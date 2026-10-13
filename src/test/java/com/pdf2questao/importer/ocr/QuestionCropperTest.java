package com.pdf2questao.importer.ocr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuestionCropperTest {

    @Test
    void ordinalPerdidoNoOcrVoltaComArtigoESubstantivoConhecidos() {
        assertEquals("o valor de X correspondente ao 2\u00BA ano do fluxo", QuestionCropper.fixOrdinals(
                "o valor de X correspondente ao 2 ano do fluxo"));
        assertEquals("vencendo a 12\u00AA presta\u00E7\u00E3o", QuestionCropper.fixOrdinals("vencendo a 12 presta\u00E7\u00E3o"));
    }

    @Test
    void numeroSemSubstantivoDeOrdinalNaoMuda() {
        String text = "o saldo de 2 anos e a taxa de 15 por cento";
        assertEquals(text, QuestionCropper.fixOrdinals(text));
    }
}
