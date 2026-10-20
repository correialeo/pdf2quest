package com.pdf2questao.mcp;

import com.pdf2questao.Pdf2QuestaoApplication;
import com.pdf2questao.study.StudyPageRepository;
import com.pdf2questao.study.StudyTask;
import com.pdf2questao.study.StudyTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ferramentas MCP de ponta a ponta: treino registrado aparece nos erros e no desempenho; rotina do dia. */
@SpringBootTest(classes = Pdf2QuestaoApplication.class)
class StudyMcpToolsIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("test.db"));
        registry.add("pdf2questao.pdf-dir", () -> tempDir.resolve("pdfs").toString());
    }

    @Autowired
    StudyMcpTools tools;
    @Autowired
    StudyTaskService taskService;
    @Autowired
    StudyPageRepository pageRepository;

    @Test
    void treinoRegistradoEntraNosErrosENoDesempenho() {
        var result = tools.recordTraining("Treino AFO", "FCC", List.of(
                new StudyMcpTools.TrainingQuestion("Anualidade?", List.of("a", "b", "c"), "C", "B", false,
                        "AFO", "Princípios", "Porque sim.", null),
                new StudyMcpTools.TrainingQuestion("LOA so tem receita e despesa.", null, "Certo", "Certo", true,
                        "AFO", null, null, null),
                new StudyMcpTools.TrainingQuestion("Sem alternativa valida", List.of("a", "b"), "Z", "A", null,
                        "AFO", null, null, null)));

        assertNull(result.erro());
        assertEquals(2, result.questoesNovas());
        assertEquals(1, result.acertos());
        assertEquals(1, result.erros());
        assertTrue(result.avisos().stream().anyMatch(a -> a.contains("Questão 3")));

        var errors = tools.listErrors("afo", 5);
        assertEquals(1, errors.size());
        assertEquals("B", errors.get(0).suaResposta());
        assertEquals("C", errors.get(0).respostaCorreta());
        assertEquals("Porque sim.", errors.get(0).explicacao());
        assertTrue(tools.listErrors("portugues", 5).isEmpty());

        var afo = tools.subjectPerformance().stream().filter(s -> s.disciplina().equals("AFO")).findFirst().orElseThrow();
        assertEquals(2, afo.respondidas());
        assertEquals(50.0, afo.percentual());
    }

    @Test
    void rotinaDoDiaConcluiRevisaEAnexaAoResumo() {
        LocalDate today = LocalDate.now();
        StudyTask task = taskService.create(new StudyTaskService.TaskForm("Princípios orçamentários", null, "AFO",
                today, 60, null, null));
        assertTrue(tools.todayTasks().stream().anyMatch(t -> t.id().equals(task.getId())));

        var done = tools.completeTask(task.getId(), 50);
        assertEquals(50, done.minutos());
        assertEquals(today.plusDays(1), done.proximaRevisao());

        var marked = tools.markReview(task.getId(), true);
        assertEquals(today.plusDays(3), marked.proximaRevisao());

        var summary = tools.appendToDaySummary("## Treino\n- ponto **chave**", null);
        String content = pageRepository.findById(summary.paginaId()).orElseThrow().getContent();
        assertTrue(content.endsWith("<h2>Treino</h2><ul><li>ponto <strong>chave</strong></li></ul>"), content);
        tools.appendToDaySummary("mais", today.toString());
        assertTrue(pageRepository.findById(summary.paginaId()).orElseThrow().getContent().endsWith("<p>mais</p>"));
    }
}
