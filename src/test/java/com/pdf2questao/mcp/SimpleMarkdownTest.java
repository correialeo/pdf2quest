package com.pdf2questao.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimpleMarkdownTest {

    @Test
    void titulosListasComSublistaEParagrafos() {
        String html = SimpleMarkdown.toHtml("""
                ## Treino AFO
                - Anualidade: **errei**
                  - confundi com *unidade*
                - Exclusividade ok

                Revisar amanhã.
                linha 2""");

        assertEquals("<h2>Treino AFO</h2><ul><li>Anualidade: <strong>errei</strong><ul><li>confundi com "
                + "<em>unidade</em></li></ul></li><li>Exclusividade ok</li></ul><p>Revisar amanhã.<br>linha 2</p>", html);
    }

    @Test
    void escapaHtmlEFechaSublistasNoFim() {
        assertEquals("<ul><li>a &lt;b&gt;<ul><li>b<ul><li>c</li></ul></li></ul></li></ul>",
                SimpleMarkdown.toHtml("- a <b>\n  - b\n    - c"));
        assertEquals("<h3>x</h3><p>2 * 3 = 6</p>", SimpleMarkdown.toHtml("### x\n2 * 3 = 6"));
    }
}
