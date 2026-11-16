package com.pdf2questao.aula;

import com.pdf2questao.aula.LessonDidactics.EditalItem;
import com.pdf2questao.aula.LessonDidactics.Term;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Didática da aula: edital coberto, explicação simples e em texto em cada slide, siglas explicadas. */
class LessonDidacticsTest {

    static final String TEXTO = "Explicação em texto corrido do conceito, passo a passo, para quem nunca viu. ".repeat(13);

    /** Slide de conteúdo aceito: caixa simples e texto corrido acima do mínimo. */
    static String slide(String titulo, String id, String extra) {
        return "=== " + titulo + " {#" + id + "}\n" + TEXTO + "\n:::simples\nÉ como a conta de luz: você deve desde "
                + "que consumiu, não só quando paga.\n:::\n" + extra + "\n[[questão 1]]\n";
    }

    static final String TOPICO = "Restos a pagar: inscrição, cancelamento e prescrição";

    @Test
    void itemDoEditalSemSlideERecusado() {
        String aula = slide("Inscrição", "rp-inscricao", "") + slide("Cancelamento", "rp-cancelamento", "");
        var semCobertura = LessonDidactics.coverageProblems(aula, TOPICO, null);
        assertTrue(semCobertura.get(0).contains("coberturaEdital"), semCobertura.toString());

        var faltaItem = LessonDidactics.coverageProblems(aula, TOPICO, List.of(
                new EditalItem("Restos a pagar: inscrição", List.of("rp-inscricao")),
                new EditalItem("cancelamento", List.of("rp-cancelamento"))));
        assertEquals(1, faltaItem.size(), faltaItem.toString());
        assertTrue(faltaItem.get(0).contains("prescricao"), "a prescrição está no edital e não na aula: " + faltaItem);

        var conceitoInexistente = LessonDidactics.coverageProblems(aula, TOPICO, List.of(
                new EditalItem("Restos a pagar: inscrição, cancelamento e prescrição", List.of("rp-prescricao"))));
        assertTrue(conceitoInexistente.get(0).contains("rp-prescricao"), conceitoInexistente.toString());

        assertTrue(LessonDidactics.coverageProblems(aula, TOPICO, List.of(
                new EditalItem("Restos a pagar: inscrição", List.of("rp-inscricao")),
                new EditalItem("Cancelamento e prescrição", List.of("rp-cancelamento")))).isEmpty());
    }

    @Test
    void slideDeConteudoPrecisaDeExplicacaoSimplesEDeTexto() {
        String telegrafico = "=== Inscrição {#rp-inscricao}\n- empenhada e não paga até 31/12\n| a | b |\n"
                + "=== Resumo {#rp-resumo}\n- curto\n";
        var problemas = LessonDidactics.slideProblems(telegrafico);
        assertEquals(2, problemas.size(), "caixa simples e texto; o slide de resumo é dispensado: " + problemas);
        assertTrue(problemas.get(0).contains(":::simples"), problemas.toString());
        assertTrue(problemas.get(1).contains("caracteres de explicação"), problemas.toString());
        assertTrue(LessonDidactics.slideProblems(slide("Inscrição", "rp-inscricao", "")).isEmpty());
    }

    @Test
    void tabelaECodigoNaoContamComoExplicacao() {
        assertEquals(0, LessonDidactics.prose("| Conta | Valor |\n| Caixa | 100 |\n```sql\nSELECT 1\n```\n:::regra X\n+++\n:::"));
        assertEquals("Texto corrido.".length(), LessonDidactics.prose("**Texto** corrido.\n| a | b |"));
    }

    @Test
    void siglaPrecisaDeExplicacaoNoTextoOuNoGlossario() {
        String aula = slide("Inscrição", "rp-inscricao", "A VPD nasce no fato gerador e a DDR fica comprometida.");
        var problemas = LessonDidactics.acronymProblems(aula, null);
        assertEquals(1, problemas.size());
        assertTrue(problemas.get(0).contains("[VPD, DDR]"), problemas.toString());

        String explicada = slide("Inscrição", "rp-inscricao", "A **variação patrimonial diminutiva (VPD)** nasce no "
                + "fato gerador. DDR (disponibilidade por destinação de recursos) fica comprometida.");
        assertTrue(LessonDidactics.acronymProblems(explicada, null).isEmpty());
        assertTrue(LessonDidactics.acronymProblems(aula, List.of(new Term("VPD", "variação patrimonial diminutiva"),
                new Term("DDR", "disponibilidade por destinação de recursos"))).isEmpty());
    }

    @Test
    void enfaseAlgarismoRomanoECodigoNaoSaoSigla() {
        String aula = slide("Inscrição", "rp-inscricao", "Isso vale ANTES do pagamento, nunca antes do empenho. Os itens "
                + "II e III do art. 36. O `LEFT JOIN` e o GROUP dos dados:\n```sql\nSELECT a FROM t GROUP BY a\n```\n"
                + "O contribuinte de SP paga ICMS e US$ 10.");
        assertTrue(LessonDidactics.acronymProblems(aula, null).isEmpty(),
                LessonDidactics.acronymProblems(aula, null).toString());
    }

    @Test
    void aulaCompletaPassaEGanhaRoteiroEGlossario() {
        String aula = slide("Inscrição", "rp-inscricao", "") + slide("Cancelamento", "rp-cancelamento", "");
        var cobertura = List.of(new EditalItem("Restos a pagar: inscrição", List.of("rp-inscricao")),
                new EditalItem("cancelamento e prescrição", List.of("rp-cancelamento")));
        assertDoesNotThrow(() -> LessonDidactics.check(aula, TOPICO, cobertura, null));
        var recusada = assertThrows(IllegalArgumentException.class,
                () -> LessonDidactics.check(aula.replace(":::simples", ":::dica"), TOPICO, null, null));
        assertTrue(recusada.getMessage().contains("coberturaEdital") && recusada.getMessage().contains(":::simples"),
                "todos os problemas de uma vez: " + recusada.getMessage());

        String roteiro = LessonDidactics.roadmapSlide(aula, TOPICO, cobertura);
        assertTrue(roteiro.startsWith("=== O que o edital cobra"), roteiro);
        assertTrue(roteiro.contains("| cancelamento e prescrição | slide 3: Cancelamento |"),
                "o roteiro é o slide 1, então o 2º slide de conteúdo é o 3: " + roteiro);
        assertTrue(LessonDidactics.glossarySlide(List.of(new Term("RP", "restos a pagar")))
                .contains("| **RP** | restos a pagar |"));
        assertEquals("", LessonDidactics.glossarySlide(null));
    }
}
