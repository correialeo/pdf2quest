package com.pdf2questao.importer.parser.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Marcacao que leva negrito/italico/sublinhado ({@code ⟦b⟧...⟦/b⟧}), imagens
 * ({@code ⟦img:N⟧}) e inicio de pagina ({@code ⟦p:N⟧}) do PDF ate o banco. Os
 * parsers decidem sempre sobre {@link #plain(String)}.
 */
public final class RichText {

    public static final Pattern TOKEN = Pattern.compile("\u27E6(/?[biu]|img:\\d+|p:\\d+)\u27E7");
    private static final Pattern PAGE_TOKEN = Pattern.compile("\u27E6p:(\\d+)\u27E7");
    private static final Pattern IMAGE_TOKEN = Pattern.compile("\u27E6img:(\\d+)\u27E7");

    private RichText() {
    }

    public static String open(char style) {
        return "\u27E6" + style + "\u27E7";
    }

    public static String close(char style) {
        return "\u27E6/" + style + "\u27E7";
    }

    public static String image(int id) {
        return "\u27E6img:" + id + "\u27E7";
    }

    public static String page(int number) {
        return "\u27E6p:" + number + "\u27E7";
    }

    public static String plain(String rich) {
        return rich == null ? null : TOKEN.matcher(rich).replaceAll("");
    }

    public static Integer pageOf(String rich) {
        Matcher m = PAGE_TOKEN.matcher(rich);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    public static String removePageTokens(String rich) {
        return rich == null ? null : PAGE_TOKEN.matcher(rich).replaceAll("");
    }

    /** Troca os indices provisorios de imagem (ordem de extracao) pelos ids persistidos. */
    public static String remapImages(String rich, java.util.function.IntUnaryOperator mapping) {
        if (rich == null) {
            return null;
        }
        Matcher m = IMAGE_TOKEN.matcher(rich);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int mapped = mapping.applyAsInt(Integer.parseInt(m.group(1)));
            m.appendReplacement(sb, mapped < 0 ? "" : Matcher.quoteReplacement(image(mapped)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Garante {@code plain(trim(x)).equals(plain(x).trim())}, preservando a marcacao. */
    public static String trim(String rich) {
        int start = 0;
        int end = rich.length();
        while (true) {
            Matcher m = TOKEN.matcher(rich).region(start, end);
            if (m.lookingAt()) {
                start = m.end();
            } else if (start < end && Character.isWhitespace(rich.charAt(start))) {
                start++;
            } else {
                break;
            }
        }
        String prefixTokens = TOKEN.matcher(rich.substring(0, start)).results()
                .map(java.util.regex.MatchResult::group).reduce("", String::concat);
        String rest = rich.substring(start);
        int restEnd = rest.length();
        StringBuilder suffixTokens = new StringBuilder();
        while (restEnd > 0) {
            if (Character.isWhitespace(rest.charAt(restEnd - 1))) {
                restEnd--;
                continue;
            }
            if (rest.charAt(restEnd - 1) == '\u27E7') {
                int open = rest.lastIndexOf('\u27E6', restEnd - 1);
                if (open >= 0 && TOKEN.matcher(rest.substring(open, restEnd)).matches()) {
                    suffixTokens.insert(0, rest, open, restEnd);
                    restEnd = open;
                    continue;
                }
            }
            break;
        }
        return prefixTokens + rest.substring(0, restEnd) + suffixTokens;
    }

    /** Corta no caractere visivel {@code visibleOffset}, reabrindo estilos abertos antes do corte. */
    public static String fromVisible(String rich, int visibleOffset) {
        StringBuilder openStyles = new StringBuilder();
        int visible = 0;
        int i = 0;
        while (i < rich.length() && visible < visibleOffset) {
            Matcher m = TOKEN.matcher(rich).region(i, rich.length());
            if (m.lookingAt()) {
                String t = m.group(1);
                if (t.length() == 1) {
                    openStyles.append(t);
                } else if (t.length() == 2 && t.charAt(0) == '/') {
                    int idx = openStyles.lastIndexOf(t.substring(1));
                    if (idx >= 0) {
                        openStyles.deleteCharAt(idx);
                    }
                }
                i = m.end();
            } else {
                visible++;
                i++;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < openStyles.length(); k++) {
            sb.append(open(openStyles.charAt(k)));
        }
        return sb.append(rich.substring(i)).toString();
    }

    /** Todo o texto e escapado; so as tags geradas aqui chegam ao navegador. */
    public static String toHtml(String rich, String imageUrlPrefix) {
        if (rich == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Matcher m = TOKEN.matcher(rich);
        int last = 0;
        while (m.find()) {
            escape(rich.substring(last, m.start()), sb);
            String t = m.group(1);
            switch (t) {
                case "b" -> sb.append("<strong>");
                case "/b" -> sb.append("</strong>");
                case "i" -> sb.append("<em>");
                case "/i" -> sb.append("</em>");
                case "u" -> sb.append("<u>");
                case "/u" -> sb.append("</u>");
                default -> {
                    if (t.startsWith("img:")) {
                        sb.append("<img class=\"question-image\" alt=\"Imagem da questao\" src=\"")
                                .append(imageUrlPrefix).append(t.substring(4)).append("\"/>");
                    }
                }
            }
            last = m.end();
        }
        escape(rich.substring(last), sb);
        return sb.toString();
    }

    private static void escape(String s, StringBuilder sb) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
    }
}
