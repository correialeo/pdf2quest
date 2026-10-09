package com.pdf2questao.importer.parser.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RichTextTest {

    @Test
    void textoVisivelIgnoraMarcacao() {
        assertEquals("Pessoas que nao se contentavam - descontentes",
                RichText.plain("Pessoas ⟦u⟧que nao se contentavam⟦/u⟧ - descontentes"));
    }

    @Test
    void trimPreservaMarcacaoEBateComTrimDoTextoVisivel() {
        String rich = "⟦p:3⟧  ⟦b⟧1⟦/b⟧   ";
        assertEquals("1", RichText.plain(RichText.trim(rich)));
        assertEquals(3, RichText.pageOf(RichText.trim(rich)));
    }

    @Test
    void cortarPorOffsetVisivelReabreEstiloAberto() {
        String rich = "(A) ⟦u⟧Este apertou⟦/u⟧ a mao";
        assertEquals("⟦u⟧apertou⟦/u⟧ a mao", RichText.fromVisible(rich, 9));
    }

    @Test
    void htmlEscapaTextoEConverteDestaquesEImagens() {
        String html = RichText.toHtml("if (a < b) ⟦b⟧x⟦/b⟧ ⟦img:7⟧", "/imagens/");
        assertEquals("if (a &lt; b) <strong>x</strong> "
                + "<img class=\"question-image\" alt=\"Imagem da questao\" src=\"/imagens/7\"/>", html);
    }

    @Test
    void separaAlternativasMantendoDestaques() {
        QuestionBlocks.Split split = QuestionBlocks.split(List.of(
                "Assinale a opção em que o elemento destacado ⟦b⟧não⟦/b⟧ funciona",
                "(A) Após ⟦u⟧alguns⟦/u⟧ instantes",
                "rápidos.",
                "(B) Outra"), java.util.regex.Pattern.compile("(?m)^\\s*\\(([A-E])\\)\\s+(.*)$"));
        assertEquals("Assinale a opção em que o elemento destacado ⟦b⟧não⟦/b⟧ funciona",
                split.statement());
        assertEquals("Após ⟦u⟧alguns⟦/u⟧ instantes rápidos.", split.alternatives().get("A"));
        assertEquals("Outra", split.alternatives().get("B"));
    }

    @Test
    void quantidadeDeQuestoesAnunciadaNoTextoDeApoio() {
        assertEquals(6, QuestionBlocks.cueCount("Use the following TEXT to answer the next six questions."));
        assertEquals(5, QuestionBlocks.cueCount("Leia o texto para responder às questões de 1 a 5."));
        assertEquals(3, QuestionBlocks.cueCount("Considere o texto a seguir para responder às próximas três questões."));
        assertEquals(-1, QuestionBlocks.cueCount("Leia o texto a seguir."));
    }
}
