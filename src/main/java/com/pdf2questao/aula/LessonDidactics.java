package com.pdf2questao.aula;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Didatica da aula: o aluno nao e especialista e nao deve precisar sair do sistema para entender. Confere, antes de
 * gravar, o que da para conferir sem ler o texto: (1) todo item do topico do edital esta ligado a um slide que o
 * ensina; (2) todo slide de conteudo explica em linguagem simples (caixa ":::simples") e tem texto corrido, nao so
 * topicos e tabela; (3) toda sigla e explicada por extenso ou no glossario. O resto (ordem, analogias, passo a passo)
 * esta nas instrucoes de criar_aula.
 */
public final class LessonDidactics {

    /** Item do topico do edital e os conceitos ({#id}) dos slides que o ensinam. */
    public record EditalItem(String item, List<String> conceitos) {
    }

    /** Termo tecnico ou sigla e o que significa, em linguagem simples. */
    public record Term(String termo, String significado) {
    }

    /** Texto corrido minimo (fora tabelas, codigo e marcacao) de um slide de conteudo. */
    public static final int MIN_PROSE = 900;
    public static final String ROADMAP = "O que o edital cobra";
    public static final String GLOSSARY = "Glossário";
    public static final String SOURCES = "Fontes";

    private static final Pattern RECAP = Pattern.compile("^(resumo|quadro|comparativo|compara[çc][ãa]o|mapa|revis[ãa]o)\\b.*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SIMPLE_BOX = Pattern.compile("(?im)^\\s*:::\\s*simples\\b");
    private static final Pattern ACRONYM = Pattern.compile("(?<![\\p{L}\\p{N}_])\\p{Lu}{2,6}(?![\\p{L}\\p{N}_$])");
    private static final Pattern ROMAN = Pattern.compile("[IVX]+");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`]*`");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    /** Siglas que nao sao materia (documento, formato, banca, UF) e palavras comuns escritas em maiusculas. */
    private static final Set<String> KNOWN = Set.of("CPF", "CNPJ", "PDF", "CSV", "JSON", "HTML", "URL", "OK", "FCC",
            "FGV", "SEFAZ", "AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG", "PA", "PB",
            "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO", "OU", "DE", "DA", "DO", "EM", "UM",
            "NO", "NA", "AO", "OS", "AS", "JÁ", "CF", "TI", "ICMS", "IPTU", "IPVA", "ISS", "EXCETO", "APENAS",
            "SEMPRE", "NUNCA", "TODO", "TODOS", "ANTES", "DEPOIS", "CERTO", "ERRADO", "SÓ", "NÃO", "SIM", "MAS", "NEM", "QUE", "COM", "SEM", "POR");
    private static final Set<String> STOP = Set.of("para", "como", "entre", "sobre", "seus", "suas", "pela", "pelo",
            "pelas", "pelos", "com", "das", "dos", "nas", "nos", "aos", "uma", "que", "sua", "seu", "art", "arts",
            "lei", "demais", "outros", "outras", "bem", "inclusive", "relativos", "relativas", "aplicado", "aplicada");

    private LessonDidactics() {
    }

    /** Slides montados pelo sistema (roteiro do edital, glossario, fontes): nao ensinam conceito. */
    public static boolean auxiliary(String title) {
        return title != null && (title.equalsIgnoreCase(SOURCES) || title.equalsIgnoreCase(GLOSSARY)
                || title.equalsIgnoreCase(ROADMAP));
    }

    /** Slide de fechamento (resumo, quadro comparativo): junta o que ja foi explicado, nao precisa explicar de novo. */
    static boolean recap(String title) {
        return title != null && RECAP.matcher(title.strip()).matches();
    }

    /** Recusa a aula com todos os problemas de uma vez (para corrigir numa reescrita so). */
    public static void check(String content, String editalTopic, List<EditalItem> coverage, List<Term> glossary) {
        List<String> problems = new ArrayList<>();
        problems.addAll(coverageProblems(content, editalTopic, coverage));
        problems.addAll(slideProblems(content));
        problems.addAll(acronymProblems(content, glossary));
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("A aula ainda não está didática o bastante para quem não é da área "
                    + "(o aluno não pode precisar de outra fonte para entender):\n- " + String.join("\n- ", problems));
        }
    }

    // ---- (1) edital

    static List<String> coverageProblems(String content, String editalTopic, List<EditalItem> coverage) {
        List<String> out = new ArrayList<>();
        if (coverage == null || coverage.isEmpty()) {
            out.add("Informe 'coberturaEdital': quebre o tópico do edital em seus itens (cada assunto separado por "
                    + "vírgula, ponto e vírgula ou \"e\") e diga, para cada um, o(s) conceito(s) {#id} do slide que o "
                    + "ensina. Item do edital sem slide = aula incompleta.");
            return out;
        }
        Set<String> concepts = LessonSlides.concepts(content).keySet();
        StringBuilder items = new StringBuilder();
        for (EditalItem it : coverage) {
            if (it == null || it.item() == null || it.item().isBlank()) {
                out.add("coberturaEdital: há um item sem o texto do edital.");
                continue;
            }
            items.append(' ').append(it.item());
            List<String> ids = it.conceitos() == null ? List.of() : it.conceitos();
            if (ids.isEmpty()) {
                out.add("coberturaEdital: o item \"" + it.item() + "\" não aponta para nenhum conceito; escreva o "
                        + "slide que o ensina e informe o {#id}.");
            }
            List<String> unknown = ids.stream().filter(id -> !concepts.contains(id)).toList();
            if (!unknown.isEmpty()) {
                out.add("coberturaEdital: o item \"" + it.item() + "\" aponta para " + unknown + ", que não é conceito "
                        + "de nenhum slide (conceitos da aula: " + concepts + ").");
            }
        }
        String covered = normalize(items.toString());
        List<String> missing = words(editalTopic).stream().filter(w -> !covered.contains(w)).toList();
        if (!missing.isEmpty()) {
            out.add("O tópico do edital fala em " + missing + ", que não aparece em nenhum item de coberturaEdital: "
                    + "a aula precisa ensinar tudo o que o tópico cobra (copie cada item com as palavras do edital e "
                    + "ligue ao slide que o explica; se faltar slide, escreva).");
        }
        return out;
    }

    /** Palavras do topico do edital que identificam um assunto (4+ letras, fora preposicoes e afins). */
    static List<String> words(String editalTopic) {
        if (editalTopic == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String w : normalize(editalTopic).split("[^a-z0-9]+")) {
            if (w.length() >= 4 && !STOP.contains(w) && !w.chars().allMatch(Character::isDigit)) {
                out.add(w);
            }
        }
        return List.copyOf(out);
    }

    static String normalize(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    // ---- (2) explicacao em cada slide

    static List<String> slideProblems(String content) {
        List<String> out = new ArrayList<>();
        for (LessonSlides.Slide s : LessonSlides.parse(content)) {
            if (auxiliary(s.title()) || recap(s.title()) || s.concept() == null) {
                continue;
            }
            if (!SIMPLE_BOX.matcher(s.source()).find()) {
                out.add("Slide \"" + s.title() + "\": falta a caixa \":::simples\" (fechada com \":::\"), que explica "
                        + "o conceito como se fosse a um colega de outra área: que problema ele resolve, uma analogia "
                        + "ou situação do dia a dia e o que muda na prática, sem jargão.");
            }
            int prose = prose(s.source());
            if (prose < MIN_PROSE) {
                out.add("Slide \"" + s.title() + "\": só " + prose + " caracteres de explicação (mínimo " + MIN_PROSE
                        + ", fora tabelas e código). Explique em texto corrido: o que é, por que existe, como funciona "
                        + "passo a passo e como se lê o exemplo; tópicos soltos e tabela não ensinam sozinhos. Se o "
                        + "assunto ficar grande, divida em dois slides em vez de resumir.");
            }
        }
        return out;
    }

    /** Caracteres de texto corrido do slide: fora linhas de tabela, blocos de codigo e marcacao. */
    static int prose(String source) {
        int n = 0;
        boolean code = false;
        for (String line : source.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("```")) {
                code = !code;
                continue;
            }
            if (code || t.startsWith("|") || t.startsWith(":::") || t.equals("+++")) {
                continue;
            }
            n += t.replaceAll("[*_`#>]", "").strip().length();
        }
        return n;
    }

    // ---- (3) siglas

    static List<String> acronymProblems(String content, List<Term> glossary) {
        StringBuilder text = new StringBuilder();
        StringBuilder codeText = new StringBuilder();
        boolean code = false;
        boolean data = false;
        for (String line : content.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("```")) {
                code = !code;
                continue;
            }
            if (!code && t.matches("(?i)^:::\\s*dados\\b.*")) {
                data = true;
                continue;
            }
            if (data) {
                data = !t.equals(":::");
                continue;
            }
            if (code) {
                codeText.append(line).append('\n');
                continue;
            }
            Matcher inline = INLINE_CODE.matcher(line);
            while (inline.find()) {
                codeText.append(inline.group()).append('\n');
            }
            text.append(URL.matcher(inline.replaceAll(" ")).replaceAll(" ").replaceAll("\\{#[^}]*}", " ")
                    .replace("*", "").replace("_", " ")).append('\n');
        }
        String plain = text.toString();
        String codeWords = codeText.toString();
        Set<String> found = new LinkedHashSet<>();
        Matcher m = ACRONYM.matcher(plain);
        while (m.find()) {
            found.add(m.group());
        }
        List<String> unexplained = new ArrayList<>();
        for (String a : found) {
            if (KNOWN.contains(a) || ROMAN.matcher(a).matches() || inGlossary(a, glossary)
                    || word(codeWords, a) || isCommonWord(plain, a) || explained(plain, a)) {
                continue;
            }
            unexplained.add(a);
        }
        if (unexplained.isEmpty()) {
            return List.of();
        }
        return List.of("Siglas usadas sem explicação: " + unexplained + ". Na primeira vez que cada uma aparece, "
                + "escreva o nome por extenso e o que é, ex.: \"variação patrimonial aumentativa (VPA): o que aumenta "
                + "o patrimônio líquido\"; ou inclua em 'glossario' ({termo, significado}).");
    }

    private static boolean inGlossary(String acronym, List<Term> glossary) {
        return glossary != null && glossary.stream().anyMatch(t -> t != null && t.termo() != null
                && word(t.termo().toUpperCase(Locale.ROOT), acronym));
    }

    private static boolean word(String text, String w) {
        return Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(w) + "(?![\\p{L}\\p{N}_])").matcher(text).find();
    }

    /** Palavra comum em maiusculas para dar enfase (ANTES, TODO): tambem aparece no texto em minusculas. */
    private static boolean isCommonWord(String plain, String a) {
        String lower = a.toLowerCase(Locale.ROOT);
        return word(plain, lower) || word(plain, a.charAt(0) + lower.substring(1));
    }

    /** "nome por extenso (SIGLA)", "SIGLA (nome...)", "SIGLA: ...", "SIGLA é/significa ...". */
    private static boolean explained(String plain, String a) {
        String q = Pattern.quote(a);
        return Pattern.compile("\\p{L}{3,}[^()\\n]{0,80}\\(\\s*" + q + "\\s*[),;:]").matcher(plain).find()
                || Pattern.compile("(?<![\\p{L}\\p{N}_])" + q + "\\s*(\\(\\s*\\p{L}{3,}|[:=—–]\\s*\\p{L}|,?\\s+(é|são|significa|quer dizer)\\s)")
                .matcher(plain).find();
    }

    // ---- slides montados pelo sistema

    /** Slide de abertura: cada item do edital e o slide que o ensina. */
    public static String roadmapSlide(String content, String editalTopic, List<EditalItem> coverage) {
        if (coverage == null || coverage.isEmpty()) {
            return "";
        }
        Map<String, LessonSlides.Concept> concepts = LessonSlides.concepts(content);
        StringBuilder sb = new StringBuilder("=== ").append(ROADMAP).append('\n');
        if (editalTopic != null && !editalTopic.isBlank()) {
            sb.append("**Tópico do edital:** ").append(editalTopic.strip()).append("\n\n");
        }
        sb.append("Esta aula ensina cada item abaixo, na ordem dos slides:\n\n| O edital cobra | Onde está na aula |\n");
        for (EditalItem it : coverage) {
            Set<String> where = new LinkedHashSet<>();
            for (String id : it.conceitos()) {
                LessonSlides.Concept c = concepts.get(id);
                if (c != null) {
                    // O roteiro vira o slide 1: os slides de conteudo andam um para a frente.
                    where.add("slide " + (c.slide() + 1) + ": " + c.slideTitle());
                }
            }
            sb.append("| ").append(cell(it.item())).append(" | ").append(cell(String.join("; ", where)))
                    .append(" |\n");
        }
        return sb.append('\n').toString();
    }

    /** Slide de glossario (antes das fontes); vazio se nao ha termos. */
    public static String glossarySlide(List<Term> glossary) {
        if (glossary == null || glossary.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n=== ").append(GLOSSARY).append("\n| Termo | O que é |\n");
        for (Term t : glossary) {
            if (t != null && t.termo() != null && !t.termo().isBlank() && t.significado() != null) {
                sb.append("| **").append(cell(t.termo())).append("** | ").append(cell(t.significado())).append(" |\n");
            }
        }
        return sb.toString();
    }

    private static String cell(String s) {
        return s.strip().replace("|", "/").replaceAll("\\R+", " ");
    }
}
