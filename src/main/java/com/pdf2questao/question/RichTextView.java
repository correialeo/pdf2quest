package com.pdf2questao.question;

import com.pdf2questao.importer.parser.support.RichText;
import org.springframework.stereotype.Component;

/** Usado nos templates como {@code th:utext="${@richText.html(texto)}"}. */
@Component("richText")
public class RichTextView {

    public String html(String rich) {
        return RichText.toHtml(rich, "/imagens/");
    }
}
