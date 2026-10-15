package com.pdf2questao.study;

import com.pdf2questao.Pdf2QuestaoApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sobe a aplicacao, grava pagina/tarefa/anotacao, desliga e sobe de novo no mesmo arquivo de banco. */
class StudyPersistenceRestartTest {

    @TempDir
    Path tempDir;

    private ConfigurableApplicationContext start() {
        // Argumentos de linha de comando, e nao .properties(): estes valem menos que o application.properties
        // e o teste gravaria no banco de verdade (pdf2questao.db).
        return new SpringApplicationBuilder(Pdf2QuestaoApplication.class)
                .run("--spring.datasource.url=jdbc:sqlite:" + tempDir.resolve("restart.db"),
                        "--pdf2questao.pdf-dir=" + tempDir.resolve("pdfs"), "--server.port=0");
    }

    @Test
    void dadosContinuamDepoisDeReiniciar() {
        try (ConfigurableApplicationContext app = start()) {
            String url = app.getEnvironment().getProperty("spring.datasource.url");
            assertEquals("jdbc:sqlite:" + tempDir.resolve("restart.db"), url);
        }
        Long pageId;
        Long taskId;
        try (ConfigurableApplicationContext app = start()) {
            StudyPageService pages = app.getBean(StudyPageService.class);
            StudyPage page = pages.create(null, "Resumo", null, null);
            pages.saveContent(page.getId(), "Resumo de Licitações", "<p>Lei 14.133</p>", 0);
            pageId = page.getId();
            taskId = app.getBean(StudyTaskService.class).create(new StudyTaskService.TaskForm("Estudar",
                    null, null, LocalDate.of(2026, 10, 20), 60, null, pageId)).getId();
            app.getBean(InboxService.class).add("lembrar de revisar", null);
        }
        try (ConfigurableApplicationContext app = start()) {
            StudyPage page = app.getBean(StudyPageService.class).find(pageId).orElseThrow();
            assertEquals("Resumo de Licitações", page.getTitle());
            assertEquals("<p>Lei 14.133</p>", page.getContent());
            assertEquals(1, page.getVersion());
            StudyTask task = app.getBean(StudyTaskRepository.class).findById(taskId).orElseThrow();
            assertEquals(pageId, task.getPageId());
            assertEquals(LocalDate.of(2026, 10, 20), task.getPlannedDate());
            assertTrue(app.getBean(InboxService.class).all().stream().anyMatch(n -> n.getContent().equals("lembrar de revisar")));
        }
    }
}
