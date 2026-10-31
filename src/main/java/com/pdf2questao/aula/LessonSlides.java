package com.pdf2questao.aula;

import com.pdf2questao.web.SimpleMarkdown;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aula em slides: cada linha "=== Titulo" abre um slide (uma pagina por assunto, ex.: DFC numa, DMPL noutra) e cada
 * linha "[[questao N]]" poe ali a questao N de fixacao da aula. Aula sem "===" continua sendo uma pagina so.
 */
public final class LessonSlides {

    static final Pattern SLIDE = Pattern.compile("^===\\s*(.+?)\\s*=*\\s*$");
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
        for (String line : content.split("\\R")) {
            String t = line.strip();
            code ^= t.startsWith("```");
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
        flush(text, parts);
        slides.add(new Slide(title == null ? "Início" : title, parts));
        return slides;
    }

    /** Posicoes das questoes citadas, na ordem em que aparecem. */
    public static List<Integer> questionRefs(String content) {
        return parse(content).stream().flatMap(s -> s.parts().stream()).map(Part::question)
                .filter(java.util.Objects::nonNull).toList();
    }

    private static void flush(StringBuilder text, List<Part> parts) {
        if (!text.toString().isBlank()) {
            parts.add(new Part(SimpleMarkdown.toHtml(text.toString()), null));
        }
        text.setLength(0);
    }
}
