package com.pdf2questao.web;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown simples (o que o Claude escreve num resumo) para o HTML do editor de paginas: titulos ## e ###,
 * listas com "- " ou "* " (2 espacos de recuo por nivel viram sublista), listas numeradas "1. ", citacoes "> ",
 * blocos de codigo entre ``` , `codigo`, **negrito**, *italico* e paragrafos. Todo texto e escapado.
 * O resultado ainda passa pelo {@code HtmlSanitizer} ao ser gravado.
 */
public final class SimpleMarkdown {

    private SimpleMarkdown() {
    }

    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        List<String> paragraph = new ArrayList<>();
        int depth = 0;   // listas abertas
        boolean ordered = false;   // lista numerada aberta (um nivel)
        StringBuilder code = null;   // bloco ``` aberto
        List<String> quote = new ArrayList<>();
        for (String raw : markdown.strip().split("\\R")) {
            String line = raw.replace("\t", "  ");
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                if (code == null) {
                    flush(out, paragraph);
                    closeLists(out, depth, 0);
                    depth = 0;
                    ordered = closeOrdered(out, ordered);
                    flushQuote(out, quote);
                    code = new StringBuilder();
                } else {
                    out.append("<pre><code>").append(escape(code.toString().stripTrailing())).append("</code></pre>");
                    code = null;
                }
                continue;
            }
            if (code != null) {
                code.append(raw).append('\n');
                continue;
            }
            boolean numbered = trimmed.matches("\\d+[.)] .*");
            if (!numbered) {
                ordered = closeOrdered(out, ordered);
            }
            if (trimmed.startsWith("> ") || trimmed.equals(">")) {
                flush(out, paragraph);
                quote.add(inline(trimmed.substring(1).strip()));
                continue;
            }
            flushQuote(out, quote);
            boolean item = trimmed.startsWith("- ") || trimmed.startsWith("* ");
            if (!item && depth > 0) {
                closeLists(out, depth, 0);
                depth = 0;
            }
            if (numbered) {
                flush(out, paragraph);
                if (!ordered) {
                    out.append("<ol>");
                    ordered = true;
                }
                out.append("<li>").append(inline(trimmed.substring(trimmed.indexOf(' ') + 1).strip())).append("</li>");
                continue;
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
        closeOrdered(out, ordered);
        flushQuote(out, quote);
        if (code != null) {
            out.append("<pre><code>").append(escape(code.toString().stripTrailing())).append("</code></pre>");
        }
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

    private static boolean closeOrdered(StringBuilder out, boolean ordered) {
        if (ordered) {
            out.append("</ol>");
        }
        return false;
    }

    private static void flushQuote(StringBuilder out, List<String> quote) {
        if (!quote.isEmpty()) {
            out.append("<blockquote><p>").append(String.join("<br>", quote)).append("</p></blockquote>");
            quote.clear();
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String inline(String text) {
        // `codigo` primeiro, sem negrito/italico dentro
        StringBuilder out = new StringBuilder();
        String[] parts = text.split("`", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i % 2 == 1 && i < parts.length - 1) {
                out.append("<code>").append(escape(parts[i])).append("</code>");
            } else {
                out.append(emphasis(escape(i % 2 == 1 ? "`" + parts[i] : parts[i])));
            }
        }
        return out.toString();
    }

    private static String emphasis(String escaped) {
        return escaped.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>")
                .replaceAll("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])", "<em>$1</em>");
    }
}
