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
 */
public final class LessonSlides {

    static final Pattern SLIDE = Pattern.compile("^===\\s*(.+?)\\s*=*\\s*$");
    static final Pattern BOX = Pattern.compile("^:::\\s*(regra|pegadinha|exemplo|dica|lado)\\b\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    static final java.util.Map<String, String> BOX_TITLES = java.util.Map.of("regra", "Regra", "pegadinha",
            "Pegadinha da banca", "exemplo", "Exemplo", "dica", "Dica", "lado", "");
    static final Pattern QUESTION = Pattern.compile("^\\[\\[\\s*quest[aã]o\\s+(\\d+)\\s*]]$", Pattern.CASE_INSENSITIVE);

    /** Trecho de um slide: Markdown ja convertido ou a posicao (1 = primeira) de uma questao de fixacao. */
    public record Part(String html, Integer question) {
    }

    public record Slide(String title, List<Part> parts) {
    }

    private LessonSlides() {
    }

    public static boolean isSlides(String content) {
        return content != null && content.lines().anyMatch(l -> SLIDE.matcher(l.strip()).matches());
    }

    public static List<Slide> parse(String content) {
        List<Slide> slides = new ArrayList<>();
        if (!isSlides(content)) {
            return slides;
        }
        String title = null;
        List<Part> parts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        boolean code = false;
        String box = null;   // caixa aberta (:::tipo) e seu titulo
        String boxTitle = null;
        List<StringBuilder> columns = new ArrayList<>();
        for (String line : content.split("\\R")) {
            String t = line.strip();
            code ^= t.startsWith("```");
            if (!code && box == null) {
                Matcher b = BOX.matcher(t);
                if (b.matches()) {
                    flush(text, parts);
                    box = b.group(1).toLowerCase(java.util.Locale.ROOT);
                    boxTitle = b.group(2).isBlank() ? BOX_TITLES.get(box) : b.group(2).strip();
                    columns = new ArrayList<>(List.of(new StringBuilder()));
                    continue;
                }
            } else if (!code && box != null) {
                if (t.equals(":::")) {
                    parts.add(new Part(boxHtml(box, boxTitle, columns), null));
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
                    slides.add(new Slide(title == null ? "Início" : title, parts));
                }
                title = s.group(1);
                parts = new ArrayList<>();
            } else if (q != null && q.matches()) {
                flush(text, parts);
                parts.add(new Part(null, Integer.parseInt(q.group(1))));
            } else {
                text.append(line).append('\n');
            }
        }
        if (box != null) {
            parts.add(new Part(boxHtml(box, boxTitle, columns), null));   // caixa sem ":::" de fechamento
        }
        flush(text, parts);
        slides.add(new Slide(title == null ? "Início" : title, parts));
        return slides;
    }

    /** Posicoes das questoes citadas, na ordem em que aparecem. */
    public static List<Integer> questionRefs(String content) {
        return parse(content).stream().flatMap(s -> s.parts().stream()).map(Part::question)
                .filter(java.util.Objects::nonNull).toList();
    }

    private static String boxHtml(String type, String title, List<StringBuilder> columns) {
        StringBuilder html = new StringBuilder("<div class=\"lesson-box lesson-box-").append(type).append("\">");
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
