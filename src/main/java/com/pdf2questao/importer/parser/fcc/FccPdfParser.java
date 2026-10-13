package com.pdf2questao.importer.parser.fcc;

import com.pdf2questao.importer.Banca;
import com.pdf2questao.importer.ParseFailure;
import com.pdf2questao.importer.ParseResult;
import com.pdf2questao.importer.ParsedQuestion;
import com.pdf2questao.importer.parser.ExamParser;
import com.pdf2questao.importer.parser.support.PdfMetadataDetector;
import com.pdf2questao.importer.parser.support.RichText;
import com.pdf2questao.question.QuestionCategory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser de prova da FCC lida por OCR (o PDF e so imagem, ver {@code OcrService}). O texto vem
 * em coluna unica, com o numero da questao como "N." no comeco da linha, as alternativas
 * "(A)".."(E)" e um cabecalho/rodape repetido por pagina. Como o OCR erra marcadores
 * ("{A)", "(0)" no lugar de "(D)"), as alternativas sao lidas na ordem A..E aceitando as
 * confusoes comuns para a letra esperada, e a numeracao so avanca em sequencia (1, 2, 3...)
 * depois que a questao anterior ja mostrou alternativas - assim itens "1." "2." dentro de um
 * enunciado nao viram questao.
 *
 * <p>Paragrafos soltos entre uma questao e a proxima sao titulos de secao (linha curta, sem
 * ponto final) ou texto de apoio ("Atencao: Para responder as questoes de numeros 1 a 8...").
 * O titulo vira so uma sugestao em {@code subject}; quem manda e o {@code SectionMap}.
 * Questoes com sinais de imagem, tabela, codigo, formula ou trecho destacado saem com
 * {@code needsReview}.
 */
@Component
public class FccPdfParser implements ExamParser {

    private static final Pattern HEADER_LINE = Pattern.compile("(?i)(\\bde\\s+prova\\b.{0,15}\\bti?po\\s*\\d+|\\bti?po\\s*00\\d\\b)");
    // Rodape conhecido da FAZSP; o ruido de outras provas e pego por repeticao nas bordas das paginas
    // (ver edgeNoise), sem depender do nome do orgao.
    private static final Pattern FOOTER_LINE = Pattern.compile(
            "(?i)(\\bFA[ZS2]\\s?SP\\b.*prova\\s*\\d|^p[a\u00E1]gina\\s+\\d+)");
    // "PROVA 2 - CONHECIMENTOS BASICOS", "Prova 1 - Lingua Portuguesa"... O texto depois do tracado e o titulo.
    private static final Pattern PROVA_TITLE = Pattern.compile("(?i)\\bprova\\s*(\\d+)\\s*[\\-\u2013\\u2014]+\\s*(\\S.*)$");
    // Titulo de secao sozinho na linha, usado quando o caderno nao tem "PROVA N -" (cadernos unicos).
    private static final Pattern STANDALONE_SECTION = Pattern.compile(
            "(?i)^\\W*(conhecimentos\\s+(gerais|b\\S+sicos|espec\\S+ficos))\\W*(\\(.*\\))?$");
    // O OCR troca digitos por simbolos parecidos ("53." vira "&3."), entao o token e comparado ao numero
    // esperado caractere a caractere (ver isQuestionNumber) em vez de exigir digitos.
    private static final Pattern QUESTION_START = Pattern.compile("^([A-Za-z0-9&$|!]{1,3})\\s*\\.\\s*(.*)$");
    private static final Pattern ALTERNATIVE_MARKER =
            Pattern.compile("^\\s*[({\\[|]?\\s*([A-Za-z0-9€@&]{1,2})\\s*[)\\]}]\\s*(.*)$");
    // Marcador com o fecha-parenteses perdido: "(0 E-F-E" ou "(A F-F-E".
    private static final Pattern OPEN_ONLY_MARKER =
            Pattern.compile("^\\s*[({\\[|]\\s*([A-Za-z0-9\u20AC@&]{1,2})\\s+(\\S.*)$");
    // Sem nenhum parenteses: "B E-F-E-E-E" (so vale para a letra exata esperada, depois de uma alternativa normal).
    private static final Pattern BARE_MARKER = Pattern.compile("^\\s*([A-E])\\s+(\\S.*)$");
    // Alternativas em forma de tabela, lidas sem nenhum parenteses ("A Business ...", "Cc Data ...").
    private static final Pattern BARE_ROW = Pattern.compile("^\\s*([A-E])[a-z]?\\s+(\\S.*)$");
    private static final Pattern STRICT_MARKER = Pattern.compile("^\\s*\\(([A-E])\\)\\s*(.*)$");
    private static final Pattern PASSAGE_CUE = Pattern.compile("(?i)quest(õ|o)es?\\b(.*)");
    private static final Pattern NUMBER_RANGE = Pattern.compile("(\\d{1,3})\\s*(?:a|à|ao|até|-)\\s*(\\d{1,3})");
    private static final Pattern NUMBER = Pattern.compile("\\b\\d{1,3}\\b");
    private static final Pattern CUE_END = Pattern.compile("(?i)[.;:]|\\b(baseie|considere|leia|utilize|com base)");

    private static final Pattern MENTIONS_VISUAL = Pattern.compile(
            "(?i)\\b(figura|tabela|gr[aá]fico|quadro|diagrama|imagem|ilustra[cç][aã]o|fluxograma|organograma"
                    + "|c[oó]digo|script|trecho de programa)\\b");
    // Negrito/italico/destaque ainda se perdem no OCR; so o sublinhado e recuperado (ver UnderlineDetector).
    private static final Pattern MENTIONS_HIGHLIGHT = Pattern.compile(
            "(?i)\\b(destacad[oa]s?|grifad[oa]s?|em negrito|em it[aá]lico|entre aspas)\\b");
    private static final Pattern MENTIONS_UNDERLINE = Pattern.compile("(?i)\\bsublinhad[oa]s?\\b");
    private static final Pattern CODE_LINE = Pattern.compile(
            "(?m)^\\s*(SELECT\\b|FROM\\b|WHERE\\b|INSERT\\b|CREATE\\b|def\\s|class\\s|import\\s|public\\s|for\\s*\\(|"
                    + "if\\s*\\(|while\\s*\\(|return\\b|print\\s*\\(|<[A-Za-z/][^>]*>|[{}]\\s*$).*|.*(==|!=|=>|->|\\);|\\{\\s*$).*");
    private static final Pattern TABLE_LINE = Pattern.compile("\\|.*\\||(?:\\b\\d[\\d.,]*\\b\\s+){3,}\\d[\\d.,]*");

    private static final int MAX_HEADING_LENGTH = 90;
    /** Marcador lido mas sem texto (o OCR perdeu o conteudo): fica o aviso e a questao vai para revisao. */
    static final String EMPTY_ALTERNATIVE = "[texto nao lido pelo OCR - ver PDF]";

    @Override
    public Banca banca() {
        return Banca.FCC;
    }

    @Override
    public ParseResult parse(String rawText) {
        String text = rawText.replace("\r\n", "\n").replace("\r", "\n").replace("\f", "\n");
        String plainText = RichText.plain(text);
        // Provas da FCC sao sempre da FCC; o detector generico confundia com palavras do texto ("OBJETIVA").
        String organization = "FCC";
        Integer year = detectYear(text, plainText);
        Set<String> noise = edgeNoise(text);

        String[] title = new String[1];
        List<Block> blocks = splitBlocks(text, true, noise, title);
        if (blocks.isEmpty()) {
            // Sem o titulo "PROVA N - ..." (caderno unico, documento fora do padrao): tenta desde o inicio,
            // reconhecendo "CONHECIMENTOS GERAIS/ESPECIFICOS" sozinhos na linha como secoes.
            blocks = splitBlocks(text, false, noise, title);
        }

        List<ParsedQuestion> questions = new ArrayList<>();
        List<ParseFailure> failures = new ArrayList<>();
        Map<Integer, String> passages = new LinkedHashMap<>();
        String heading = null;

        // O bloco 0 guarda o que vem antes da questao 1 (titulo de secao, texto de apoio).
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            Block next = i + 1 < blocks.size() ? blocks.get(i + 1) : null;
            if (next != null) {
                Trailing trailing = Trailing.of(block.trailing);
                next.headingBefore = trailing.heading;
                // Antes da questao 1 so ha capa/instrucoes: so vira texto de apoio se disser a que questoes se refere.
                boolean coverText = block.number == 0 && (trailing.passage == null
                        || passageNumbers(trailing.passage).isEmpty());
                registerPassage(coverText ? null : trailing.passage, next.number, passages);
            }
        }

        for (Block block : blocks) {
            if (block.number == 0) {
                continue;
            }
            if (block.headingBefore != null) {
                heading = block.headingBefore;
            }
            String statement = join(block.head);
            if (RichText.plain(statement).isBlank() || block.altCount() < 2) {
                failures.add(new ParseFailure(block.number, "enunciado ou alternativas insuficientes"));
                continue;
            }
            List<String> reasons = reviewReasons(block, statement);
            questions.add(new ParsedQuestion(block.number, statement,
                    block.alt(0), block.alt(1), block.alt(2), block.alt(3), block.alt(4),
                    null, heading, block.category != null ? block.category : categoryFromBlocks(blocks),
                    passages.get(block.number), block.page, !reasons.isEmpty(), String.join("; ", reasons),
                    block.headingBefore));
        }
        return new ParseResult(organization, year, questions, failures, title[0]);
    }

    private QuestionCategory categoryFromBlocks(List<Block> blocks) {
        return blocks.stream().map(b -> b.category).filter(c -> c != null).findFirst().orElse(null);
    }

    // ---- segmentacao em blocos de questao ----

    private List<Block> splitBlocks(String text, boolean requireProvaTitle, Set<String> noise, String[] titleOut) {
        List<Block> blocks = new ArrayList<>();
        Block block = null;
        QuestionCategory category = null;
        boolean started = !requireProvaTitle;
        int expected = 1;
        Integer page = null;
        boolean pendingBlank = false;

        for (String rawLine : text.split("\n", -1)) {
            Integer linePage = RichText.pageOf(rawLine);
            if (linePage != null) {
                page = linePage;
            }
            // 'rich' guarda negrito/sublinhado vindos do OCR; toda decisao do parser usa 'line' (texto puro).
            String rich = RichText.trim(RichText.removeLayoutTokens(rawLine).replace(' ', ' '));
            String line = RichText.plain(rich);

            // Numero de pagina solto (o numero de questao sempre vem com ponto: "20.").
            if (HEADER_LINE.matcher(line).find() || FOOTER_LINE.matcher(line).find()
                    || line.matches("\\d{1,3}") || noise.contains(normalizeEdge(line))) {
                continue;
            }
            Matcher title = PROVA_TITLE.matcher(line);
            Matcher standalone = STANDALONE_SECTION.matcher(line);
            boolean isTitle = title.find();
            if (isTitle || (!requireProvaTitle && standalone.matches())) {
                started = true;
                String name = isTitle ? title.group(2) : standalone.group(1);
                QuestionCategory detected = categoryOf(name);
                if (detected != null) {
                    category = detected;
                }
                if (block != null && block.number == 0) {
                    // Capa/instrucoes que vieram antes do titulo da secao nao sao titulo nem texto de apoio.
                    block.trailing.clear();
                }
                if (titleOut[0] == null) {
                    titleOut[0] = isTitle ? "Prova " + title.group(1) + " \u2013 " + titleCase(name) : titleCase(name);
                }
                if (block == null) {
                    block = new Block(0, page);
                    block.category = category;
                    blocks.add(block);
                }
                continue;
            }
            if (!started) {
                continue;
            }
            if (line.isEmpty()) {
                pendingBlank = true;
                if (block != null) {
                    block.addBlank();
                }
                continue;
            }

            Matcher start = QUESTION_START.matcher(line);
            if (start.matches()) {
                String token = start.group(1);
                boolean exact = isQuestionNumber(token, expected)
                        && (block == null || block.number == 0 || block.hasAlternatives() || expected == 1);
                // Numero que o OCR destruiu por completo ("72." lido como "ta."): se a questao anterior ja
                // fechou a alternativa (E) e a linha comeca como "xx. Texto", e a proxima questao.
                boolean guessed = !exact && block != null && block.number > 0 && block.endsAfterAlternativeE()
                        && !token.chars().allMatch(Character::isDigit)
                        && !start.group(2).isEmpty() && Character.isUpperCase(start.group(2).charAt(0));
                if (exact || guessed) {
                    block = new Block(expected, page);
                    block.category = category;
                    if (guessed) {
                        block.numberGuess = token;
                    }
                    blocks.add(block);
                    expected++;
                    block.addHead(RichText.fromVisible(rich, start.start(2)));
                    pendingBlank = false;
                    continue;
                }
            }
            if (block == null) {
                block = new Block(0, page);
                block.category = category;
                blocks.add(block);
            }
            block.addLine(line, rich, pendingBlank);
            pendingBlank = false;
        }
        return blocks.stream().anyMatch(b -> b.number > 0) ? blocks : List.of();
    }

    private static QuestionCategory categoryOf(String sectionName) {
        String n = sectionName.toLowerCase();
        if (n.contains("espec")) {
            return QuestionCategory.ESPECIFICO;
        }
        return n.contains("gerais") || n.contains("b\u00E1sicos") || n.contains("basicos")
                || n.matches(".*b\\S+sicos.*") ? QuestionCategory.GERAL : null;
    }

    private static String titleCase(String s) {
        StringBuilder out = new StringBuilder();
        for (String word : s.trim().toLowerCase().split("\\s+")) {
            if (out.length() > 0) {
                out.append(' ');
            }
            boolean small = word.length() <= 2 || word.equals("das") || word.equals("dos");
            out.append(small && out.length() > 0 ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        return out.toString();
    }

    private static final Pattern MONTH_YEAR = Pattern.compile(
            "(?i)(janeiro|fevereiro|mar[c\u00E7]o|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro)"
                    + "\\s*/\\s*((?:19|20)\\d{2})");

    /** Ano da capa ("Marco/2026"); o ano mais frequente no texto inteiro erra (enunciados citam anos de fatos). */
    private static Integer detectYear(String text, String plainText) {
        int secondPage = text.indexOf(RichText.page(2));
        String cover = secondPage > 0 ? RichText.plain(text.substring(0, secondPage)) : plainText;
        Matcher m = MONTH_YEAR.matcher(cover);
        if (m.find()) {
            return Integer.parseInt(m.group(2));
        }
        Matcher any = PdfMetadataDetector.YEAR.matcher(cover);
        return any.find() ? Integer.valueOf(any.group()) : PdfMetadataDetector.detectYear(plainText);
    }

    private static String normalizeEdge(String line) {
        return line.toLowerCase().replaceAll("[^\\p{L}]+", " ").trim();
    }

    /**
     * Linhas que se repetem nas bordas (2 primeiras e 2 ultimas linhas) de boa parte das paginas:
     * cabecalho e rodape do caderno, qualquer que seja o orgao. Numeros sao ignorados na comparacao
     * (o numero da pagina muda). Linhas que parecem questao ou alternativa nunca entram.
     */
    static Set<String> edgeNoise(String text) {
        List<List<String>> pages = new ArrayList<>();
        List<String> current = null;
        for (String raw : text.split("\n", -1)) {
            if (RichText.pageOf(raw) != null) {
                current = new ArrayList<>();
                pages.add(current);
            }
            String line = RichText.plain(RichText.removeLayoutTokens(raw)).trim();
            if (current != null && !line.isEmpty()) {
                current.add(line);
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (List<String> page : pages) {
            Set<String> edges = new LinkedHashSet<>();
            for (int i = 0; i < page.size(); i++) {
                if (i < 2 || i >= page.size() - 2) {
                    String line = page.get(i);
                    String norm = normalizeEdge(line);
                    boolean looksLikeContent = QUESTION_START.matcher(line).matches()
                            || ALTERNATIVE_MARKER.matcher(line).matches() || line.startsWith("(");
                    if (norm.length() >= 8 && !looksLikeContent) {
                        edges.add(norm);
                    }
                }
            }
            edges.forEach(e -> counts.merge(e, 1, Integer::sum));
        }
        int threshold = Math.max(3, (int) Math.ceil(pages.size() * 0.4));
        Set<String> noise = new LinkedHashSet<>();
        counts.forEach((line, n) -> {
            if (pages.size() >= 4 && n >= threshold) {
                noise.add(line);
            }
        });
        return noise;
    }

    /** O token e o numero esperado, aceitando confusoes de OCR (S/&/$ por 5, O por 0, I/l por 1...). */
    static boolean isQuestionNumber(String token, int expected) {
        String digits = String.valueOf(expected);
        if (token.length() != digits.length()) {
            return false;
        }
        boolean hasRealDigit = false;
        for (int i = 0; i < digits.length(); i++) {
            char c = token.charAt(i);
            char d = digits.charAt(i);
            if (c == d) {
                hasRealDigit = true;
            } else if (digitConfusions(d).indexOf(c) < 0) {
                return false;
            }
        }
        // Um numero de um digito so vale se for o proprio digito ("S." sozinho e so uma letra);
        // com dois ou mais ("TO." por "70.") o tamanho e a posicao ja dao confianca.
        return hasRealDigit || digits.length() > 1;
    }

    private static String digitConfusions(char digit) {
        return switch (digit) {
            case '0' -> "OoDQ";
            case '1' -> "Il|!";
            case '2' -> "Zz";
            case '4' -> "A";
            case '5' -> "S&$s";
            case '6' -> "Gb";
            case '7' -> "T";
            case '8' -> "B&";
            case '9' -> "gq";
            default -> "";
        };
    }

    // ---- texto de apoio ----

    private void registerPassage(String passage, int nextNumber, Map<Integer, String> passages) {
        if (passage == null || passage.isBlank()) {
            return;
        }
        Set<Integer> numbers = passageNumbers(passage);
        if (numbers.isEmpty()) {
            numbers.add(nextNumber);
        }
        for (int n : numbers) {
            passages.merge(n, passage, (a, b) -> a.equals(b) ? a : a + "\n\n" + b);
        }
    }

    /** "Atencao: Para responder as questoes de numeros 54, 55 e 58 / 56 a 58, baseie-se..." -> {54,55,56,57,58}. */
    static Set<Integer> passageNumbers(String richPassage) {
        String passage = RichText.plain(richPassage);
        Set<Integer> numbers = new LinkedHashSet<>();
        String firstLines = Arrays.stream(passage.split("\n")).limit(3).reduce("", (a, b) -> a + " " + b);
        Matcher cue = PASSAGE_CUE.matcher(firstLines);
        if (!cue.find()) {
            return numbers;
        }
        String tail = cue.group(2);
        Matcher end = CUE_END.matcher(tail);
        if (end.find()) {
            tail = tail.substring(0, end.start());
        }
        Matcher range = NUMBER_RANGE.matcher(tail);
        while (range.find()) {
            int from = Integer.parseInt(range.group(1));
            int to = Integer.parseInt(range.group(2));
            for (int n = from; n <= to && n - from < 200; n++) {
                numbers.add(n);
            }
        }
        String withoutRanges = NUMBER_RANGE.matcher(tail).replaceAll(" ");
        Matcher single = NUMBER.matcher(withoutRanges);
        while (single.find()) {
            numbers.add(Integer.parseInt(single.group()));
        }
        return numbers;
    }

    private static final class Trailing {
        String heading;
        String passage;

        /** Paragrafos entre o fim das alternativas e a proxima questao: titulos primeiro, depois texto de apoio. */
        static Trailing of(List<String> lines) {
            Trailing result = new Trailing();
            List<List<String>> paragraphs = new ArrayList<>();
            List<String> current = new ArrayList<>();
            for (String line : lines) {
                if (line.isEmpty()) {
                    if (!current.isEmpty()) {
                        paragraphs.add(current);
                        current = new ArrayList<>();
                    }
                } else {
                    current.add(line);
                }
            }
            if (!current.isEmpty()) {
                paragraphs.add(current);
            }
            int i = 0;
            while (i < paragraphs.size() && isHeading(paragraphs.get(i))) {
                result.heading = RichText.plain(paragraphs.get(i).get(0));
                i++;
            }
            if (i < paragraphs.size()) {
                result.passage = paragraphs.subList(i, paragraphs.size()).stream()
                        .map(FccPdfParser::joinLines).reduce((a, b) -> a + "\n" + b).orElse(null);
            }
            return result;
        }

        private static boolean isHeading(List<String> paragraph) {
            if (paragraph.size() != 1) {
                return false;
            }
            String line = RichText.plain(paragraph.get(0));
            // So letras, espacos e pontuacao de titulo: descarta lixo de OCR ("P(X=m+1)", "RM 001").
            return line.length() <= MAX_HEADING_LENGTH && line.matches("[\\p{L} :,/\\u2013\\u2014-]+") && !line.endsWith(".") && !line.endsWith(":")
                    && !line.toLowerCase().startsWith("aten") && Character.isUpperCase(line.charAt(0));
        }
    }

    // ---- sinais de leitura incerta ----

    private List<String> reviewReasons(Block block, String richStatement) {
        // Os sinais de leitura incerta olham o texto puro, sem as marcas de estilo.
        String statement = RichText.plain(richStatement);
        List<String> reasons = new ArrayList<>();
        if (block.recoveredBare) {
            reasons.add("alternativas lidas como linhas de tabela, sem parenteses");
        }
        if (block.numberGuess != null) {
            reasons.add("numero da questao ilegivel no OCR (lido como '" + block.numberGuess + ".'), deduzido pela sequencia");
        }
        for (int i = 0; i < 5; i++) {
            if (block.alt(i) == null || block.alt(i).isBlank()) {
                reasons.add("alternativa " + (char) ('A' + i) + " nao encontrada");
            }
        }
        for (int i = 0; i < 5; i++) {
            if (EMPTY_ALTERNATIVE.equals(block.alt(i))) {
                reasons.add("alternativa " + (char) ('A' + i) + " sem texto no OCR");
            }
        }
        if (statement.length() < 15) {
            reasons.add("enunciado muito curto");
        }
        if (MENTIONS_VISUAL.matcher(statement).find()) {
            reasons.add("cita figura/tabela/quadro/codigo");
        }
        if (MENTIONS_HIGHLIGHT.matcher(statement).find()) {
            reasons.add("cita trecho destacado (formatacao perdida no OCR)");
        }
        // "o segmento sublinhado": so e problema se nenhum sublinhado foi achado na questao.
        if (MENTIONS_UNDERLINE.matcher(statement).find() && !hasUnderline(block, richStatement)) {
            reasons.add("cita trecho sublinhado, mas nenhum sublinhado foi detectado no PDF");
        }
        StringBuilder all = new StringBuilder(statement);
        for (int i = 0; i < 5; i++) {
            all.append('\n').append(block.alt(i) == null ? "" : RichText.plain(block.alt(i)));
        }
        String everything = all.toString();
        if (CODE_LINE.matcher(everything).find()) {
            reasons.add("possivel codigo");
        }
        if (TABLE_LINE.matcher(everything).find()) {
            reasons.add("possivel tabela");
        }
        if (symbolRatio(statement) > 0.08) {
            reasons.add("muitos simbolos (formula?)");
        }
        if (shortLineRun(block.head) >= 4) {
            reasons.add("varias linhas curtas seguidas (tabela?)");
        }
        return reasons;
    }

    private static boolean hasUnderline(Block block, String richStatement) {
        String open = RichText.open('u');
        if (richStatement.contains(open)) {
            return true;
        }
        for (int i = 0; i < 5; i++) {
            String alt = block.alt(i);
            if (alt != null && alt.contains(open)) {
                return true;
            }
        }
        return false;
    }

    private double symbolRatio(String s) {
        int symbols = 0;
        int total = 0;
        for (char c : s.toCharArray()) {
            if (Character.isWhitespace(c)) {
                continue;
            }
            total++;
            if (!Character.isLetterOrDigit(c) && ".,;:()-'\"%$/?!“”‘’–—ºª".indexOf(c) < 0) {
                symbols++;
            }
        }
        return total == 0 ? 0 : (double) symbols / total;
    }

    private int shortLineRun(List<String> lines) {
        int run = 0;
        int best = 0;
        for (String line : lines) {
            if (!line.isEmpty() && RichText.plain(line).length() <= 14) {
                best = Math.max(best, ++run);
            } else {
                run = 0;
            }
        }
        return best;
    }

    // ---- montagem de texto ----

    private String join(List<String> lines) {
        List<String> paragraphs = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : lines) {
            if (line.isEmpty()) {
                if (!current.isEmpty()) {
                    paragraphs.add(joinLines(current));
                    current = new ArrayList<>();
                }
            } else {
                current.add(line);
            }
        }
        if (!current.isEmpty()) {
            paragraphs.add(joinLines(current));
        }
        return String.join("\n", paragraphs).trim();
    }

    /**
     * Junta linhas de um paragrafo, desfazendo hifenizacao de fim de linha ("Re-" + "ceita" -> "Receita").
     * As linhas podem ter marcas de estilo; um sublinhado que continua na linha seguinte vira um so.
     */
    static String joinLines(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (out.length() == 0) {
                out.append(line);
                continue;
            }
            String plainOut = RichText.plain(out.toString());
            String plainLine = RichText.plain(line);
            int n = plainOut.length();
            boolean hyphenated = plainOut.endsWith("-") && n > 1 && Character.isLowerCase(plainOut.charAt(n - 2))
                    && !plainLine.isEmpty() && Character.isLowerCase(plainLine.charAt(0));
            if (hyphenated) {
                // Tira so o hifen; marcas de estilo que vinham depois dele continuam no lugar.
                out.deleteCharAt(out.lastIndexOf("-"));
            } else {
                out.append(' ');
            }
            out.append(line);
        }
        return mergeAdjacentStyles(out.toString());
    }

    /** "⟦/u⟧ ⟦u⟧" e "⟦/u⟧⟦u⟧" (mesmo estilo fechando e reabrindo) viram um estilo continuo. */
    static String mergeAdjacentStyles(String rich) {
        return rich.replaceAll("\u27E6/([biu])\u27E7(\\s?)\u27E6\\1\u27E7", "$2");
    }

    private static String confusableLetters(char letter) {
        return switch (letter) {
            case 'A' -> "A4@&8";
            case 'B' -> "B8";
            case 'C' -> "CG€0OQE";
            case 'D' -> "D0OQ";
            default -> "EF€3";
        };
    }

    private static final class Block {
        final int number;
        final Integer page;
        QuestionCategory category;
        String headingBefore;
        final List<String> head = new ArrayList<>();
        final List<String> trailing = new ArrayList<>();
        /** Indice da ultima alternativa aberta (-1: ainda no enunciado). */
        int altIndex = -1;
        /** Preenchido quando o numero da questao nao foi lido e foi deduzido pela sequencia. */
        String numberGuess;
        private boolean blankAfterLastAlt;
        private boolean parenSeen;
        private final boolean[] markerSeen = new boolean[5];
        boolean recoveredBare;
        private boolean inTrailing;
        private final List<List<String>> altText = new ArrayList<>(
                Arrays.asList(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));

        Block(int number, Integer page) {
            this.number = number;
            this.page = page;
        }

        /** True se ja viu alternativas; sem elas, tenta reconhecer uma tabela A..E sem parenteses no enunciado. */
        boolean hasAlternatives() {
            if (altIndex >= 0) {
                return true;
            }
            return recoverBareAlternatives();
        }

        /**
         * Alternativas que o OCR leu como linhas de tabela ("A texto", "B texto", ... sem parenteses): so
         * valem como alternativas se as cinco letras aparecerem em ordem, em linhas consecutivas.
         */
        private boolean recoverBareAlternatives() {
            List<Integer> lineIndexes = new ArrayList<>();
            for (int i = 0; i < head.size(); i++) {
                if (!head.get(i).isEmpty()) {
                    lineIndexes.add(i);
                }
            }
            for (int k = lineIndexes.size() - 5; k >= 0; k--) {
                boolean sequence = true;
                for (int j = 0; j < 5 && sequence; j++) {
                    Matcher m = BARE_ROW.matcher(RichText.plain(head.get(lineIndexes.get(k + j))));
                    sequence = m.matches() && m.group(1).charAt(0) == (char) ('A' + j);
                }
                if (!sequence) {
                    continue;
                }
                for (int j = 0; j < 5; j++) {
                    String rich = head.get(lineIndexes.get(k + j));
                    Matcher m = BARE_ROW.matcher(RichText.plain(rich));
                    m.matches();
                    altText.get(j).add(RichText.trim(RichText.fromVisible(rich, m.start(2))));
                }
                int first = lineIndexes.get(k);
                int last = lineIndexes.get(k + 4);
                trailing.addAll(head.subList(last + 1, head.size()));
                head.subList(first, head.size()).clear();
                altIndex = 4;
                recoveredBare = true;
                return true;
            }
            return false;
        }

        boolean endsAfterAlternativeE() {
            return altIndex == 4 && blankAfterLastAlt && !inTrailing;
        }

        long altCount() {
            return altText.stream().filter(l -> !l.isEmpty()).count();
        }

        String alt(int index) {
            List<String> lines = altText.get(index);
            if (lines.isEmpty()) {
                return markerSeen[index] ? EMPTY_ALTERNATIVE : null;
            }
            return joinLines(lines);
        }

        void addHead(String line) {
            head.add(line);
        }

        void addBlank() {
            if (inTrailing || number == 0) {
                trailing.add("");
            } else if (altIndex < 0) {
                head.add("");
            } else {
                blankAfterLastAlt = true;
            }
        }

        /** @param line texto puro (para decidir) @param rich o mesmo texto com marcas de estilo (para guardar). */
        void addLine(String line, String rich, boolean afterBlank) {
            if (number == 0) {
                trailing.add(rich);
                return;
            }
            if (inTrailing) {
                trailing.add(rich);
                return;
            }
            Marker marker = altIndex + 1 < 5 || altIndex < 0 ? matchMarker(line) : null;
            if (marker != null) {
                altIndex = marker.index();
                markerSeen[altIndex] = true;
                blankAfterLastAlt = false;
                if (!marker.body().isEmpty()) {
                    altText.get(marker.index()).add(RichText.trim(RichText.fromVisible(rich, marker.bodyStart())));
                }
                if (marker.parenthesized()) {
                    parenSeen = true;
                }
                return;
            }
            if (altIndex < 0) {
                head.add(rich);
                return;
            }
            // Depois de uma linha em branco, so continua a alternativa se o texto seguir minusculo
            // (quebra de pagina no meio da alternativa); senao e titulo/texto de apoio da proxima questao.
            if (altIndex == 4 && blankAfterLastAlt && !Character.isLowerCase(line.charAt(0))) {
                inTrailing = true;
                trailing.add(rich);
                return;
            }
            altText.get(altIndex).add(rich);
            blankAfterLastAlt = false;
        }

        private record Marker(int index, String body, int bodyStart, boolean parenthesized) {
        }

        /** Marcador de alternativa no inicio da linha. Prefere a proxima letra esperada; aceita pular letras. */
        private Marker matchMarker(String line) {
            int expected = altIndex + 1;
            Matcher m = ALTERNATIVE_MARKER.matcher(line);
            boolean closed = m.matches();
            if (!closed) {
                m = OPEN_ONLY_MARKER.matcher(line);
                if (!m.matches()) {
                    return bareMarker(line, expected);
                }
            }
            String token = m.group(1).toUpperCase();
            if (token.length() == 2 && (token.charAt(0) == token.charAt(1) || "JLI|1".indexOf(token.charAt(1)) >= 0)) {
                // "(BB)" ou "(Dj" (parentese de fechamento lido como letra/barra)
                token = token.substring(0, 1);
            }
            if (token.length() == 2 && expected < 5) {
                // "(EC)" ou "(84)": duas leituras de um mesmo marcador; vale se alguma bate com a esperada.
                String candidates = confusableLetters((char) ('A' + expected));
                if (candidates.indexOf(token.charAt(0)) >= 0 || candidates.indexOf(token.charAt(1)) >= 0) {
                    token = token.substring(0, 1).equals(String.valueOf(token.charAt(0)))
                            && candidates.indexOf(token.charAt(0)) >= 0 ? token.substring(0, 1) : token.substring(1);
                }
            }
            if (token.length() == 1 && expected < 5
                    && confusableLetters((char) ('A' + expected)).indexOf(token.charAt(0)) >= 0) {
                return new Marker(expected, m.group(2).trim(), m.start(2), true);
            }
            Matcher strict = STRICT_MARKER.matcher(line);
            if (strict.matches()) {
                int letter = strict.group(1).charAt(0) - 'A';
                if (letter > expected) {
                    return new Marker(letter, strict.group(2).trim(), strict.start(2), true);
                }
            }
            return bareMarker(line, expected);
        }

        private Marker bareMarker(String line, int expected) {
            if (!parenSeen || expected >= 5) {
                return null;
            }
            Matcher m = BARE_MARKER.matcher(line);
            if (m.matches() && m.group(1).charAt(0) == (char) ('A' + expected)
                    && !Character.isLowerCase(m.group(2).charAt(0))) {
                return new Marker(expected, m.group(2).trim(), m.start(2), false);
            }
            return null;
        }
    }
}
