package com.pdf2questao.aula;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdf2questao.web.SimpleMarkdown;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Blocos interativos das aulas em slides (cercas ``` com tipo):
 * <ul>
 *   <li>```formula {#id} : cartao de formula (JSON: titulo, formula em TeX, simbolos, quandoUsar, pegadinha).</li>
 *   <li>```calculo : exercicio guiado parametrizado (JSON: enunciado com {var}, variaveis com faixa, restricoes,
 *       exemplo, etapas, resposta). Os numeros sao sorteados a cada tentativa e nunca repetem os do exemplo.</li>
 *   <li>```python run {#id} / ```sql run / ```pyspark run : exemplo executavel no navegador; um ```saida logo
 *       depois declara a saida esperada (conferida na criacao).</li>
 *   <li>```tarefa python|sql|pyspark : exercicio de codigo (### enunciado, codigo, teste ou esperado, dica,
 *       solucao).</li>
 *   <li>:::dados nome ... ::: : tabela CSV da aula (tabela no SQL, arquivo dados/nome.csv no Python).</li>
 * </ul>
 */
public final class LessonBlocks {

    static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static final String ID = "(?:\\s*\\{#([a-z0-9][a-z0-9-]*)})?";
    static final Pattern RUN = Pattern.compile("^```\\s*(python|pandas|sql|pyspark)\\s+run" + ID + "\\s*$",
            Pattern.CASE_INSENSITIVE);
    static final Pattern SAIDA = Pattern.compile("^```\\s*sa[ií]da\\s*$", Pattern.CASE_INSENSITIVE);
    static final Pattern TAREFA = Pattern.compile("^```\\s*tarefa\\s+(python|pandas|sql|pyspark)" + ID + "\\s*$",
            Pattern.CASE_INSENSITIVE);
    static final Pattern CALCULO = Pattern.compile("^```\\s*c[aá]lculo" + ID + "\\s*$", Pattern.CASE_INSENSITIVE);
    static final Pattern FORMULA = Pattern.compile("^```\\s*f[oó]rmula" + ID + "\\s*$", Pattern.CASE_INSENSITIVE);
    static final Pattern DADOS = Pattern.compile("^:::\\s*dados\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*$", Pattern.CASE_INSENSITIVE);

    public enum Kind { RUN, TAREFA, CALCULO, FORMULA }

    /** Bloco interativo, na ordem da aula ({@code index} 0 = primeiro). */
    public static final class Block {
        private final Kind kind;
        private final int index;
        private final String lang;
        private final String body;
        private final String concept;
        private final int slide;
        private String expected;

        Block(Kind kind, int index, String lang, String body, String concept, int slide) {
            this.kind = kind;
            this.index = index;
            this.lang = lang == null ? null : normalizeLang(lang);
            this.body = body;
            this.concept = concept;
            this.slide = slide;
        }

        public Kind kind() { return kind; }
        public int index() { return index; }
        public String lang() { return lang; }
        public String body() { return body; }
        /** Conceito declarado no proprio bloco ({#id} na cerca). */
        public String concept() { return concept; }
        /** Slide (1 = primeiro) em que o bloco esta. */
        public int slide() { return slide; }
        public String expected() { return expected; }
        void expected(String e) { this.expected = e; }

        /** Bloco que conta como questao de fixacao (resultado vai para o desempenho). */
        public boolean graded() { return kind == Kind.CALCULO || kind == Kind.TAREFA; }

        public String langLabel() {
            return switch (lang == null ? "" : lang) {
                case "sql" -> "SQL";
                case "pyspark" -> "PySpark (simulação)";
                default -> "Python";
            };
        }
    }

    static String normalizeLang(String lang) {
        String l = lang.toLowerCase(Locale.ROOT);
        return l.equals("pandas") ? "python" : l;
    }

    private LessonBlocks() {
    }

    // ------------------------------------------------------------------ tarefa de codigo

    public record Task(String enunciado, String codigo, String teste, String esperado, String dica, String solucao) {
    }

    static final Pattern SECTION = Pattern.compile("^###\\s*(enunciado|c[oó]digo|teste|esperado|dica|solu[cç][aã]o)\\s*$",
            Pattern.CASE_INSENSITIVE);

    public static Task task(String body) {
        Map<String, StringBuilder> parts = new LinkedHashMap<>();
        StringBuilder cur = null;
        for (String line : body.split("\\R", -1)) {
            Matcher m = SECTION.matcher(line.strip());
            if (m.matches()) {
                String key = java.text.Normalizer.normalize(m.group(1).toLowerCase(Locale.ROOT),
                        java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
                cur = new StringBuilder();
                parts.put(key, cur);
            } else if (cur != null) {
                cur.append(line).append('\n');
            }
        }
        java.util.function.Function<String, String> get = k -> parts.containsKey(k) && !parts.get(k).toString().isBlank()
                ? parts.get(k).toString().strip() : null;
        return new Task(get.apply("enunciado"), get.apply("codigo"), get.apply("teste"), get.apply("esperado"),
                get.apply("dica"), get.apply("solucao"));
    }

    // ------------------------------------------------------------------ formula

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Symbol(String simbolo, String significado, String unidade) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Formula(String titulo, String formula, List<Symbol> simbolos, String quandoUsar, String pegadinha) {
    }

    public static Formula formula(String body) {
        try {
            return JSON.readValue(body, Formula.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Bloco ```formula: JSON inválido (" + e.getOriginalMessage() + ").");
        }
    }

    static void validate(Formula f, int slide) {
        String where = "Cartão de fórmula do slide " + slide + ": ";
        List<String> missing = new ArrayList<>();
        if (blank(f.titulo())) missing.add("titulo");
        if (blank(f.formula())) missing.add("formula (TeX)");
        if (f.simbolos() == null || f.simbolos().isEmpty()) missing.add("simbolos");
        if (blank(f.quandoUsar())) missing.add("quandoUsar");
        if (blank(f.pegadinha())) missing.add("pegadinha");
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(where + "falta " + String.join(", ", missing) + ".");
        }
        for (Symbol s : f.simbolos()) {
            if (blank(s.simbolo()) || blank(s.significado()) || blank(s.unidade())) {
                throw new IllegalArgumentException(where + "cada símbolo precisa de simbolo, significado e unidade "
                        + "(use \"adimensional\" quando não houver).");
            }
        }
    }

    static String formulaHtml(Formula f, String concept) {
        StringBuilder h = new StringBuilder("<div class=\"lesson-box lesson-box-formula\"")
                .append(concept == null ? "" : " id=\"c-" + concept + "\"").append(">");
        h.append("<div class=\"lesson-box-title\">").append(SimpleMarkdown.inlineHtml(nz(f.titulo()))).append("</div>");
        h.append("<div class=\"formula-tex katex-display-src\" data-tex=\"").append(attr(nz(f.formula()))).append("\">")
                .append(esc(nz(f.formula()))).append("</div>");
        h.append("<table class=\"lesson-table formula-symbols\"><thead><tr><th>Símbolo</th><th>Significado</th>")
                .append("<th>Unidade</th></tr></thead><tbody>");
        for (Symbol s : f.simbolos() == null ? List.<Symbol>of() : f.simbolos()) {
            h.append("<tr><td><span class=\"katex-inline-src\" data-tex=\"").append(attr(nz(s.simbolo()))).append("\">")
                    .append(esc(nz(s.simbolo()))).append("</span></td><td>").append(SimpleMarkdown.inlineHtml(nz(s.significado())))
                    .append("</td><td>").append(SimpleMarkdown.inlineHtml(nz(s.unidade()))).append("</td></tr>");
        }
        h.append("</tbody></table>");
        h.append("<div class=\"formula-when\"><strong>Quando usar:</strong> ").append(SimpleMarkdown.inlineHtml(nz(f.quandoUsar())))
                .append("</div>");
        h.append("<div class=\"formula-trap\"><strong>Pegadinha:</strong> ").append(SimpleMarkdown.inlineHtml(nz(f.pegadinha())))
                .append("</div>");
        return h.append("</div>").toString();
    }

    // ------------------------------------------------------------------ calculo guiado

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Range(Double min, Double max, Double passo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TypicalError(String resposta, String mensagem) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(String titulo, String pergunta, String resposta, String unidade, Double tolerancia, String dica,
                       List<TypicalError> errosTipicos) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Calc(String titulo, String conceito, String enunciado, Map<String, Range> variaveis,
                       List<String> restricoes, Map<String, Double> exemplo, List<Step> etapas, String resposta,
                       String interpretacao) {
    }

    public static Calc calc(String body) {
        try {
            return JSON.readValue(body, Calc.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Bloco ```calculo: JSON inválido (" + e.getOriginalMessage() + ").");
        }
    }

    /** Tolerancia da etapa: a declarada ou max(0,01; 0,2% do valor). Igual em lesson-interactive.js. */
    public static double tolerance(Step s, double value) {
        return s.tolerancia() != null ? s.tolerancia() : Math.max(0.01, Math.abs(value) * 0.002);
    }

    static final int CALC_RUNS = 40;
    static final int CALC_TRIES = 500;
    static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /**
     * Valida o gerador rodando-o {@value #CALC_RUNS} vezes: as restricoes sao satisfaziveis sem repetir os valores do
     * exemplo, toda etapa da numero finito, a resposta final fecha com a ultima etapa e nenhum erro tipico coincide
     * com a resposta certa. Se nao fechar, a aula e recusada.
     */
    static void validate(Calc c, int slide, Random random) {
        String where = "Exercício guiado do slide " + slide + ": ";
        List<String> missing = new ArrayList<>();
        if (blank(c.enunciado())) missing.add("enunciado");
        if (c.variaveis() == null || c.variaveis().isEmpty()) missing.add("variaveis");
        if (c.exemplo() == null || c.exemplo().isEmpty()) missing.add("exemplo (os valores usados no slide)");
        if (c.etapas() == null || c.etapas().size() < 2) missing.add("etapas (ao menos 2)");
        if (blank(c.resposta())) missing.add("resposta");
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(where + "falta " + String.join(", ", missing) + ".");
        }
        for (var e : c.variaveis().entrySet()) {
            Range r = e.getValue();
            if (r == null || r.min() == null || r.max() == null || r.passo() == null || r.passo() <= 0 || r.min() > r.max()) {
                throw new IllegalArgumentException(where + "a variável \"" + e.getKey() + "\" precisa de min <= max e "
                        + "passo > 0.");
            }
            if (!c.exemplo().containsKey(e.getKey())) {
                throw new IllegalArgumentException(where + "'exemplo' precisa do valor de \"" + e.getKey() + "\" usado "
                        + "no slide (o sorteio nunca o repete).");
            }
            if (steps(r) < 1) {
                throw new IllegalArgumentException(where + "a faixa de \"" + e.getKey() + "\" só tem o valor do "
                        + "exemplo: amplie min/max.");
            }
        }
        Matcher m = PLACEHOLDER.matcher(c.enunciado());
        while (m.find()) {
            if (!c.variaveis().containsKey(m.group(1))) {
                throw new IllegalArgumentException(where + "o enunciado usa {" + m.group(1) + "}, que não está em "
                        + "'variaveis'.");
            }
        }
        for (int i = 0; i < c.etapas().size(); i++) {
            Step s = c.etapas().get(i);
            if (blank(s.pergunta()) || blank(s.resposta()) || blank(s.dica())) {
                throw new IllegalArgumentException(where + "a etapa " + (i + 1) + " precisa de pergunta, resposta "
                        + "(expressão) e dica.");
            }
        }
        Set<String> combos = new HashSet<>();
        int[] typicalHits = new int[c.etapas().stream().mapToInt(s -> s.errosTipicos() == null ? 0 : s.errosTipicos().size()).sum()];
        for (int run = 0; run < CALC_RUNS; run++) {
            Map<String, Double> vars = sample(c, random);
            if (vars == null) {
                throw new IllegalArgumentException(where + "em " + CALC_TRIES + " sorteios nenhuma combinação "
                        + "satisfez as restricoes sem repetir o exemplo. Revise as faixas e as restrições.");
            }
            combos.add(vars.toString());
            Map<String, Double> env = new LinkedHashMap<>(vars);
            int t = 0;
            double last = Double.NaN;
            for (int i = 0; i < c.etapas().size(); i++) {
                Step s = c.etapas().get(i);
                double v = evalOrFail(where + "etapa " + (i + 1) + ": ", s.resposta(), env);
                env.put("e" + (i + 1), v);
                last = v;
                for (TypicalError te : s.errosTipicos() == null ? List.<TypicalError>of() : s.errosTipicos()) {
                    if (blank(te.resposta()) || blank(te.mensagem())) {
                        throw new IllegalArgumentException(where + "erro típico da etapa " + (i + 1) + " precisa de "
                                + "resposta (expressão) e mensagem.");
                    }
                    double w = evalOrFail(where + "erro típico da etapa " + (i + 1) + ": ", te.resposta(), env);
                    if (Math.abs(w - v) <= tolerance(s, v)) {
                        typicalHits[t]++;
                    }
                    t++;
                }
            }
            double answer = evalOrFail(where + "resposta: ", c.resposta(), env);
            Step lastStep = c.etapas().get(c.etapas().size() - 1);
            if (Math.abs(answer - last) > tolerance(lastStep, last)) {
                throw new IllegalArgumentException(where + "a resposta (" + c.resposta() + " = " + fmt(answer)
                        + ") não fecha com a última etapa (" + fmt(last) + ") para " + vars + ". Corrija as etapas "
                        + "ou a função de resposta.");
            }
        }
        if (combos.size() < Math.min(3, CALC_RUNS)) {
            throw new IllegalArgumentException(where + "o gerador quase não varia (" + combos.size() + " combinações em "
                    + CALC_RUNS + " sorteios): amplie as faixas.");
        }
        for (int i = 0; i < typicalHits.length; i++) {
            if (typicalHits[i] > CALC_RUNS / 5) {
                throw new IllegalArgumentException(where + "um erro típico dá o mesmo valor da resposta certa em "
                        + typicalHits[i] + " de " + CALC_RUNS + " sorteios: ele não distingue o erro do acerto.");
            }
        }
    }

    private static long steps(Range r) {
        return (long) Math.floor((r.max() - r.min()) / r.passo() + 1e-9);
    }

    /** Sorteio: min + k*passo, sem repetir nenhum valor do exemplo, satisfazendo as restricoes (nulo = nao achou). */
    static Map<String, Double> sample(Calc c, Random random) {
        for (int tries = 0; tries < CALC_TRIES; tries++) {
            Map<String, Double> vars = new LinkedHashMap<>();
            boolean ok = true;
            for (var e : c.variaveis().entrySet()) {
                Range r = e.getValue();
                double v = CalcExpr.round(r.min() + random.nextInt((int) Math.min(Integer.MAX_VALUE - 1, steps(r) + 1)) * r.passo(), 10);
                Double ex = c.exemplo().get(e.getKey());
                ok &= ex == null || Math.abs(ex - v) > 1e-9;
                vars.put(e.getKey(), v);
            }
            if (!ok) {
                continue;
            }
            for (String rule : c.restricoes() == null ? List.<String>of() : c.restricoes()) {
                ok &= evalOrFail("Restrição \"" + rule + "\": ", rule, vars) != 0;
            }
            if (ok) {
                return vars;
            }
        }
        return null;
    }

    private static double evalOrFail(String where, String expr, Map<String, Double> env) {
        double v;
        try {
            v = CalcExpr.eval(expr, env);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + e.getMessage() + ". Variáveis disponíveis: " + env.keySet()
                    + " (etapas anteriores: e1, e2, ...).");
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new IllegalArgumentException(where + "\"" + expr + "\" não dá número finito para " + env + ".");
        }
        return v;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    static String calcHtml(String body, int index) {
        return "<div class=\"calc-block\" data-block=\"" + index + "\" data-spec=\"" + attr(body) + "\">"
                + "<div class=\"small text-muted\">Exercício guiado (carregando…)</div></div>";
    }

    // ------------------------------------------------------------------ codigo

    static String runHtml(Block b) {
        return "<div class=\"code-run\" data-block=\"" + b.index() + "\" data-lang=\"" + b.lang() + "\"" +
                (b.concept() == null ? "" : " id=\"c-" + b.concept() + "\"") + ">"
                + "<div class=\"code-run-head\"><span class=\"code-lang\">" + esc(b.langLabel()) + "</span>"
                + ("pyspark".equals(b.lang()) ? "<span class=\"badge text-bg-warning ms-2\" title=\"Roda numa simulação "
                + "didática da API em pandas: não é o Spark real\">Simulação — não é Spark real</span>" : "") + "</div>"
                + "<textarea class=\"code-src\" spellcheck=\"false\" data-original=\"" + attr(b.body()) + "\">"
                + esc(b.body()) + "</textarea>"
                + "<div class=\"code-buttons\"></div>"
                + (b.expected() == null ? "" : "<pre class=\"code-expected\" title=\"Saída conferida na criação da aula\">"
                + esc(b.expected()) + "</pre>")
                + "<div class=\"code-output\" hidden></div></div>";
    }

    static String taskHtml(Block b) {
        Task t = task(b.body());
        return "<div class=\"code-task\" data-block=\"" + b.index() + "\" data-lang=\"" + b.lang() + "\""
                + " data-test=\"" + attr(nz(t.teste())) + "\" data-expected=\"" + attr(nz(t.esperado())) + "\""
                + " data-hint=\"" + attr(nz(t.dica())) + "\" data-solution=\"" + attr(nz(t.solucao())) + "\">"
                + "<div class=\"code-run-head\"><span class=\"code-lang\"><i class=\"bi bi-code-square me-1\"></i>Tarefa · "
                + esc(b.langLabel()) + "</span>"
                + ("pyspark".equals(b.lang()) ? "<span class=\"badge text-bg-warning ms-2\">Simulação — não é Spark real</span>" : "")
                + "</div><div class=\"code-task-statement\">" + SimpleMarkdown.toHtml(nz(t.enunciado())) + "</div>"
                + "<textarea class=\"code-src\" spellcheck=\"false\" data-original=\"" + attr(nz(t.codigo())) + "\">"
                + esc(nz(t.codigo())) + "</textarea>"
                + "<div class=\"code-buttons\"></div><div class=\"code-output\" hidden></div></div>";
    }

    // ------------------------------------------------------------------ dados

    /** :::dados nome com CSV: mostrado como tabela. */
    static String datasetHtml(String name, String csv) {
        List<String> lines = csv.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        StringBuilder h = new StringBuilder("<div class=\"lesson-dataset\"><div class=\"small text-muted mb-1\">")
                .append("<i class=\"bi bi-table me-1\"></i>Tabela <code>").append(esc(name)).append("</code> (arquivo ")
                .append("<code>dados/").append(esc(name)).append(".csv</code>)</div><table class=\"lesson-table\">");
        for (int i = 0; i < lines.size(); i++) {
            String tag = i == 0 ? "th" : "td";
            h.append(i == 0 ? "<thead>" : i == 1 ? "<tbody>" : "").append("<tr>");
            for (String cell : splitCsv(lines.get(i))) {
                h.append('<').append(tag).append('>').append(esc(cell)).append("</").append(tag).append('>');
            }
            h.append("</tr>").append(i == 0 ? "</thead>" : "");
        }
        return h.append(lines.size() > 1 ? "</tbody>" : "").append("</table></div>").toString();
    }

    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                out.add(cur.toString().strip());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString().strip());
        return out;
    }

    // ------------------------------------------------------------------ util

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String attr(String s) {
        return esc(s).replace("\"", "&quot;").replace("'", "&#39;").replace("\n", "&#10;");
    }
}
