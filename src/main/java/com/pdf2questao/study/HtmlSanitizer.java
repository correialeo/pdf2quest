package com.pdf2questao.study;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Limpa o HTML do editor de paginas antes de gravar: so passam as tags e atributos que o editor produz
 * (titulos, listas, checklists, links, tabelas, codigo...). Script, estilo, eventos e links "javascript:" somem;
 * tags desconhecidas somem mas o texto delas fica. Toda tag aberta e fechada.
 */
public final class HtmlSanitizer {

    private static final Set<String> ALLOWED = Set.of("p", "br", "h1", "h2", "h3", "strong", "b", "em", "i", "u", "s",
            "ul", "ol", "li", "a", "table", "thead", "tbody", "tr", "th", "td", "pre", "code", "hr", "blockquote",
            "div", "span");
    private static final Set<String> VOID = Set.of("br", "hr");
    /** O conteudo destas some junto com a tag. */
    private static final Set<String> DROP_CONTENT = Set.of("script", "style", "iframe", "object", "embed", "template",
            "noscript", "textarea", "select", "svg", "math", "head", "title");
    private static final Map<String, Set<String>> ATTRIBUTES = Map.of(
            "a", Set.of("href"),
            "ul", Set.of("data-checklist"),
            "li", Set.of("data-checked"));

    private static final Pattern TAG = Pattern.compile(
            "<(/?)([a-zA-Z][a-zA-Z0-9]*)((?:\\s+[^\\s\"'>/=]+(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s\"'>]+))?)*)\\s*/?>");
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([^\\s\"'>/=]+)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+)))?");
    private static final Pattern ENTITY = Pattern.compile("&(?:[a-zA-Z][a-zA-Z0-9]{1,31}|#\\d{1,7}|#[xX][0-9a-fA-F]{1,6});");
    private static final Pattern SAFE_URL = Pattern.compile("^(?:https?:|mailto:|/|#)", Pattern.CASE_INSENSITIVE);

    private HtmlSanitizer() {
    }

    public static String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder(html.length());
        Deque<String> open = new ArrayDeque<>();
        String dropping = null;
        int i = 0;
        Matcher tag = TAG.matcher(html);
        while (i < html.length()) {
            char c = html.charAt(i);
            if (c == '<' && html.startsWith("<!--", i)) {
                int end = html.indexOf("-->", i + 4);
                i = end < 0 ? html.length() : end + 3;
                continue;
            }
            if (c == '<' && tag.region(i, html.length()).lookingAt()) {
                boolean closing = !tag.group(1).isEmpty();
                String name = tag.group(2).toLowerCase(Locale.ROOT);
                i = tag.end();
                if (dropping != null) {
                    if (closing && name.equals(dropping)) {
                        dropping = null;
                    }
                    continue;
                }
                if (DROP_CONTENT.contains(name)) {
                    if (!closing) {
                        dropping = name;
                    }
                    continue;
                }
                if (!ALLOWED.contains(name)) {
                    continue;
                }
                if (closing) {
                    if (open.contains(name)) {
                        String top;
                        do {
                            top = open.pop();
                            out.append("</").append(top).append('>');
                        } while (!top.equals(name));
                    }
                    continue;
                }
                out.append('<').append(name);
                attributes(name, tag.group(3), out);
                out.append('>');
                if (!VOID.contains(name)) {
                    open.push(name);
                }
                continue;
            }
            i++;
            if (dropping != null) {
                continue;
            }
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> {
                    Matcher m = ENTITY.matcher(html).region(i - 1, html.length());
                    out.append(m.lookingAt() ? "&" : "&amp;");
                }
                default -> out.append(c);
            }
        }
        while (!open.isEmpty()) {
            out.append("</").append(open.pop()).append('>');
        }
        return out.toString();
    }

    private static void attributes(String tag, String raw, StringBuilder out) {
        Set<String> allowed = ATTRIBUTES.get(tag);
        if (allowed == null || raw == null || raw.isBlank()) {
            return;
        }
        Matcher m = ATTRIBUTE.matcher(raw);
        while (m.find()) {
            String name = m.group(1).toLowerCase(Locale.ROOT);
            if (!allowed.contains(name)) {
                continue;
            }
            String value = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3)
                    : m.group(4) != null ? m.group(4) : "";
            switch (name) {
                case "href" -> {
                    String url = value.trim();
                    if (!SAFE_URL.matcher(url).find() && url.contains(":")) {
                        continue;
                    }
                    out.append(" href=\"").append(escapeAttribute(url)).append('"');
                    out.append(" target=\"_blank\" rel=\"noopener noreferrer\"");
                }
                case "data-checked" -> out.append(" data-checked=\"").append("true".equals(value) ? "true" : "false").append('"');
                default -> out.append(' ').append(name);
            }
        }
    }

    /** Valor de atributo vindo do HTML: entidades que ja estao la (como {@code &amp;} num link) ficam como estao. */
    private static String escapeAttribute(String value) {
        StringBuilder out = new StringBuilder(value.length());
        Matcher entity = ENTITY.matcher(value);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append(entity.region(i, value.length()).lookingAt() ? "&" : "&amp;");
                case '"' -> out.append("&quot;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String escapeText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Texto simples (caixa de entrada) em paragrafos HTML, para virar pagina. */
    public static String fromPlainText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String paragraph : text.strip().split("\\R\\s*\\R")) {
            out.append("<p>").append(escapeText(paragraph.strip()).replaceAll("\\R", "<br>")).append("</p>");
        }
        return out.toString();
    }
}
