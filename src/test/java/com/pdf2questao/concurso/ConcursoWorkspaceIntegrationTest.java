package com.pdf2questao.concurso;

import com.pdf2questao.Pdf2QuestaoApplication;
import com.pdf2questao.aula.LessonRepository;
import com.pdf2questao.mcp.ConcursoMcpTools;
import com.pdf2questao.mcp.InsightMcpTools;
import com.pdf2questao.mcp.LessonMcpTools;
import com.pdf2questao.mcp.StudyMcpTools;
import com.pdf2questao.study.DaySummaryService;
import com.pdf2questao.study.SpacedRepetitionService;
import com.pdf2questao.study.StudyPage;
import com.pdf2questao.study.StudyTask;
import com.pdf2questao.study.StudyTaskRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    @Autowired
    DaySummaryService daySummaryService;
    @Autowired
    SpacedRepetitionService spacedRepetition;
    @Autowired
    StudyTaskRepository taskRepository;

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
        var aula = lessonTools.createLesson("ADI e ADC", "Constitucional", "Controle concentrado",
                com.pdf2questao.mcp.LessonMcpToolsIntegrationTest.SLIDES,
                "[CF/88, art. 102](https://www.planalto.gov.br/ccivil_03/constituicao/constituicao.htm)", hoje.get(0).id(),
                null, null, null, com.pdf2questao.mcp.LessonMcpToolsIntegrationTest.fixacao(),
                com.pdf2questao.mcp.LessonMcpToolsIntegrationTest.ESTILO,
                com.pdf2questao.mcp.LessonMcpToolsIntegrationTest.REFS, null);
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

    @Test
    void editalAnexadoFicaGuardadoNoConcurso() throws Exception {
        Concurso cgu = concurso("CGU AFFC", "CGU");
        Path pdf = tempDir.resolve("edital.pdf");
        java.nio.file.Files.write(pdf, "%PDF-1.4 edital".getBytes());
        Path txt = tempDir.resolve("nao-e-pdf.pdf");
        java.nio.file.Files.write(txt, "texto".getBytes());

        var anexado = concursoTools.attachEdital(pdf.toString(), "cgu");
        assertEquals(cgu.getId(), anexado.concursoId());
        assertTrue(anexado.link().endsWith("/concursos/" + cgu.getId() + "/edital.pdf"));
        assertTrue(java.nio.file.Files.isRegularFile(Path.of(anexado.caminho())));
        assertThrows(IllegalArgumentException.class, () -> concursoTools.attachEdital(txt.toString(), "cgu"));
    }

    @Test
    void resumoDoDiaEPorConcursoComPastaESetasProprias() {
        Concurso sefaz = concurso("SEFAZ Resumo", "Sefaz");
        Concurso cgu = concurso("CGU Resumo", "CGU");
        LocalDate day = LocalDate.of(2027, 5, 12);
        workspace.select(sefaz.getId());
        taskService.create(new StudyTaskService.TaskForm("Contabilidade", null, "Contab", day, 60, null, null));
        workspace.select(cgu.getId());
        taskService.create(new StudyTaskService.TaskForm("Estado e democracia", null, "P1", day, 40, null, null));

        // Cada concurso tem o seu resumo do dia, com so as tarefas dele, numa pasta propria.
        StudyPage doCgu = daySummaryService.open(day);
        assertEquals(cgu.getId(), doCgu.getConcursoId());
        assertTrue(doCgu.getContent().contains("Estado e democracia"), doCgu.getContent());
        assertFalse(doCgu.getContent().contains("Contabilidade"), doCgu.getContent());

        workspace.select(sefaz.getId());
        StudyPage doSefaz = daySummaryService.open(day);
        assertNotEquals(doCgu.getId(), doSefaz.getId());
        assertNotEquals(doCgu.getParentId(), doSefaz.getParentId());
        assertEquals(sefaz.getId(), doSefaz.getConcursoId());
        assertTrue(doSefaz.getContent().contains("Contabilidade") && !doSefaz.getContent().contains("Estado"));
        assertEquals(java.util.Map.of(day, doSefaz.getId()), daySummaryService.existing(day, day));
        assertEquals(doSefaz.getId(), daySummaryService.open(day).getId());

        // As setas e os botoes do cronograma so enxergam os resumos do mesmo concurso.
        assertTrue(daySummaryService.next(day.minusDays(1), sefaz.getId()).map(StudyPage::getId)
                .filter(doSefaz.getId()::equals).isPresent());
        assertTrue(daySummaryService.next(day, sefaz.getId()).isEmpty());
        assertTrue(daySummaryService.previous(day.plusDays(1), cgu.getId()).map(StudyPage::getId)
                .filter(doCgu.getId()::equals).isPresent());
        workspace.select(cgu.getId());
        assertEquals(java.util.Map.of(day, doCgu.getId()), daySummaryService.existing(day, day));

        // anexar_ao_resumo_do_dia no concurso pedido, sem trocar o ativo da tela.
        var anexado = studyTools.appendToDaySummary("nota da Sefaz", day.toString(), "sefaz resumo");
        assertEquals(doSefaz.getId(), anexado.paginaId());
        assertEquals(cgu.getId(), workspace.selectedId());
    }

    /** Conclui a tarefa e a deixa com a revisao vencendo hoje. */
    private StudyTask dueToday(Concurso concurso, String title, String subject) {
        workspace.select(concurso.getId());
        StudyTask t = taskService.create(new StudyTaskService.TaskForm(title, null, subject, LocalDate.now(), 30, null, null));
        taskService.setDone(t.getId(), true, null);
        StudyTask saved = taskRepository.findById(t.getId()).orElseThrow();
        saved.setNextReviewDate(LocalDate.now());
        return taskRepository.save(saved);
    }

    @Test
    void revisaoDoDiaEPorConcursoENaoMisturaOsAssuntos() {
        Concurso alfa = concurso("Revisao Alfa", "Alfa");
        Concurso beta = concurso("Revisao Beta", "Beta");
        LocalDate today = LocalDate.now();
        StudyTask a = dueToday(alfa, "Assunto so do Alfa", "Contab");
        StudyTask b = dueToday(beta, "Assunto so do Beta", "Estatistica");

        spacedRepetition.syncReviewTask(today);
        spacedRepetition.syncReviewTask(today);

        // Cada workspace enxerga so os seus assuntos e a sua tarefa de revisao (uma por concurso).
        workspace.select(alfa.getId());
        assertEquals(java.util.List.of(a.getId()), spacedRepetition.due(today).stream().map(StudyTask::getId).toList());
        var reviewsAlfa = taskService.dueBy(today).stream().filter(StudyTask::isReview).toList();
        assertEquals(1, reviewsAlfa.size());
        assertEquals("Revisão · 1 assunto", reviewsAlfa.get(0).getTitle());
        assertTrue(reviewsAlfa.get(0).getDescription().contains("Assunto so do Alfa"));
        assertFalse(reviewsAlfa.get(0).getDescription().contains("Beta"));
        assertEquals(alfa.getId(), concursoService.concursoOfTask(reviewsAlfa.get(0)));

        workspace.select(beta.getId());
        var reviewsBeta = taskService.dueBy(today).stream().filter(StudyTask::isReview).toList();
        assertEquals(1, reviewsBeta.size());
        assertNotEquals(reviewsAlfa.get(0).getId(), reviewsBeta.get(0).getId());
        assertTrue(reviewsBeta.get(0).getDescription().contains("Assunto so do Beta"));
        assertFalse(reviewsBeta.get(0).getDescription().contains("Alfa"));

        // As ferramentas MCP respeitam o concurso pedido, sem trocar o ativo da tela.
        var mcpAlfa = studyTools.todayReviews(String.valueOf(alfa.getId()));
        assertEquals(java.util.List.of(a.getId()), mcpAlfa.stream().map(StudyMcpTools.ReviewView::id).toList());
        assertEquals(beta.getId(), workspace.selectedId());
        assertEquals(0, studyTools.markReview(a.getId(), true).revisoesRestantesHoje());
        assertEquals(1, spacedRepetition.dueFor(beta.getId(), today).size());

        // Concluir a revisao de um concurso nao avanca os assuntos do outro.
        workspace.select(alfa.getId());
        spacedRepetition.syncReviewTask(today);
        workspace.select(beta.getId());
        var reviewBeta = taskService.dueBy(today).stream().filter(StudyTask::isReview).findFirst().orElseThrow();
        taskService.setDone(reviewBeta.getId(), true, null);
        assertEquals(today.plusDays(3), taskRepository.findById(b.getId()).orElseThrow().getNextReviewDate());
        assertEquals(2, taskRepository.findById(b.getId()).orElseThrow().getCurrentStep());
        assertEquals(today.plusDays(3), taskRepository.findById(a.getId()).orElseThrow().getNextReviewDate(),
                "o assunto do Alfa ja tinha sido marcado antes e nao anda de novo");
    }
}
