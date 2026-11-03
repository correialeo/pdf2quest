package com.pdf2questao.aula;

import com.pdf2questao.web.SimpleMarkdown;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aula em slides: cada linha "=== Titulo" abre um slide (uma pagina por assunto, ex.: DFC numa, DMPL noutra) e cada
 * linha "[[questao N]]" poe ali a questao N de fixacao da aula. Aula sem "===" continua sendo uma pagina so.
 * Caixas: ":::regra Titulo", ":::pegadinha", ":::exemplo", ":::dica" ate ":::" viram cartoes coloridos; dentro de
 * ":::lado" (ou de qualquer caixa), "+++" separa colunas lado a lado (ex.: tabela de entrada | resultado).
 * Conceitos: "=== Titulo {#id}" e ":::regra Titulo {#id}" declaram o conceito ensinado ali; as perguntas de quiz da
 * aula dizem que conceito cobram, e o sistema confere que ele existe (o quiz nao cobra o que a aula nao ensinou).
 */
public final class LessonSlides {

    static final Pattern SLIDE = Pattern.compile("^===\\s*(.+?)\\s*=*\\s*$");
    static final Pattern BOX = Pattern.compile("^:::\\s*(regra|pegadinha|exemplo|dica|lado)\\b\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    static final java.util.Map<String, String> BOX_TITLES = java.util.Map.of("regra", "Regra", "pegadinha",
            "Pegadinha da banca", "exemplo", "Exemplo", "dica", "Dica", "lado", "");
    /** Identificador de conceito no fim de um titulo: "{#rp-desbloqueio}". */
    static final Pattern CONCEPT_ID = Pattern.compile("\\s*\\{#([a-z0-9][a-z0-9-]*)}\\s*$");
    static final Pattern QUESTION = Pattern.compile("^\\[\\[\\s*quest[aã]o\\s+(\\d+)\\s*]]$", Pattern.CASE_INSENSITIVE);

    /**
     * Trecho de um slide: Markdown ja convertido, a posicao (1 = primeira) de uma questao de fixacao, ou um bloco
     * interativo (codigo, tarefa, exercicio guiado, formula), ja com o HTML dele.
     */
    public record Part(String html, Integer question, LessonBlocks.Block block) {
        public Part(String html, Integer question) {
            this(html, question, null);
        }
    }

    /**
     * @param concept conceito do slide ({#id} no titulo; nulo = nao declarado)
     * @param source  Markdown cru do slide (para comparar os dados do exemplo com os da fixacao)
     * @param boxConcepts conceitos das caixas (e dos blocos de codigo/formula) do slide, na ordem
     */
    public record Slide(String title, List<Part> parts, String concept, String source, List<String> boxConcepts) {
    }

    /** Conceito ensinado: em que slide (1 = primeiro) esta e com que titulo. */
    public record Concept(String id, int slide, String slideTitle, String title, String source) {
    }

    /**
     * Aula inteira: slides, blocos interativos (na ordem), tabelas :::dados (nome -> CSV) e problemas de marcacao
     * achados no caminho (ex.: ```saida sem um bloco run antes).
     */
    public record Parsed(List<Slide> slides, List<LessonBlocks.Block> blocks, java.util.Map<String, String> datasets,
                         List<String> problems) {
    }

    private LessonSlides() {
    }

    public static boolean isSlides(String content) {
        return content != null && content.lines().anyMatch(l -> SLIDE.matcher(l.strip()).matches());
    }

    public static List<Slide> parse(String content) {
        return parseAll(content).slides();
    }

    public static Parsed parseAll(String content) {
        List<Slide> slides = new ArrayList<>();
        List<LessonBlocks.Block> blocks = new ArrayList<>();
        java.util.Map<String, String> datasets = new java.util.LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        if (!isSlides(content)) {
            return new Parsed(slides, blocks, datasets, problems);
        }
        String title = null;
        String concept = null;
        List<Part> parts = new ArrayList<>();
        List<String> boxConcepts = new ArrayList<>();
        StringBuilder source = new StringBuilder();
        StringBuilder text = new StringBuilder();
        boolean code = false;
        String box = null;   // caixa aberta (:::tipo) e seu titulo
        String boxTitle = null;
        String boxId = null;
        List<StringBuilder> columns = new ArrayList<>();
        LessonBlocks.Kind special = null;   // bloco interativo aberto (```python run, ```calculo...)
        boolean saida = false;              // ```saida aberto
        String specialLang = null;
        String specialId = null;
        StringBuilder specialBody = new StringBuilder();
        String dataset = null;              // :::dados aberto
        for (String line : content.split("\\R")) {
            String t = line.strip();
            // ---- dentro de um bloco interativo ou de uma tabela de dados
            if (special != null || saida) {
                if (t.startsWith("```")) {
                    if (saida) {
                        LessonBlocks.Block last = blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
                        if (last != null && last.kind() == LessonBlocks.Kind.RUN && last.expected() == null
                                && last.slide() == slides.size() + 1) {
                            last.expected(specialBody.toString().stripTrailing());
                        } else {
                            problems.add("Slide " + (slides.size() + 1) + ": ```saida precisa vir logo depois de um "
                                    + "bloco ```python run / ```sql run / ```pyspark run.");
                        }
                        saida = false;
                    } else {
                        LessonBlocks.Block b = new LessonBlocks.Block(special, blocks.size(), specialLang,
                                specialBody.toString().stripTrailing(), specialId, slides.size() + 1);
                        blocks.add(b);
                        flush(text, parts);
                        parts.add(new Part(null, null, b));
                        if (specialId != null && (special == LessonBlocks.Kind.RUN || special == LessonBlocks.Kind.FORMULA)) {
                            boxConcepts.add(specialId);
                        }
                        special = null;
                    }
                    specialBody.setLength(0);
                } else {
                    specialBody.append(line).append('\n');
                    if (special == LessonBlocks.Kind.RUN || special == LessonBlocks.Kind.TAREFA) {
                        source.append(line).append('\n');
                    }
                }
                continue;
            }
            if (dataset != null) {
                if (t.equals(":::")) {
                    datasets.put(dataset, specialBody.toString().strip());
                    parts.add(new Part(LessonBlocks.datasetHtml(dataset, specialBody.toString()), null));
                    specialBody.setLength(0);
                    dataset = null;
                } else {
                    specialBody.append(line).append('\n');
                    source.append(line).append('\n');
                }
                continue;
            }
            if (!code && box == null) {
                Matcher open = null;
                LessonBlocks.Kind kind = null;
                for (var k : java.util.Map.of(LessonBlocks.Kind.RUN, LessonBlocks.RUN, LessonBlocks.Kind.TAREFA,
                        LessonBlocks.TAREFA, LessonBlocks.Kind.CALCULO, LessonBlocks.CALCULO, LessonBlocks.Kind.FORMULA,
                        LessonBlocks.FORMULA).entrySet()) {
                    Matcher m = k.getValue().matcher(t);
                    if (m.matches()) {
                        open = m;
                        kind = k.getKey();
                    }
                }
                if (open != null) {
                    special = kind;
                    boolean hasLang = kind == LessonBlocks.Kind.RUN || kind == LessonBlocks.Kind.TAREFA;
                    specialLang = hasLang ? open.group(1) : null;
                    specialId = open.group(hasLang ? 2 : 1);
                    specialBody.setLength(0);
                    continue;
                }
                if (LessonBlocks.SAIDA.matcher(t).matches()) {
                    saida = true;
                    specialBody.setLength(0);
                    continue;
                }
                Matcher d = LessonBlocks.DADOS.matcher(t);
                if (d.matches()) {
                    flush(text, parts);
                    dataset = d.group(1);
                    if (datasets.containsKey(dataset)) {
                        problems.add("A tabela :::dados " + dataset + " aparece duas vezes.");
                    }
                    specialBody.setLength(0);
                    continue;
                }
            }
            if (code || !SLIDE.matcher(t).matches() && !QUESTION.matcher(t).matches()) {
                source.append(line).append('\n');
            }
            code ^= t.startsWith("```");
            if (!code && box == null) {
                Matcher b = BOX.matcher(t);
                if (b.matches()) {
                    flush(text, parts);
                    box = b.group(1).toLowerCase(java.util.Locale.ROOT);
                    String[] titled = splitId(b.group(2));
                    if (titled[1] != null) {
                        boxConcepts.add(titled[1]);
                    }
                    boxTitle = titled[0].isBlank() ? BOX_TITLES.get(box) : titled[0];
                    boxId = titled[1];
                    columns = new ArrayList<>(List.of(new StringBuilder()));
                    continue;
                }
            } else if (!code && box != null) {
                if (t.equals(":::")) {
                    parts.add(new Part(boxHtml(box, boxTitle, boxId, columns), null));
                    box = null;
                } else if (t.equals("+++")) {
                    columns.add(new StringBuilder());
                } else {
                    columns.get(columns.size() - 1).append(line).append('\n');
                }
                continue;
            } else if (box != null) {
                columns.get(columns.size() - 1).append(line).append('\n');
                continue;
            }
            Matcher s = code ? null : SLIDE.matcher(t);
            Matcher q = code ? null : QUESTION.matcher(t);
            if (s != null && s.matches()) {
                flush(text, parts);
                if (title != null || !parts.isEmpty()) {
                    slides.add(new Slide(title == null ? "Início" : title, render(parts), concept, source.toString(),
                            boxConcepts));
                }
                String[] titled = splitId(s.group(1));
                title = titled[0];
                concept = titled[1];
                parts = new ArrayList<>();
                boxConcepts = new ArrayList<>();
                source = new StringBuilder();
            } else if (q != null && q.matches()) {
                flush(text, parts);
                parts.add(new Part(null, Integer.parseInt(q.group(1))));
            } else {
                text.append(line).append('\n');
            }
        }
        if (box != null) {
            parts.add(new Part(boxHtml(box, boxTitle, boxId, columns), null));   // caixa sem ":::" de fechamento
        }
        if (special != null || saida) {
            problems.add("Um bloco ``` " + (saida ? "saida" : special.name().toLowerCase(java.util.Locale.ROOT))
                    + " não foi fechado com ```.");
        }
        if (dataset != null) {
            problems.add("A tabela :::dados " + dataset + " não foi fechada com :::.");
        }
        flush(text, parts);
        slides.add(new Slide(title == null ? "Início" : title, render(parts), concept, source.toString(), boxConcepts));
        return new Parsed(slides, blocks, datasets, problems);
    }

    /** HTML dos blocos interativos (o de codigo so agora, depois do ```saida que vem logo apos ele). */
    private static List<Part> render(List<Part> parts) {
        List<Part> out = new ArrayList<>();
        for (Part p : parts) {
            if (p.block() == null) {
                out.add(p);
                continue;
            }
            LessonBlocks.Block b = p.block();
            String html;
            try {
                html = switch (b.kind()) {
                    case RUN -> LessonBlocks.runHtml(b);
                    case TAREFA -> LessonBlocks.taskHtml(b);
                    case CALCULO -> LessonBlocks.calcHtml(b.body(), b.index());
                    case FORMULA -> LessonBlocks.formulaHtml(LessonBlocks.formula(b.body()), b.concept());
                };
            } catch (IllegalArgumentException e) {
                html = "<div class=\"alert alert-warning\">" + LessonBlocks.esc(e.getMessage()) + "</div>";
            }
            out.add(new Part(html, null, b));
        }
        return out;
    }

    /** Titulo sem o "{#id}" e o id (nulo se nao houver). */
    static String[] splitId(String title) {
        Matcher m = CONCEPT_ID.matcher(title);
        if (m.find()) {
            return new String[]{title.substring(0, m.start()).strip(), m.group(1)};
        }
        return new String[]{title.strip(), null};
    }

    /**
     * Conceitos declarados na aula, na ordem (id do slide e das caixas). Id repetido e recusado: a pergunta precisa
     * apontar para um lugar so.
     */
    public static java.util.Map<String, Concept> concepts(String content) {
        java.util.Map<String, Concept> out = new java.util.LinkedHashMap<>();
        List<Slide> slides = parse(content);
        for (int i = 0; i < slides.size(); i++) {
            Slide sl = slides.get(i);
            List<String> ids = new ArrayList<>();
            if (sl.concept() != null) {
                ids.add(sl.concept());
            }
            ids.addAll(sl.boxConcepts());
            for (String id : ids) {
                if (out.containsKey(id)) {
                    throw new IllegalArgumentException("O conceito {#" + id + "} aparece em dois lugares (slides "
                            + out.get(id).slide() + " e " + (i + 1) + "): cada conceito tem um id só.");
                }
                out.put(id, new Concept(id, i + 1, sl.title(), sl.title(), sl.source()));
            }
        }
        return out;
    }

    /** Slide (1 = primeiro) em que cada [[questao N]] aparece. */
    public static java.util.Map<Integer, Integer> questionSlides(String content) {
        java.util.Map<Integer, Integer> out = new java.util.LinkedHashMap<>();
        List<Slide> slides = parse(content);
        for (int i = 0; i < slides.size(); i++) {
            for (Part p : slides.get(i).parts()) {
                if (p.question() != null) {
                    out.putIfAbsent(p.question(), i + 1);
                }
            }
        }
        return out;
    }

    /** Posicoes das questoes citadas, na ordem em que aparecem. */
    public static List<Integer> questionRefs(String content) {
        return parse(content).stream().flatMap(s -> s.parts().stream()).map(Part::question)
                .filter(java.util.Objects::nonNull).toList();
    }

    private static String boxHtml(String type, String title, String id, List<StringBuilder> columns) {
        StringBuilder html = new StringBuilder("<div class=\"lesson-box lesson-box-").append(type).append("\"")
                .append(id == null ? "" : " id=\"c-" + id + "\"").append(">");
        if (title != null && !title.isBlank()) {
            html.append("<div class=\"lesson-box-title\">").append(SimpleMarkdown.inlineHtml(title)).append("</div>");
        }
        // Texto sem tabela nem codigo antes do primeiro "+++" e a introducao da caixa: largura toda, acima das colunas.
        if (columns.size() > 1 && !hasBlock(columns.get(0))
                && columns.subList(1, columns.size()).stream().anyMatch(LessonSlides::hasBlock)) {
            html.append("<div class=\"lesson-box-intro\">").append(SimpleMarkdown.toHtml(columns.get(0).toString()))
                    .append("</div>");
            columns = columns.subList(1, columns.size());
        }
        html.append(columns.size() > 1 ? "<div class=\"lesson-cols\">" : "");
        for (StringBuilder c : columns) {
            html.append(columns.size() > 1 ? "<div class=\"lesson-col\">" : "").append(SimpleMarkdown.toHtml(c.toString()))
                    .append(columns.size() > 1 ? "</div>" : "");
        }
        return html.append(columns.size() > 1 ? "</div>" : "").append("</div>").toString();
    }

    /** Coluna com tabela ou bloco de codigo (o que justifica ficar lado a lado). */
    private static boolean hasBlock(StringBuilder column) {
        return column.toString().lines().anyMatch(l -> l.strip().startsWith("|") || l.strip().startsWith("```"));
    }

    private static void flush(StringBuilder text, List<Part> parts) {
        if (!text.toString().isBlank()) {
            parts.add(new Part(SimpleMarkdown.toHtml(text.toString()), null));
        }
        text.setLength(0);
    }
}
