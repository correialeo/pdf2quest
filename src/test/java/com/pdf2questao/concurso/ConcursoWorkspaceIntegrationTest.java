package com.pdf2questao.concurso;

import com.pdf2questao.Pdf2QuestaoApplication;
import com.pdf2questao.aula.LessonRepository;
import com.pdf2questao.mcp.ConcursoMcpTools;
import com.pdf2questao.mcp.InsightMcpTools;
import com.pdf2questao.mcp.LessonMcpTools;
import com.pdf2questao.mcp.StudyMcpTools;
import com.pdf2questao.study.StudyTask;
import com.pdf2questao.study.StudyTaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Concurso como workspace: cronograma padrao, filtro das tarefas e o parametro "concurso" das ferramentas MCP. */
@SpringBootTest(classes = Pdf2QuestaoApplication.class)
class ConcursoWorkspaceIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("test.db"));
        registry.add("pdf2questao.pdf-dir", () -> tempDir.resolve("pdfs").toString());
    }

    @Autowired
    ConcursoService concursoService;
    @Autowired
    Workspace workspace;
    @Autowired
    StudyTaskService taskService;
    @Autowired
    LessonRepository lessonRepository;
    @Autowired
    ConcursoMcpTools concursoTools;
    @Autowired
    StudyMcpTools studyTools;
    @Autowired
    LessonMcpTools lessonTools;
    @Autowired
    InsightMcpTools insightTools;

    @AfterEach
    void reset() {
        workspace.select(null);
    }

    private Concurso concurso(String nome, String orgao) {
        return concursoService.create(new ConcursoService.ConcursoForm(nome, orgao, "FCC", null,
                LocalDate.now().plusDays(90), null, null, null, null, null, null));
    }

    @Test
    void tarefaNovaEntraNoCronogramaDoConcursoAtivoESoApareceNele() {
        Concurso sefaz = concurso("SEFAZ-SP 2026 AFR", "Sefaz");
        Concurso trt = concurso("TRT-2 Analista", "TRT");
        assertEquals("Geral", concursoService.defaultCronograma(sefaz.getId()).getNome());

        workspace.select(sefaz.getId());
        StudyTask a = taskService.create(new StudyTaskService.TaskForm("ICMS", null, "Tributário", LocalDate.now(),
                60, null, null));
        workspace.select(trt.getId());
        StudyTask b = taskService.create(new StudyTaskService.TaskForm("CLT", null, "Trabalho", LocalDate.now(),
                60, null, null));

        assertEquals(sefaz.getId(), concursoService.concursoOfTask(a));
        assertTrue(taskService.dueBy(LocalDate.now()).stream().noneMatch(t -> t.getId().equals(a.getId())));
        assertTrue(taskService.dueBy(LocalDate.now()).stream().anyMatch(t -> t.getId().equals(b.getId())));

        workspace.select(null);
        var all = taskService.dueBy(LocalDate.now()).stream().map(StudyTask::getId).toList();
        assertTrue(all.contains(a.getId()) && all.contains(b.getId()));
    }

    @Test
    void mcpMontaAsAulasDeHojeDoConcursoPedido() {
        Concurso pge = concurso("PGE-RJ Procurador", "PGE");
        concurso("Câmara Analista", "Câmara");

        // criar_tarefa no concurso pedido pelo nome, sem trocar o ativo.
        var created = insightTools.createTask("Controle de constitucionalidade", null, "Constitucional", 90, null,
                "pge", null);
        assertEquals(pge.getNome(), created.concurso());
        assertNull(workspace.selectedId());

        var hoje = studyTools.todayTasks("pge", null);
        assertEquals(1, hoje.size());
        assertEquals("Geral", hoje.get(0).cronograma());
        assertTrue(studyTools.todayTasks("câmara", null).isEmpty());

        // A aula herda o concurso da tarefa.
        var aula = lessonTools.createLesson("ADI e ADC", "Constitucional", "Controle concentrado", "## ADI\n...",
                hoje.get(0).id(), null);
        assertEquals(pge.getNome(), aula.concurso());
        assertEquals(pge.getId(), lessonRepository.findById(aula.aulaId()).orElseThrow().getConcursoId());
        assertEquals(1, lessonTools.listLessons(null, "pge").size());
        assertTrue(lessonTools.listLessons(null, "câmara").isEmpty());

        assertThrows(IllegalArgumentException.class, () -> studyTools.todayTasks("inexistente", null));
        assertTrue(concursoTools.listConcursos(null).stream().anyMatch(c -> c.nome().equals(pge.getNome())));
    }

    @Test
    void cronogramaPedidoPeloNomeEApagarConcursoSoDesfazOVinculo() {
        Concurso inss = concurso("INSS Técnico", "INSS");
        var reta = concursoTools.createCronograma("Reta final", "inss", null, null);
        var task = insightTools.createTask("Revisão geral", null, null, null, null, "inss", "reta");
        assertEquals("Reta final", task.cronograma());

        concursoService.delete(inss.getId());
        assertNull(concursoService.cronograma(reta.id()).orElseThrow().getConcursoId());
        assertEquals(reta.id(), taskService.forEdit(task.id()).orElseThrow().getCronogramaId());
    }

    @Test
    void nomeAmbiguoPedeOId() {
        concurso("Prefeitura SP Auditor", "Prefeitura");
        concurso("Prefeitura RJ Auditor", "Prefeitura");
        var e = assertThrows(IllegalArgumentException.class, () -> concursoService.resolve("prefeitura"));
        assertTrue(e.getMessage().contains("Mais de um concurso"));
        assertEquals("Prefeitura SP Auditor", concursoService.resolve("prefeitura sp").getNome());
    }
}
