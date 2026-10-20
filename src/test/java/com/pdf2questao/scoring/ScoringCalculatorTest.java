package com.pdf2questao.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdf2questao.scoring.ScoringCalculator.GroupSpec;
import com.pdf2questao.scoring.ScoringCalculator.Item;
import com.pdf2questao.scoring.ScoringCalculator.ProfileSpec;
import com.pdf2questao.scoring.ScoringCalculator.RuleSpec;
import com.pdf2questao.scoring.ScoringCalculator.ScoreResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoringCalculatorTest {

    private static final ProfileSpec PROFILE = new ProfileSpec("Teste", 10.0, List.of(
            new GroupSpec("P1", 4.0, List.of(
                    new RuleSpec("Português", Set.of("Língua Portuguesa"), 1, 5),
                    new RuleSpec("Contabilidade", Set.of(), 2, 5))),
            new GroupSpec("P2", 6.0, List.of(
                    new RuleSpec("TI", Set.of(), 3, 4)))));

    private static List<Item> items(String subject, int correct, int wrong) {
        List<Item> list = new ArrayList<>();
        for (int i = 0; i < correct; i++) {
            list.add(new Item(subject, true, false));
        }
        for (int i = 0; i < wrong; i++) {
            list.add(new Item(subject, false, false));
        }
        return list;
    }

    @Test
    void pontosPorDisciplinaTotalPonderadoEMinimos() {
        List<Item> all = new ArrayList<>();
        all.addAll(items("Português", 3, 2));
        all.addAll(items("Contabilidade", 2, 3));
        all.addAll(items("TI", 2, 2));

        ScoreResult result = ScoringCalculator.calculate(PROFILE, all);

        assertEquals(3 + 4, result.groups().get(0).points());
        assertEquals(5 + 10, result.groups().get(0).maxPoints());
        assertEquals(6, result.groups().get(1).points());
        assertEquals(13, result.total());
        assertEquals(27, result.maxTotal());
        assertTrue(result.groups().get(0).minMet());
        assertTrue(result.groups().get(1).minMet());
        assertTrue(result.minTotalMet());
        assertTrue(result.passed());
        assertEquals(3, result.groups().get(0).rules().get(0).correct());
        assertEquals(5, result.groups().get(0).rules().get(0).questions());
    }

    @Test
    void naoBaterMinimoDeUmGrupoReprovaMesmoComTotalSuficiente() {
        List<Item> all = new ArrayList<>();
        all.addAll(items("Português", 1, 4));
        all.addAll(items("TI", 4, 0));

        ScoreResult result = ScoringCalculator.calculate(PROFILE, all);

        assertEquals(13, result.total());
        assertTrue(result.minTotalMet());
        assertFalse(result.groups().get(0).minMet());
        assertFalse(result.passed());
    }

    @Test
    void naoBaterOMinimoTotalReprova() {
        ScoreResult result = ScoringCalculator.calculate(PROFILE, items("TI", 2, 2).stream().toList());

        assertEquals(6, result.total());
        assertFalse(result.minTotalMet());
        assertFalse(result.passed());
    }

    @Test
    void questaoAnuladaContaPontoParaTodosRespondidaOuNao() {
        List<Item> all = List.of(
                new Item("Contabilidade", false, true),   // errou, mas anulada
                new Item("Contabilidade", null, true),    // nao respondeu, anulada
                new Item("Contabilidade", null, false),   // nao respondeu, valida
                new Item("Contabilidade", true, false));

        ScoreResult result = ScoringCalculator.calculate(PROFILE, all);

        assertEquals(3, result.groups().get(0).rules().get(1).correct());
        assertEquals(4, result.groups().get(0).rules().get(1).questions());
        assertEquals(6, result.groups().get(0).points());
    }

    @Test
    void projetaOAproveitamentoParaAsQuestoesDoEdital() {
        // Portugues 2/4 (edital 5 x peso 1) e Contabilidade nao caiu; TI 1/2 (edital 4 x peso 3).
        List<Item> all = new ArrayList<>();
        all.addAll(items("Português", 2, 2));
        all.addAll(items("TI", 1, 1));

        ScoreResult result = ScoringCalculator.calculate(PROFILE, all);

        var p1 = result.groups().get(0);
        assertEquals(2.5, p1.rules().get(0).projected());
        assertEquals(null, p1.rules().get(1).projected());
        assertEquals(5, p1.coveredMaxPoints());
        assertEquals(7.5, p1.projectedPoints(), 1e-9);   // 50% do maximo do grupo (15)
        assertTrue(p1.projectedMinMet());
        assertEquals(6, result.groups().get(1).projectedPoints(), 1e-9);
        assertEquals(13.5, result.projectedTotal(), 1e-9);
        assertTrue(result.projectedPassed());
        assertEquals(17.0 / 27, result.coverage(), 1e-9);
    }

    @Test
    void grupoComMinimoQueNaoCaiuDeixaOPassariaSemResposta() {
        ScoreResult result = ScoringCalculator.calculate(PROFILE, items("TI", 4, 0));

        assertEquals(null, result.groups().get(0).projectedPoints());
        assertTrue(result.groups().get(1).projectedMinMet());
        assertEquals(null, result.projectedPassed());
    }

    @Test
    void erroNaoDescontaESemRespostaNaoPontua() {
        ScoreResult result = ScoringCalculator.calculate(PROFILE, List.of(
                new Item("TI", false, false), new Item("TI", null, false)));

        assertEquals(0, result.total());
    }

    @Test
    void apelidoCasaIgnorandoAcentoECaixaEDisciplinaSemRegraNaoPontua() {
        ScoreResult result = ScoringCalculator.calculate(PROFILE, List.of(
                new Item("LINGUA PORTUGUESA", true, false),
                new Item("Direção", true, false),
                new Item(null, true, false)));

        assertEquals(1, result.total());
        assertEquals(2, result.unweightedSubjects().size());
        assertEquals(1, result.unweightedSubjects().get("Direção"));
        assertEquals(1, result.unweightedSubjects().get("(sem disciplina)"));
    }

    @Test
    void perfilDeExemploTemOsMaximosDoEdital() throws Exception {
        JsonNode root = new ObjectMapper().readTree(ScoringProfileService.EXAMPLE_JSON);
        double[] max = new double[2];
        int[] questions = new int[2];
        int g = 0;
        for (JsonNode group : root.get("groups")) {
            for (JsonNode rule : group.get("rules")) {
                max[g] += rule.get("questionCount").asInt() * rule.get("weight").asDouble();
                questions[g] += rule.get("questionCount").asInt();
            }
            g++;
        }

        assertEquals("SEFAZ-SP APOFP 2026 – C03", root.get("name").asText());
        assertEquals(112, max[0]);
        assertEquals(80, questions[0]);
        assertEquals(45, root.get("groups").get(0).get("minScore").asInt());
        assertEquals(120, max[1]);
        assertEquals(48, root.get("groups").get(1).get("minScore").asInt());
        assertEquals(116, root.get("minTotal").asInt());
    }
}
