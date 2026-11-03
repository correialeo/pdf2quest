package com.pdf2questao.web;

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

    @Test
    void codigoCitacaoEListaNumerada() {
        assertEquals("<p>Use <code>a*b</code> e **x**</p>".replace("**x**", "<strong>x</strong>"),
                SimpleMarkdown.toHtml("Use `a*b` e **x**"));
        assertEquals("<pre><code>SELECT *\nFROM t WHERE a &lt; 2</code></pre><p>fim</p>",
                SimpleMarkdown.toHtml("```sql\nSELECT *\nFROM t WHERE a < 2\n```\nfim"));
        assertEquals("<blockquote><p>FCC adora <strong>EXCETO</strong></p></blockquote><ol><li>um</li><li>dois</li></ol>",
                SimpleMarkdown.toHtml("> FCC adora **EXCETO**\n1. um\n2. dois"));
    }

    @Test
    void negritoComCodigoDentroETabela() {
        assertEquals("<p>ganham <strong><code>_x</code> e <code>_y</code></strong> (com <code>suffixes=</code>)</p>",
                SimpleMarkdown.toHtml("ganham **`_x` e `_y`** (com `suffixes=`)"));
        assertEquals("<p><strong><code>df.join</code> une pelo índice</strong></p>",
                SimpleMarkdown.toHtml("**`df.join` une pelo índice**"));
        assertEquals("<table><thead><tr><th>k</th><th>v</th></tr></thead><tbody><tr><td>1</td><td><strong>a</strong></td>"
                + "</tr></tbody></table><p>fim</p>", SimpleMarkdown.toHtml("| k | v |\n|---|---|\n| 1 | **a** |\nfim"));
    }
}
