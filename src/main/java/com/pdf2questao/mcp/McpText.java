package com.pdf2questao.mcp;

import com.pdf2questao.importer.parser.support.RichText;

/** Texto para o chat: sem marcas de destaque das questoes de PDF, sem HTML e com tamanho limitado. */
final class McpText {

    private McpText() {
    }

    static String plain(String text) {
        if (text == null) {
            return null;
        }
        return RichText.TOKEN.matcher(text).replaceAll("")
                .replaceAll("(?i)<br\\s*/?>|</(p|li|h[1-6]|tr|div)>", "\n").replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&")
                .replaceAll("[ \\t]+", " ").replaceAll(" *\\n[ \\n]*", "\n").strip();
    }

    static String clip(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max) + "…";
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
