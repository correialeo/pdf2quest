package com.concursos.study.importer;

import com.concursos.study.question.QuestionCategory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parser puro (sem dependencia de Spring) que extrai questoes de um PDF de prova
 * ja convertido em texto. Detecta automaticamente banca, ano, disciplina e
 * categoria (Conhecimentos Gerais/Especificos) a partir dos titulos de secao
 * presentes no proprio documento. "Assunto" nao e detectado (raramente aparece
 * no enunciado da prova) e fica disponivel para edicao manual depois.
 */
public class PdfQuestionParser {

    private static final String[] KNOWN_ORGANIZATIONS = {
            "CEBRASPE", "CESPE", "CESGRANRIO", "FCC", "FGV", "VUNESP",
            "IBFC", "IADES", "INSTITUTO AOCP", "AOCP", "QUADRIX", "CONSULPLAN", "IDECAN",
            "IBADE", "CETRO", "SELECON", "IBGP", "LEGALLE", "OBJETIVA", "AVANÇA SP", "IUDS"
    };

    private static final Pattern QUESTION_START =
            Pattern.compile("^\\s*(?:QUEST\\u00C3O\\s+|QUESTAO\\s+)?(\\d{1,3})\\s*[.\\-\\u2013\\u2014\\)]\\s*(.*)$");

    // Muitas provas colocam o numero da questao sozinho em sua propria linha,
    // com o enunciado comecando somente na linha seguinte.
    private static final Pattern STANDALONE_NUMBER = Pattern.compile("^(\\d{1,3})$");

    private static final Pattern ALTERNATIVE_LINE =
            Pattern.compile("(?m)^\\s*\\(?([A-E])\\s*[.\\-\\u2013\\u2014\\)]\\s+(.*)$");

    private static final Pattern INLINE_GABARITO =
            Pattern.compile("(?i)gabarito\\s*:?\\s*([A-E])\\b");

    // Preposicoes/conectivos minusculos aceitos dentro de um titulo de disciplina
    // (ex.: "Legislacao Acerca de Seguranca da Informacao").
    private static final Set<String> LOWER_CONNECTORS =
            Set.of("de", "da", "do", "das", "dos", "e", "em", "a", "o", "as", "os", "ao", "aos");

    private static final Pattern GABARITO_ENTRY =
            Pattern.compile("(\\d{1,3})\\s*[\\-\\u2013:]\\s*([A-E])\\b");

    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");

    public ParseResult parse(String rawText) {
        String text = rawText.replace("\r\n", "\n").replace("\r", "\n").replace("\f", "\n");
        String organization = detectOrganization(text);
        Integer year = detectYear(text);

        List<ParsedQuestion> questions = new ArrayList<>();
        List<ParseFailure> failures = new ArrayList<>();
        List<String> gabaritoLines = new ArrayList<>();

        String currentSubject = null;
        QuestionCategory currentCategory = null;

        List<String> blockLines = null;
        int blockNumber = -1;
        String blockSubject = null;
        QuestionCategory blockCategory = null;

        List<String> pendingSubjectLines = new ArrayList<>();
        boolean inGabaritoSection = false;
        // -1 = ainda nao viu nenhuma questao; aceita qualquer numero como primeiro.
        // Depois disso, so abre um novo bloco se o numero continuar a sequencia,
        // evitando que um numero solto qualquer (pagina, item de lista) vire questao.
        int expectedNumber = -1;

        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.trim();

            if (inGabaritoSection) {
                gabaritoLines.add(line);
                continue;
            }

            // "Gabarito: B" embutido no corpo da questao nao e um titulo de secao
            // (isso e tratado por INLINE_GABARITO dentro de flush()).
            boolean looksLikeInlineGabarito = INLINE_GABARITO.matcher(line).find();

            if (!looksLikeInlineGabarito && isHeaderCandidate(line)) {
                String upper = line.toUpperCase(Locale.ROOT);
                if (upper.contains("GABARITO") || upper.contains("RESPOSTAS")) {
                    currentSubject = commitPendingSubject(pendingSubjectLines, currentSubject);
                    if (blockLines != null) {
                        flush(blockNumber, blockLines, blockSubject, blockCategory, questions, failures);
                        blockLines = null;
                    }
                    inGabaritoSection = true;
                    continue;
                }
                if (upper.contains("CONHECIMENTOS") && upper.contains("GERA")) {
                    currentSubject = commitPendingSubject(pendingSubjectLines, currentSubject);
                    currentCategory = QuestionCategory.GERAL;
                    continue;
                }
                if (upper.contains("CONHECIMENTOS") && upper.contains("ESPEC")) {
                    currentSubject = commitPendingSubject(pendingSubjectLines, currentSubject);
                    currentCategory = QuestionCategory.ESPECIFICO;
                    continue;
                }
                pendingSubjectLines.add(line);
                continue;
            }
            currentSubject = commitPendingSubject(pendingSubjectLines, currentSubject);

            Integer candidateNumber = null;
            String remainder = "";
            Matcher qm = QUESTION_START.matcher(line);
            Matcher sn = STANDALONE_NUMBER.matcher(line);
            if (qm.matches()) {
                candidateNumber = Integer.parseInt(qm.group(1));
                remainder = qm.group(2);
            } else if (sn.matches()) {
                candidateNumber = Integer.parseInt(sn.group(1));
            }

            if (candidateNumber != null && (expectedNumber == -1 || candidateNumber == expectedNumber)) {
                if (blockLines != null) {
                    flush(blockNumber, blockLines, blockSubject, blockCategory, questions, failures);
                }
                blockNumber = candidateNumber;
                expectedNumber = candidateNumber + 1;
                blockSubject = currentSubject;
                blockCategory = currentCategory;
                blockLines = new ArrayList<>();
                if (!remainder.isBlank()) {
                    blockLines.add(remainder);
                }
                continue;
            }

            if (blockLines != null) {
                blockLines.add(line);
            }
        }
        if (blockLines != null) {
            flush(blockNumber, blockLines, blockSubject, blockCategory, questions, failures);
        }

        Map<Integer, String> gabaritoMap = parseGabaritoTable(String.join("\n", gabaritoLines));
        if (!gabaritoMap.isEmpty()) {
            questions = questions.stream()
                    .map(q -> q.correctAnswer() != null ? q : withCorrectAnswer(q, gabaritoMap.get(q.number())))
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        return new ParseResult(organization, year, questions, failures);
    }

    private ParsedQuestion withCorrectAnswer(ParsedQuestion q, String correctAnswer) {
        return new ParsedQuestion(q.number(), q.statement(), q.alternativeA(), q.alternativeB(),
                q.alternativeC(), q.alternativeD(), q.alternativeE(), correctAnswer, q.subject(), q.category());
    }

    private void flush(int number, List<String> lines, String subject, QuestionCategory category,
                        List<ParsedQuestion> questions, List<ParseFailure> failures) {
        String blockText = String.join("\n", lines).trim();
        if (blockText.isBlank()) {
            failures.add(new ParseFailure(number, "bloco vazio"));
            return;
        }

        // ALTERNATIVE_LINE so reconhece a primeira linha de cada alternativa; o texto
        // completo (quando a alternativa quebra em varias linhas no PDF) vai da onde
        // essa primeira linha termina ate o inicio da proxima alternativa (ou fim do bloco).
        record AltMatch(String letter, int contentStart, int matchStart) {
        }
        Matcher am = ALTERNATIVE_LINE.matcher(blockText);
        List<AltMatch> matches = new ArrayList<>();
        while (am.find()) {
            matches.add(new AltMatch(am.group(1), am.start(2), am.start()));
        }

        Map<String, String> alternatives = new LinkedHashMap<>();
        for (int i = 0; i < matches.size(); i++) {
            AltMatch m = matches.get(i);
            int end = (i + 1 < matches.size()) ? matches.get(i + 1).matchStart() : blockText.length();
            String text = blockText.substring(m.contentStart(), end).trim().replaceAll("\\s+", " ");
            alternatives.putIfAbsent(m.letter(), text);
        }

        int firstAltStart = matches.isEmpty() ? -1 : matches.get(0).matchStart();
        String statement = firstAltStart > 0 ? blockText.substring(0, firstAltStart).trim() : blockText;

        if (statement.isBlank() || alternatives.size() < 2) {
            failures.add(new ParseFailure(number,
                    "enunciado ou alternativas insuficientes (" + alternatives.size() + " alternativas encontradas)"));
            return;
        }

        Matcher gm = INLINE_GABARITO.matcher(blockText);
        String correct = gm.find() ? gm.group(1).toUpperCase(Locale.ROOT) : null;

        questions.add(new ParsedQuestion(number, statement,
                alternatives.get("A"), alternatives.get("B"), alternatives.get("C"),
                alternatives.get("D"), alternatives.get("E"),
                correct, subject, category));
    }

    /**
     * Reconhece linhas curtas de titulo de secao/disciplina, tanto em CAIXA ALTA
     * quanto em Title Case (ex.: "Conhecimentos Gerais", "Lingua Portuguesa"),
     * exigindo que toda palavra comece maiuscula (ou seja uma preposicao curta)
     * e que a linha nao termine como uma frase (pontuacao) nem seja alternativa/questao.
     */
    private boolean isHeaderCandidate(String line) {
        if (line.isBlank() || line.length() > 60) {
            return false;
        }
        char last = line.charAt(line.length() - 1);
        if (".,;:!?".indexOf(last) >= 0) {
            return false;
        }
        if (ALTERNATIVE_LINE.matcher(line).matches() || QUESTION_START.matcher(line).matches()
                || STANDALONE_NUMBER.matcher(line).matches()) {
            return false;
        }
        String[] words = line.split("\\s+");
        if (words.length == 0 || words.length > 6) {
            return false;
        }
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            for (int i = 0; i < w.length(); i++) {
                if (Character.isDigit(w.charAt(i))) {
                    return false;
                }
            }
            boolean startsUpper = Character.isUpperCase(w.charAt(0));
            boolean allowedLower = LOWER_CONNECTORS.contains(w.toLowerCase(Locale.ROOT));
            if (!startsUpper && !allowedLower) {
                return false;
            }
        }
        return Character.isUpperCase(words[0].charAt(0));
    }

    /** Junta linhas de titulo acumuladas (cabecalhos quebrados em 2+ linhas) num unico assunto. */
    private String commitPendingSubject(List<String> pendingSubjectLines, String currentSubject) {
        if (pendingSubjectLines.isEmpty()) {
            return currentSubject;
        }
        String subject = toTitleCase(String.join(" ", pendingSubjectLines));
        pendingSubjectLines.clear();
        return subject;
    }

    private String toTitleCase(String line) {
        String[] words = line.toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private String detectOrganization(String text) {
        // Usa borda de palavra para nao confundir bancas com palavras comuns que
        // contenham o mesmo texto (ex.: "OBJETIVA" dentro de "questoes objetivas").
        String best = null;
        int bestIndex = Integer.MAX_VALUE;
        for (String org : KNOWN_ORGANIZATIONS) {
            Matcher m = Pattern.compile("\\b" + Pattern.quote(org) + "\\b", Pattern.CASE_INSENSITIVE).matcher(text);
            if (m.find() && m.start() < bestIndex) {
                bestIndex = m.start();
                best = org;
            }
        }
        return best;
    }

    private Integer detectYear(String text) {
        Matcher m = YEAR.matcher(text);
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        while (m.find()) {
            int y = Integer.parseInt(m.group());
            counts.merge(y, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private Map<Integer, String> parseGabaritoTable(String text) {
        Map<Integer, String> map = new LinkedHashMap<>();
        Matcher m = GABARITO_ENTRY.matcher(text);
        while (m.find()) {
            map.putIfAbsent(Integer.parseInt(m.group(1)), m.group(2).toUpperCase(Locale.ROOT));
        }
        return map;
    }
}
