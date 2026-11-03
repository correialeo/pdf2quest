package com.pdf2questao.aula;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LessonSlidesConceptsTest {

    private static final String AULA = """
            === Inscrição em RP {#rp-inscricao}
            Empenho 100.000, liquidado 60.000.
            :::pegadinha Prazo {#rp-prazo}
            Vale até 31/12.
            :::
            [[questão 1]]
            === Sem conceito
            Texto.
            [[questão 2]]
            """;

    @Test
    void conceitosDosSlidesEDasCaixasComOSlideDeCada() {
        var concepts = LessonSlides.concepts(AULA);
        assertEquals(List.of("rp-inscricao", "rp-prazo"), List.copyOf(concepts.keySet()));
        assertEquals(1, concepts.get("rp-prazo").slide());
        assertEquals("Inscrição em RP", concepts.get("rp-inscricao").slideTitle(), "o {#id} sai do título");

        var slides = LessonSlides.parse(AULA);
        assertNull(slides.get(1).concept());
        assertTrue(slides.get(0).source().contains("60.000"));
        assertFalse(slides.get(0).source().contains("[[questão"), "marcador de questão não é dado do slide");
        String box = slides.get(0).parts().get(1).html();
        assertTrue(box.contains("id=\"c-rp-prazo\"") && box.contains(">Prazo<"), box);
        assertEquals(java.util.Map.of(1, 1, 2, 2), LessonSlides.questionSlides(AULA));
    }

    @Test
    void idRepetidoERecusado() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> LessonSlides.concepts("=== A {#x}\nt\n=== B {#x}\nt"));
        assertTrue(e.getMessage().contains("slides 1 e 2"), e.getMessage());
    }
}
