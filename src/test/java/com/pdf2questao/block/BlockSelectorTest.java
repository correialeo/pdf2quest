package com.pdf2questao.block;

import com.pdf2questao.question.Question;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockSelectorTest {

    private final List<Question> pool = new ArrayList<>();
    private long nextId = 1;

    private void exam(long job, String subjectFor1to10, int count) {
        for (int n = 1; n <= count; n++) {
            Question q = new Question();
            q.setId(nextId++);
            q.setImportJobId(job);
            q.setQuestionNumber(n);
            q.setSubject(n <= 10 ? subjectFor1to10 : "Contabilidade Geral");
            q.setOrganization("FCC");
            q.setYear(2026);
            q.setStatement("q");
            pool.add(q);
        }
    }

    private static StudyBlockSource source(Long job, String ranges) {
        StudyBlockSource s = new StudyBlockSource();
        s.setImportJobId(job);
        s.setRanges(ranges);
        return s;
    }

    private static StudyBlockExclusion byNumber(Long job, int number) {
        StudyBlockExclusion e = new StudyBlockExclusion();
        e.setImportJobId(job);
        e.setQuestionNumber(number);
        return e;
    }

    private static List<Integer> numbers(BlockSelector.Selection s) {
        return s.questions().stream().map(Question::getQuestionNumber).toList();
    }

    @Test
    void faixasEscolhemOsNumerosDaProvaNaOrdemDaProva() {
        exam(1, "Português", 30);

        var selection = BlockSelector.select(pool, List.of(source(1L, "8-12, 1-2")), List.of(), new Random(1));

        assertEquals(List.of(1, 2, 8, 9, 10, 11, 12), numbers(selection));
    }

    @Test
    void blocoJuntaVariasProvasNaOrdemDasOrigens() {
        exam(1, "A", 12);
        exam(2, "B", 12);

        var selection = BlockSelector.select(pool, List.of(source(2L, "1-2"), source(1L, "3")), List.of(), new Random(1));

        assertEquals(List.of(2L, 2L, 1L), selection.questions().stream().map(Question::getImportJobId).toList());
        assertEquals(List.of(2, 1), selection.perSource());
    }

    @Test
    void filtroPorDisciplinaPegaDeQualquerProvaComLimiteEEmbaralhar() {
        exam(1, "A", 20);
        exam(2, "B", 20);
        StudyBlockSource filter = new StudyBlockSource();
        filter.setSubject("contabilidade");
        filter.setMaxQuestions(5);
        filter.setShuffle(true);

        var selection = BlockSelector.select(pool, List.of(filter), List.of(), new Random(7));

        assertEquals(5, selection.questions().size());
        assertTrue(selection.questions().stream().allMatch(q -> q.getSubject().equals("Contabilidade Geral")));
    }

    @Test
    void exclusaoPorNumeroPorProvaEPorIdNaoApagaNadaDoBanco() {
        exam(1, "A", 10);
        exam(2, "B", 10);
        StudyBlockExclusion byId = new StudyBlockExclusion();
        byId.setQuestionId(pool.get(0).getId());

        var selection = BlockSelector.select(pool, List.of(source(1L, "1-5"), source(2L, "1-5")),
                List.of(byNumber(2L, 3), byNumber(null, 5), byId), new Random(1));

        // prova 1 perde q1 (por id) e q5 (numero em qualquer prova); prova 2 perde q3 e q5.
        assertEquals(List.of(2, 3, 4), numbers(selection).subList(0, 3));
        assertEquals(List.of(1, 2, 4), numbers(selection).subList(3, 6));
        assertEquals(6, selection.questions().size());
        assertEquals(4, selection.excluded());
        assertEquals(20, pool.size());
    }

    @Test
    void limiteContaSoQuestoesValidasENaoRepeteQuestaoJaEscolhida() {
        exam(1, "A", 10);
        StudyBlockSource first = source(1L, "1-3");
        StudyBlockSource second = source(1L, "2-6");
        second.setMaxQuestions(2);

        var selection = BlockSelector.select(pool, List.of(first, second), List.of(byNumber(1L, 4)), new Random(1));

        assertEquals(List.of(1, 2, 3, 5, 6), numbers(selection));
    }

    @Test
    void avisaFaixaForaDaProvaEExclusaoQueNaoBateComNada() {
        exam(1, "A", 10);

        var selection = BlockSelector.select(pool, List.of(source(1L, "8-14")), List.of(byNumber(1L, 99)), new Random(1));

        assertEquals(List.of(8, 9, 10), numbers(selection));
        assertEquals(2, selection.warnings().size());
        assertTrue(selection.warnings().get(0).contains("11"));
        assertTrue(selection.warnings().get(1).contains("1:99"));
    }
}
