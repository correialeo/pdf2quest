package com.pdf2questao.simulado.review;

import com.pdf2questao.simulado.review.ReviewRules.Criterion;
import com.pdf2questao.simulado.review.ReviewRules.Source;
import com.pdf2questao.simulado.review.ReviewRules.SourceState;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regras de aprovacao das questoes geradas, sem banco nem internet. */
class ReviewRulesTest {

    private static final String WHY = "conforme a questão de referência #12";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private static List<Criterion> all(int note) {
        return new ArrayList<>(Collections.nCopies(8, new Criterion(note, WHY)));
    }

    private static Source source(SourceState state, boolean primary) {
        return new Source("https://www.planalto.gov.br/ccivil_03/constituicao/constituicao.htm", primary,
                "CF, art. 100", TODAY, state, null);
    }

    @Test
    void fidelidadeAltaComReferenciaAprova() {
        var o = ReviewRules.fidelity(all(4), 2, 0.2);
        assertEquals(ReviewStatus.APROVADA_FIDELIDADE, o.status());
        assertEquals(4.0, o.score());
    }

    @Test
    void semReferenciaNaoRecebeFidelidadeAlta() {
        var o = ReviewRules.fidelity(all(4), 0, 0.0);
        assertEquals(ReviewStatus.EM_REVISAO, o.status());
        assertEquals(2.0, o.score(), "limitada a 2 sem evidência");
    }

    @Test
    void falhaGraveReprovaMesmoComMediaAlta() {
        List<Criterion> c = all(4);
        c.set(4, new Criterion(2, "duas alternativas podem ser defendidas"));   // resposta não única
        assertEquals(ReviewStatus.REPROVADA, ReviewRules.fidelity(c, 2, 0.1).status());
        List<Criterion> zero = all(4);
        zero.set(2, new Criterion(0, "falta o dado necessário para responder"));
        assertEquals(ReviewStatus.REPROVADA, ReviewRules.fidelity(zero, 2, 0.1).status());
    }

    @Test
    void justificativaVaziaNaoAprova() {
        List<Criterion> c = all(4);
        c.set(0, new Criterion(4, "ok"));
        assertEquals(ReviewStatus.EM_REVISAO, ReviewRules.fidelity(c, 1, 0.1).status());
    }

    @Test
    void duplicataOuQuaseDuplicataReprova() {
        String ref = "Considere a Lei Complementar 101/2000 e assinale a alternativa correta sobre limites de despesa com pessoal dos Estados";
        String copy = "Considere a Lei Complementar 101/2000 e assinale a alternativa correta sobre os limites de despesa com pessoal dos Estados";
        double s = ReviewRules.similarity(ref, copy);
        assertTrue(s >= ReviewRules.DUPLICATE_THRESHOLD, "similaridade " + s);
        assertEquals(ReviewStatus.REPROVADA, ReviewRules.fidelity(all(4), 2, s).status());
        assertTrue(ReviewRules.similarity(ref, "Em Python, qual o resultado de len de uma lista vazia") < 0.2);
    }

    @Test
    void regraJuridicaDesatualizadaNaoEAprovada() {
        // Precatórios: prazo anterior à EC 136/2025 usado como vigente.
        var o = ReviewRules.material(ContentType.LEGISLACAO, "C", "C", List.of(), false, null,
                List.of(source(SourceState.ALTERADA, true)), false, null);
        assertEquals(ReviewStatus.REPROVADA, o.status());
        assertTrue(o.issues().get(0).contains("alterada"));
    }

    @Test
    void regraHistoricaComMarcoTemporalEAceita() {
        var o = ReviewRules.material(ContentType.LEGISLACAO, "C", "C", List.of(), false, null,
                List.of(source(SourceState.ALTERADA, true), source(SourceState.VIGENTE, true)), true,
                "regra vigente em 2024, antes da EC 136/2025");
        assertEquals(ReviewStatus.APROVADA_CORRECAO, o.status());
    }

    @Test
    void divergenciaComOGabaritoReprova() {
        var o = ReviewRules.material(ContentType.CONCEITO_ESTAVEL, "B", "D", List.of(), false, null, List.of(),
                false, null);
        assertEquals(ReviewStatus.REPROVADA, o.status());
        assertEquals(ReviewStatus.REPROVADA, ReviewRules.material(ContentType.CONCEITO_ESTAVEL, "B", "B",
                List.of("B", "E"), false, null, List.of(), false, null).status(), "outra defensável");
    }

    @Test
    void fonteIndisponivelFicaPendenteENuncaAprovada() {
        var o = ReviewRules.material(ContentType.LEGISLACAO, "A", "A", List.of(), false, null,
                List.of(source(SourceState.INDISPONIVEL, true)), false, null);
        assertEquals(ReviewStatus.PENDENTE_FONTE, o.status(), "falha de conexão não vira validação nem reprovação");
        assertTrue(o.issues().get(0).contains("indisponível"));
    }

    @Test
    void legislacaoExigeFontePrimariaComDispositivoEData() {
        assertEquals(ReviewStatus.PENDENTE_FONTE, ReviewRules.material(ContentType.LEGISLACAO, "A", "A", List.of(),
                false, null, List.of(source(SourceState.VIGENTE, false)), false, null).status(), "secundária");
        assertEquals(ReviewStatus.APROVADA_CORRECAO, ReviewRules.material(ContentType.LEGISLACAO, "A", "A",
                List.of(), false, null, List.of(source(SourceState.VIGENTE, true)), false, null).status());
        assertEquals(ReviewStatus.APROVADA_CORRECAO, ReviewRules.material(ContentType.CONCEITO_ESTAVEL, "A", "a",
                null, false, null, null, false, null).status(), "conceito estável não exige fonte externa");
    }

    @Test
    void calculoPrecisaSerRefeito() {
        assertEquals(ReviewStatus.PENDENTE_FONTE, ReviewRules.material(ContentType.DADOS_ESTATISTICA_ML, "A", "A",
                List.of(), true, null, List.of(), false, null).status());
        assertEquals(ReviewStatus.APROVADA_CORRECAO, ReviewRules.material(ContentType.DADOS_ESTATISTICA_ML, "A", "A",
                List.of(), true, "média = (2+4)/2 = 3", List.of(), false, null).status());
    }

    @Test
    void estadoGeralSoAprovaComAsDuasRevisoesEDentroDoEdital() {
        assertEquals(ReviewStatus.APROVADA, ReviewRules.overall(ReviewStatus.APROVADA_FIDELIDADE,
                ReviewStatus.APROVADA_CORRECAO, false));
        assertEquals(ReviewStatus.EM_REVISAO, ReviewRules.overall(ReviewStatus.APROVADA_FIDELIDADE,
                ReviewStatus.APROVADA_CORRECAO, true), "fora do edital");
        assertEquals(ReviewStatus.REPROVADA, ReviewRules.overall(ReviewStatus.APROVADA_FIDELIDADE,
                ReviewStatus.REPROVADA, false));
        assertEquals(ReviewStatus.PENDENTE_FONTE, ReviewRules.overall(ReviewStatus.APROVADA_FIDELIDADE,
                ReviewStatus.PENDENTE_FONTE, false));
        assertEquals(ReviewStatus.GERADA, ReviewRules.overall(null, null, false));
    }
}
