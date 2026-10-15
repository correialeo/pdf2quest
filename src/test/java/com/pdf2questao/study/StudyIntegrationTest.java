package com.pdf2questao.study;

import com.pdf2questao.Pdf2QuestaoApplication;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Paginas, cronograma e caixa de entrada gravando num SQLite temporario. */
@SpringBootTest(classes = Pdf2QuestaoApplication.class)
class StudyIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("test.db"));
        registry.add("pdf2questao.pdf-dir", () -> tempDir.resolve("pdfs").toString());
    }

    @Autowired
    StudyPageService pageService;
    @Autowired
    StudyTaskService taskService;
    @Autowired
    InboxService inboxService;
    @Autowired
    StudyPageRepository pageRepository;
    @Autowired
    StudyTaskRepository taskRepository;

    @Test
    void criaEditaPaginaESalvaConteudoComVersao() {
        StudyPage page = pageService.create(null, "  Atos administrativos ", "Direito Administrativo", null);
        assertEquals("Atos administrativos", page.getTitle());
        assertEquals(0, page.getVersion());

        var saved = pageService.saveContent(page.getId(), "Atos – resumo",
                "<h2>Atributos</h2><ul data-checklist><li data-checked=\"true\">PATI</li></ul><script>x</script>", 0);
        assertEquals(1, saved.version());
        StudyPage reloaded = pageRepository.findById(page.getId()).orElseThrow();
        assertEquals("Atos – resumo", reloaded.getTitle());
        assertEquals("<h2>Atributos</h2><ul data-checklist><li data-checked=\"true\">PATI</li></ul>", reloaded.getContent());

        // Outra aba com a versao antiga nao sobrescreve.
        var stale = assertThrows(StudyPageService.StaleVersionException.class,
                () -> pageService.saveContent(page.getId(), "velho", "<p>velho</p>", 0));
        assertEquals(1, stale.getCurrentVersion());
        assertEquals("Atos – resumo", pageRepository.findById(page.getId()).orElseThrow().getTitle());
    }

    @Test
    void subpaginasArvoreMoverEExcluirEmCascata() {
        StudyPage root = pageService.create(null, "Constitucional", null, null);
        StudyPage child = pageService.create(root.getId(), "Direitos fundamentais", null, null);
        StudyPage grandchild = pageService.create(child.getId(), "Remédios", null, null);
        StudyPage other = pageService.create(null, "Outra", null, null);

        var node = pageService.tree().stream().filter(n -> n.page().getId().equals(root.getId())).findFirst().orElseThrow();
        assertEquals(child.getId(), node.children().get(0).page().getId());
        assertEquals(2, node.children().get(0).children().get(0).depth());
        assertEquals(List.of(root.getId(), child.getId(), grandchild.getId()),
                pageService.breadcrumbs(grandchild).stream().map(StudyPage::getId).toList());

        // Nao da para colocar uma pagina dentro da propria subpagina.
        assertThrows(IllegalArgumentException.class, () -> pageService.updateDetails(root.getId(), null, grandchild.getId()));
        pageService.updateDetails(other.getId(), "Português", child.getId());
        assertEquals(child.getId(), pageRepository.findById(other.getId()).orElseThrow().getParentId());

        StudyTask linked = taskService.create(new StudyTaskService.TaskForm("Revisar remédios", null, null,
                LocalDate.now(), 30, null, grandchild.getId()));

        assertEquals(4, pageService.delete(root.getId()));
        assertTrue(pageRepository.findById(grandchild.getId()).isEmpty());
        assertTrue(pageRepository.findById(other.getId()).isEmpty());
        // A tarefa continua, sem o vinculo com a pagina apagada.
        StudyTask after = taskRepository.findById(linked.getId()).orElseThrow();
        assertNull(after.getPageId());
    }

    @Test
    void reordenaPaginasIrmas() {
        StudyPage mother = pageService.create(null, "Mae", null, null);
        StudyPage a = pageService.create(mother.getId(), "A", null, null);
        StudyPage b = pageService.create(mother.getId(), "B", null, null);
        pageService.move(b.getId(), -1);
        assertEquals(List.of(b.getId(), a.getId()), pageService.children(mother.getId()).stream().map(StudyPage::getId).toList());
        pageService.move(b.getId(), -1);
        assertEquals(List.of(b.getId(), a.getId()), pageService.children(mother.getId()).stream().map(StudyPage::getId).toList());
    }

    @Test
    void tarefasCriarConcluirReabrirEditarEExcluir() {
        LocalDate today = LocalDate.of(2026, 10, 15);
        StudyTask overdue = taskService.create(new StudyTaskService.TaskForm("Atrasada", null, "Português",
                today.minusDays(2), 50, null, null));
        StudyTask todayTask = taskService.create(new StudyTaskService.TaskForm("Hoje", "ler", null, today, 30, null, null));
        StudyTask undated = taskService.create(new StudyTaskService.TaskForm("Sem data", null, null, null, null, null, null));

        var groups = taskService.groups(today);
        var labels = groups.stream().map(StudyTaskService.TaskGroup::label).toList();
        assertTrue(labels.get(0).equals("Atrasada · Ter, 13/10"), labels.toString());
        assertEquals(today.minusDays(2), groups.get(0).date());
        assertTrue(labels.contains("Hoje · qui, 15/10"), labels.toString());
        assertTrue(labels.contains("Sem data"));

        StudyTask done = taskService.setDone(todayTask.getId(), true, 45);
        assertTrue(done.isDone());
        assertNotNull(done.getDoneAt());
        assertEquals(45, done.getSpentMinutes());
        assertTrue(taskService.groups(today).stream().anyMatch(g -> g.kind().equals("done")
                && g.tasks().stream().anyMatch(t -> t.getId().equals(todayTask.getId()))));

        StudyTask reopened = taskService.setDone(todayTask.getId(), false, null);
        assertFalse(reopened.isDone());
        assertNull(reopened.getDoneAt());
        assertEquals(45, reopened.getSpentMinutes());

        taskService.update(undated.getId(), new StudyTaskService.TaskForm("Com data agora", "nova descrição",
                "Matemática", today.plusDays(1), 20, 10, null));
        StudyTask edited = taskRepository.findById(undated.getId()).orElseThrow();
        assertEquals(today.plusDays(1), edited.getPlannedDate());
        assertEquals("nova descrição", edited.getDescription());
        assertEquals(10, edited.getSpentMinutes());

        assertThrows(IllegalArgumentException.class, () -> taskService.create(
                new StudyTaskService.TaskForm(" ", null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> taskService.create(
                new StudyTaskService.TaskForm("x", null, null, null, -5, null, null)));
        assertThrows(IllegalArgumentException.class, () -> taskService.create(
                new StudyTaskService.TaskForm("x", null, null, null, null, null, 999999L)));

        taskService.delete(overdue.getId());
        assertTrue(taskRepository.findById(overdue.getId()).isEmpty());
    }

    @Test
    void caixaDeEntradaViraPagina() {
        assertThrows(IllegalArgumentException.class, () -> inboxService.add("   ", null));
        InboxNote note = inboxService.add("Revisar art. 37\nprincípios LIMPE\n\n<b>ver</b> súmula", null);
        inboxService.setSubject(note.getId(), "Direito Administrativo");

        StudyPage page = inboxService.toPage(note.getId(), null);
        assertEquals("Revisar art. 37", page.getTitle());
        assertEquals("Direito Administrativo", page.getSubject());
        assertEquals("<p>princípios LIMPE</p><p>&lt;b&gt;ver&lt;/b&gt; súmula</p>", page.getContent());
        assertTrue(inboxService.all().stream().noneMatch(n -> n.getId().equals(note.getId())));
    }

    @Test
    void descricaoQueSoRepeteOBlocoNaoApareceNaLista() {
        StudyTask task = new StudyTask();
        task.setDescription("B – TI (90 min)");
        assertNull(task.getListDescription());
        task.setDescription("Ler com calma (90 min)");
        assertEquals("Ler com calma (90 min)", task.getListDescription());
    }
}
