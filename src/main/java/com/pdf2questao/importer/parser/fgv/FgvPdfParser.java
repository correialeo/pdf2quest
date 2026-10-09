package com.pdf2questao.importer.parser.fgv;

import com.pdf2questao.importer.Banca;
import com.pdf2questao.importer.ParseFailure;
import com.pdf2questao.importer.ParseResult;
import com.pdf2questao.importer.ParsedQuestion;
import com.pdf2questao.importer.parser.ExamParser;
import com.pdf2questao.importer.parser.support.PdfMetadataDetector;
import com.pdf2questao.importer.parser.support.PdfNoiseFilter;
import com.pdf2questao.importer.parser.support.PdfTextUtils;
import com.pdf2questao.importer.parser.support.QuestionBlocks;
import com.pdf2questao.importer.parser.support.RichText;
import com.pdf2questao.question.QuestionCategory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parser de PDF de prova da banca FGV, ja convertido em texto. Detecta
 * automaticamente ano, disciplina e categoria (Conhecimentos
 * Gerais/Especificos) a partir dos titulos de secao presentes no proprio
 * documento. "Assunto" nao e detectado (raramente aparece no enunciado da
 * prova) e fica disponivel para edicao manual depois.
 */
@Component
public class FgvPdfParser implements ExamParser {

    private static final Pattern QUESTION_START =
            Pattern.compile("^\\s*(?:QUEST\\u00C3O\\s+|QUESTAO\\s+)?(\\d{1,3})\\s*[.\\-\\u2013\\u2014\\)]\\s*(.*)$");

    // Muitas provas colocam o numero da questao sozinho em sua propria linha,
    // com o enunciado comecando somente na linha seguinte.
    private static final Pattern STANDALONE_NUMBER = Pattern.compile("^(\\d{1,3})$");

    private static final Pattern ALTERNATIVE_LINE =
            Pattern.compile("(?m)^\\s*\\(?([A-E])\\s*[.\\-\\u2013\\u2014\\)]\\s+(.*)$");

    private static final Pattern INLINE_GABARITO =
            Pattern.compile("(?i)gabarito\\s*:?\\s*([A-E])\\b");

    private static final Pattern GABARITO_ENTRY =
            Pattern.compile("(\\d{1,3})\\s*[\\-\\u2013:]\\s*([A-E])\\b");

    // Frases de instrucao que introduzem um texto de apoio (interpretacao de
    // texto, comum em Lingua Inglesa/Portuguesa), ex.: "Use the following TEXT
    // to answer the next six questions." Nada depois delas pertence a questao
    // anterior - servem de fronteira de bloco, igual GABARITO/CONHECIMENTOS.
    private static final Pattern READING_PASSAGE_CUE = Pattern.compile(
            "(?i)^(use|read|utilize|leia|considere|analise|observe)\\b.*\\btext[oe]?s?\\b.*\\b(quest(\\u00e3|a)o|question)");

    @Override
    public Banca banca() {
        return Banca.FGV;
    }

    @Override
    public ParseResult parse(String rawText) {
        String text = rawText.replace("\r\n", "\n").replace("\r", "\n").replace("\f", "\n");
        String plainText = RichText.plain(text);
        String organization = PdfMetadataDetector.detectOrganization(plainText);
        Integer year = PdfMetadataDetector.detectYear(plainText);
        Set<String> repeatedBrandingLines = PdfNoiseFilter.detectRepeatedBrandingLines(plainText);

        List<ParsedQuestion> questions = new ArrayList<>();
        List<ParseFailure> failures = new ArrayList<>();
        List<String> gabaritoLines = new ArrayList<>();

        String currentSubject = null;
        QuestionCategory currentCategory = null;
        Integer currentPage = null;

        Block block = null;

        List<String> pendingSubjectLines = new ArrayList<>();
        boolean inGabaritoSection = false;
        // -1 = ainda nao viu nenhuma questao; aceita qualquer numero como primeiro.
        // Depois disso, so abre um novo bloco se o numero continuar a sequencia,
        // evitando que um numero solto qualquer (pagina, item de lista) vire questao.
        int expectedNumber = -1;
        // Fica true assim que um texto corrido comum (nao cabecalho) aparece depois
        // do ultimo ponto de corte conhecido (troca de categoria ou inicio de bloco).
        // Um titulo de disciplina legitimo so aparece bem no comeco desse intervalo,
        // antes de qualquer texto de apoio - depois disso, linhas curtas que parecem
        // cabecalho sao nome de autor/citacao dentro do texto (comum em Ingles).
        boolean gapHasBodyText = false;

        // Linhas soltas entre questoes viram o texto de apoio das seguintes, ate a
        // quantidade anunciada ("next six questions"), outro texto ou outra disciplina.
        Passages passages = new Passages();

        for (String rawLine : text.split("\n", -1)) {
            Integer page = RichText.pageOf(rawLine);
            if (page != null) {
                currentPage = page;
            }
            String rich = RichText.trim(rawLine);
            String line = RichText.plain(rich);

            if (PdfNoiseFilter.isPageNoise(line, repeatedBrandingLines)) {
                continue;
            }

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
                    if (block != null) {
                        flush(block, questions, failures);
                        block = null;
                    }
                    inGabaritoSection = true;
                    continue;
                }
                if (upper.contains("CONHECIMENTOS") && upper.contains("GERA")) {
                    if (block != null) {
                        flush(block, questions, failures);
                        block = null;
                    }
                    pendingSubjectLines.clear();
                    currentSubject = null;
                    currentCategory = QuestionCategory.GERAL;
                    gapHasBodyText = false;
                    passages.reset();
                    continue;
                }
                if (upper.contains("CONHECIMENTOS") && upper.contains("ESPEC")) {
                    if (block != null) {
                        flush(block, questions, failures);
                        block = null;
                    }
                    pendingSubjectLines.clear();
                    currentSubject = null;
                    currentCategory = QuestionCategory.ESPECIFICO;
                    gapHasBodyText = false;
                    passages.reset();
                    continue;
                }
                if (!gapHasBodyText && !QuestionBlocks.PASSAGE_TITLE.matcher(line).matches()) {
                    // Titulo de disciplina legitimo: fecha o bloco anterior (se houver)
                    // para o texto de apoio que vem a seguir nao grudar na ultima
                    // alternativa dele.
                    if (block != null) {
                        flush(block, questions, failures);
                        block = null;
                    }
                    pendingSubjectLines.add(line);
                    continue;
                }
                // Senao: parece cabecalho mas veio depois de texto corrido ja ter
                // comecado nesse intervalo - e titulo/autor dentro do texto de apoio.
                if (block == null) {
                    if (currentCategory != null) {
                        passages.add(rich);
                    }
                    continue;
                }
            }
            if (READING_PASSAGE_CUE.matcher(line).find()
                    || (block != null && block.hasAlternatives()
                    && QuestionBlocks.PASSAGE_TITLE.matcher(line).matches())) {
                if (block != null) {
                    flush(block, questions, failures);
                    block = null;
                }
                // Essa frase ja anuncia que um texto de apoio comeca agora - nada
                // depois dela (nem o titulo do texto) deve ser lido como titulo de
                // disciplina, entao marca o "gap" como se ja tivesse corpo de texto.
                gapHasBodyText = true;
                passages.startNew(QuestionBlocks.cueCount(line));
                if (QuestionBlocks.PASSAGE_TITLE.matcher(line).matches()) {
                    passages.add(rich);
                }
                continue;
            }
            String previousSubject = currentSubject;
            currentSubject = commitPendingSubject(pendingSubjectLines, currentSubject);
            if (!Objects.equals(previousSubject, currentSubject)) {
                passages.subjectChanged();
            }

            Integer candidateNumber = null;
            String remainder = "";
            Matcher qm = QUESTION_START.matcher(line);
            Matcher sn = STANDALONE_NUMBER.matcher(line);
            if (qm.matches()) {
                candidateNumber = Integer.parseInt(qm.group(1));
                remainder = RichText.fromVisible(rich, qm.start(2));
            } else if (sn.matches()) {
                candidateNumber = Integer.parseInt(sn.group(1));
            }

            if (candidateNumber != null && (expectedNumber == -1 || candidateNumber == expectedNumber)) {
                if (block != null) {
                    flush(block, questions, failures);
                }
                expectedNumber = candidateNumber + 1;
                block = new Block(candidateNumber, currentSubject, currentCategory, passages.nextQuestion(),
                        currentPage);
                gapHasBodyText = false;
                if (!RichText.plain(remainder).isBlank()) {
                    block.lines.add(remainder);
                }
                continue;
            }

            if (block != null) {
                block.lines.add(rich);
            } else {
                if (!line.isBlank()) {
                    gapHasBodyText = true;
                }
                if (currentCategory != null) {
                    passages.add(rich);
                }
            }
        }
        if (block != null) {
            flush(block, questions, failures);
        }

        Map<Integer, String> gabaritoMap = parseInlineGabaritoSection(String.join("\n", gabaritoLines));
        if (!gabaritoMap.isEmpty()) {
            questions = questions.stream()
                    .map(q -> q.correctAnswer() != null ? q : q.withCorrectAnswer(gabaritoMap.get(q.number())))
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        return new ParseResult(organization, year, questions, failures);
    }

    private void flush(Block block, List<ParsedQuestion> questions, List<ParseFailure> failures) {
        if (!QuestionBlocks.hasVisibleText(block.lines)) {
            failures.add(new ParseFailure(block.number, "bloco vazio"));
            return;
        }

        QuestionBlocks.Split split = QuestionBlocks.split(block.lines, ALTERNATIVE_LINE);
        Map<String, String> alternatives = split.alternatives();
        String statement = split.statement();

        if (RichText.plain(statement).isBlank() || alternatives.size() < 2) {
            failures.add(new ParseFailure(block.number,
                    "enunciado ou alternativas insuficientes (" + alternatives.size() + " alternativas encontradas)"));
            return;
        }

        Matcher gm = INLINE_GABARITO.matcher(RichText.plain(String.join("\n", block.lines)));
        String correct = gm.find() ? gm.group(1).toUpperCase(Locale.ROOT) : null;

        questions.add(new ParsedQuestion(block.number, statement,
                alternatives.get("A"), alternatives.get("B"), alternatives.get("C"),
                alternatives.get("D"), alternatives.get("E"),
                correct, block.subject, block.category, block.passage, block.page));
    }

    private static final class Block {
        final int number;
        final String subject;
        final QuestionCategory category;
        final String passage;
        final Integer page;
        final List<String> lines = new ArrayList<>();

        Block(int number, String subject, QuestionCategory category, String passage, Integer page) {
            this.number = number;
            this.subject = subject;
            this.category = category;
            this.passage = passage;
            this.page = page;
        }

        boolean hasAlternatives() {
            return ALTERNATIVE_LINE.matcher(RichText.plain(String.join("\n", lines))).find();
        }
    }

    private static final class Passages {
        private final List<String> collecting = new ArrayList<>();
        private int collectingCount = -1;
        private String active;
        private int remaining;

        void add(String richLine) {
            collecting.add(richLine);
        }

        void startNew(int count) {
            collecting.clear();
            collectingCount = count;
        }

        void subjectChanged() {
            active = null;
        }

        void reset() {
            collecting.clear();
            collectingCount = -1;
            active = null;
        }

        String nextQuestion() {
            if (QuestionBlocks.hasVisibleText(collecting)) {
                active = QuestionBlocks.join(collecting);
                remaining = collectingCount;
            }
            collecting.clear();
            collectingCount = -1;
            if (active == null || remaining == 0) {
                return null;
            }
            if (remaining > 0) {
                remaining--;
            }
            return active;
        }
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
            boolean allowedLower = PdfTextUtils.LOWER_CONNECTORS.contains(w.toLowerCase(Locale.ROOT));
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
        String subject = PdfTextUtils.toTitleCase(String.join(" ", pendingSubjectLines));
        pendingSubjectLines.clear();
        return subject;
    }

    /**
     * Le pares numero-letra (ex.: "12 - A") de um gabarito embutido no fim da
     * propria prova, quando o PDF de prova traz o gabarito na ultima pagina em
     * vez de vir num PDF separado. Formato distinto do resolvido por
     * {@link com.pdf2questao.importer.parser.fgv.FgvGabaritoParser}, que
     * localiza o gabarito por titulo de disciplina e le blocos de numero numa
     * linha seguido da letra na proxima - layout tipico de um PDF de gabarito
     * publicado a parte.
     */
    private Map<Integer, String> parseInlineGabaritoSection(String text) {
        Map<Integer, String> map = new LinkedHashMap<>();
        Matcher m = GABARITO_ENTRY.matcher(text);
        while (m.find()) {
            map.putIfAbsent(Integer.parseInt(m.group(1)), m.group(2).toUpperCase(Locale.ROOT));
        }
        return map;
    }
}
