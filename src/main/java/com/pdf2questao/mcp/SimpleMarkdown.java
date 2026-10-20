package com.pdf2questao.mcp;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown simples (o que o Claude escreve num resumo) para o HTML do editor de paginas: titulos ## e ###,
 * listas com "- " ou "* " (2 espacos de recuo por nivel viram sublista), **negrito**, *italico* e paragrafos.
 * O resultado ainda passa pelo {@code HtmlSanitizer} ao ser gravado.
 */
final class SimpleMarkdown {

    private SimpleMarkdown() {
    }

    static String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        List<String> paragraph = new ArrayList<>();
        int depth = 0;   // listas abertas
        for (String raw : markdown.strip().split("\\R")) {
            String line = raw.replace("\t", "  ");
            String trimmed = line.strip();
            boolean item = trimmed.startsWith("- ") || trimmed.startsWith("* ");
            if (!item && depth > 0) {
                closeLists(out, depth, 0);
                depth = 0;
            }
            if (trimmed.isEmpty()) {
                flush(out, paragraph);
                continue;
            }
            if (item) {
                flush(out, paragraph);
                int level = Math.min((line.length() - line.stripLeading().length()) / 2 + 1, depth + 1);
                if (level > depth) {
                    if (depth > 0) {
                        out.setLength(out.length() - "</li>".length());   // sublista fica dentro do item anterior
                    }
                    out.append("<ul>");
                    depth = level;
                } else if (level < depth) {
                    closeLists(out, depth, level);
                    depth = level;
                }
                out.append("<li>").append(inline(trimmed.substring(2).strip())).append("</li>");
            } else if (trimmed.startsWith("### ")) {
                flush(out, paragraph);
                out.append("<h3>").append(inline(trimmed.substring(4).strip())).append("</h3>");
            } else if (trimmed.startsWith("## ") || trimmed.startsWith("# ")) {
                flush(out, paragraph);
                out.append("<h2>").append(inline(trimmed.substring(trimmed.indexOf(' ') + 1).strip())).append("</h2>");
            } else {
                paragraph.add(inline(trimmed));
            }
        }
        flush(out, paragraph);
        closeLists(out, depth, 0);
        return out.toString();
    }

    /** Fecha listas ate ficar em {@code to} niveis (cada sublista fecha tambem o item que a contem). */
    private static void closeLists(StringBuilder out, int from, int to) {
        for (int d = from; d > to; d--) {
            out.append("</ul>");
            if (d > 1) {
                out.append("</li>");
            }
        }
    }

    private static void flush(StringBuilder out, List<String> paragraph) {
        if (!paragraph.isEmpty()) {
            out.append("<p>").append(String.join("<br>", paragraph)).append("</p>");
            paragraph.clear();
        }
    }

    private static String inline(String text) {
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return escaped.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>")
                .replaceAll("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])", "<em>$1</em>");
    }
}
