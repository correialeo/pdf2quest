package com.pdf2questao.aula;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormCitationsTest {

    private static SourceExcerpt trecho(String dispositivo, String texto) {
        SourceExcerpt e = new SourceExcerpt();
        e.setDispositivo(dispositivo);
        e.setTexto(texto);
        return e;
    }

    static final SourceExcerpt LRF_42 = trecho("LRF, art. 42", "Art. 42. É vedado ao titular de Poder ou órgão "
            + "referido no art. 20, nos últimos dois quadrimestres do seu mandato, contrair obrigação de despesa...");
    static final SourceExcerpt L4320_36 = trecho("Lei 4.320, art. 36", "Art. 36. Consideram-se Restos a Pagar as "
            + "despesas empenhadas mas não pagas até o dia 31 de dezembro...");

    @Test
    void reconheceANormaDeCadaCitacao() {
        var c = NormCitations.cited("O art. 42 da LRF, o art. 36 da Lei nº 4.320/64, o art. 165, § 8º, da CF, "
                + "LC 101, art. 9º e o art. 1º do Decreto 93.872.", null);
        assertEquals(List.of("lc101:42", "lei4320:36", "cf:165", "lc101:9", "decreto93872:1"),
                c.stream().map(NormCitations.Citation::key).toList());
        assertEquals("cf", NormCitations.first("Constituição Federal de 1988").key());
        assertEquals("lc101", NormCitations.first("Lei de Responsabilidade Fiscal").key());
        assertEquals("lc101", NormCitations.first("Lei Complementar nº 101/2000").key());
    }

    @Test
    void mesmoNumeroEmOutraLeiNaoContaComoConferido() {
        var faltam = LessonService.articlesWithoutExcerpt("=== RP\nO art. 42 da Lei 4.320 trata do crédito "
                + "suplementar; o art. 42 da LRF, do fim de mandato.", List.of(LRF_42), null);
        assertEquals(List.of("art. 42 da Lei 4.320"), faltam);
    }

    @Test
    void semNormaNaCitacaoUsaOTituloDoSlideOuOUnicoTrecho() {
        assertEquals(List.of(), LessonService.articlesWithoutExcerpt("=== Fim de mandato (LRF)\nO art. 42 veda...",
                List.of(LRF_42, L4320_36), null));
        assertEquals(List.of(), LessonService.articlesWithoutExcerpt("=== Fim de mandato\nO art. 42 veda...",
                List.of(LRF_42), null), "só há trechos da LRF");
        var ambigua = LessonService.articlesWithoutExcerpt("=== Restos\nO art. 42 veda...", List.of(LRF_42, L4320_36), null);
        assertTrue(ambigua.get(0).startsWith("art. 42 (de que norma?"), ambigua.toString());
    }

    @Test
    void artigoSoMencionadoNoTrechoNaoEConferidoMasNaoConfirmadoAceita() {
        assertEquals(List.of("art. 20 da LRF (LC 101)"), LessonService.articlesWithoutExcerpt(
                "=== LRF\nO art. 20 da LRF fixa os limites.", List.of(LRF_42), null));
        assertEquals(List.of(), LessonService.articlesWithoutExcerpt("=== LRF\nO art. 20 da LRF fixa os limites.",
                List.of(LRF_42), "art. 20 da LRF: texto do Planalto não abriu"));
    }
}
