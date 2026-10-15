package com.pdf2questao.study;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HtmlSanitizerTest {

    @Test
    void mantemOQueOEditorProduz() {
        String html = "<h1>Atos</h1><p><strong>Negrito</strong> e <em>itálico</em><br></p>"
                + "<ul data-checklist=\"\"><li data-checked=\"true\">feito</li><li data-checked=\"false\">não</li></ul>"
                + "<ol><li>um</li></ol><table><tbody><tr><th>A</th><td>1</td></tr></tbody></table>"
                + "<pre>código &lt;x&gt;</pre><hr><blockquote>art. 37</blockquote>";
        assertEquals("<h1>Atos</h1><p><strong>Negrito</strong> e <em>itálico</em><br></p>"
                + "<ul data-checklist><li data-checked=\"true\">feito</li><li data-checked=\"false\">não</li></ul>"
                + "<ol><li>um</li></ol><table><tbody><tr><th>A</th><td>1</td></tr></tbody></table>"
                + "<pre>código &lt;x&gt;</pre><hr><blockquote>art. 37</blockquote>", HtmlSanitizer.sanitize(html));
    }

    @Test
    void removeScriptsEventosEstilosELinksPerigosos() {
        assertEquals("<p>oi</p>", HtmlSanitizer.sanitize("<p onclick=\"x()\" style=\"color:red\">oi<script>alert(1)</script></p>"));
        assertEquals("<a>x</a>", HtmlSanitizer.sanitize("<a href=\"javascript:alert(1)\">x</a>"));
        assertEquals("<a>x</a>", HtmlSanitizer.sanitize("<a href=\" JaVaScRiPt:alert(1)\">x</a>"));
        assertEquals("texto", HtmlSanitizer.sanitize("<img src=x onerror=alert(1)>texto<!-- comentario -->"));
        assertEquals("<p>a</p>", HtmlSanitizer.sanitize("<p>a<style>p{}</style><iframe src=\"x\"></iframe></p>"));
        assertEquals("<span>sem fim</span>", HtmlSanitizer.sanitize("<span>sem fim"));
        assertEquals("1 &lt; 2 &amp; 3 &gt; 0 &amp;nbsp", HtmlSanitizer.sanitize("1 < 2 & 3 > 0 &amp;nbsp"));
    }

    @Test
    void linksSeguros() {
        assertEquals("<a href=\"https://www.planalto.gov.br/a?b=1&amp;c=2\" target=\"_blank\" rel=\"noopener noreferrer\">lei</a>",
                HtmlSanitizer.sanitize("<a href='https://www.planalto.gov.br/a?b=1&amp;c=2' target=\"_self\">lei</a>"));
        assertEquals("<a href=\"/estudos/paginas/1\" target=\"_blank\" rel=\"noopener noreferrer\">p</a>",
                HtmlSanitizer.sanitize("<a href=\"/estudos/paginas/1\">p</a>"));
    }

    @Test
    void tagDesconhecidaSomeMasOTextoFica() {
        assertEquals("<p>Olá mundo</p>", HtmlSanitizer.sanitize("<p><font color=red>Olá</font> <section>mundo</section></p>"));
        assertEquals("ab", HtmlSanitizer.sanitize("a</p>b"));
    }

    @Test
    void textoSimplesViraParagrafos() {
        assertEquals("<p>linha 1<br>linha 2</p><p>&lt;b&gt; outro</p>",
                HtmlSanitizer.fromPlainText("linha 1\nlinha 2\n\n<b> outro"));
    }
}
