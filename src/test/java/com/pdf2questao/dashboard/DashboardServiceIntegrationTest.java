package com.pdf2questao.dashboard;

import com.pdf2questao.Pdf2QuestaoApplication;
import com.pdf2questao.block.BlockService;
import com.pdf2questao.block.StudyBlock;
import com.pdf2questao.dashboard.DashboardData.Overview;
import com.pdf2questao.dashboard.DashboardData.ProvaCard;
import com.pdf2questao.dashboard.DashboardData.ProvaPanel;
import com.pdf2questao.dashboard.DashboardData.SubjectProgress;
import com.pdf2questao.importer.ImportJob;
import com.pdf2questao.importer.ImportJobRepository;
import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionRepository;
import com.pdf2questao.session.StudySession;
import com.pdf2questao.session.StudySessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Duas provas de bancas diferentes com a MESMA disciplina ("Portugues") precisam aparecer separadas no
 * dashboard; cada prova e cada bloco tem seu painel. Tambem renderiza as telas (Thymeleaf so acusa erro ao
 * renderizar) e os filtros por prova do caderno de erros e da pratica.
 */
@SpringBootTest(classes = Pdf2QuestaoApplication.class)
@AutoConfigureMockMvc
class DashboardServiceIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("dash.db"));
        registry.add("pdf2questao.pdf-dir", () -> tempDir.resolve("pdfs").toString());
    }

    @Autowired DashboardService dashboardService;
    @Autowired StudySessionService sessionService;
    @Autowired BlockService blockService;
    @Autowired QuestionRepository questionRepository;
    @Autowired ImportJobRepository importJobRepository;
    @Autowired MockMvc mvc;

    private ImportJob job(String banca, String title, String file) {
        ImportJob job = new ImportJob();
        job.setBanca(banca);
        job.setTitle(title);
        job.setFileName(file);
        job.setImportedQuestions(4);
        job.setDetectedYear(2026);
        job.setStartedAt(LocalDateTime.now());
        return importJobRepository.save(job);
    }

    private List<Question> questions(ImportJob job, String subject) {
        List<Question> list = new ArrayList<>();
        for (int n = 1; n <= 4; n++) {
            Question q = new Question();
            q.setImportJobId(job.getId());
            q.setQuestionNumber(n);
            q.setStatement("Questao " + n);
            q.setCorrectAnswer("A");
            q.setSubject(n <= 3 ? subject : "Outra");
            q.setOrganization(job.getBanca());
            list.add(questionRepository.save(q));
        }
        return list;
    }

    @Test
    void provasComAMesmaDisciplinaNaoSeMisturamEOsPaineisFuncionam() throws Exception {
        ImportJob fcc = job("FCC", "Prova 1 – Conhecimentos Gerais", "fcc-p1.pdf");
        ImportJob fgv = job("FGV", null, "fgv-prova.pdf");
        List<Question> a = questions(fcc, "Português");
        List<Question> b = questions(fgv, "Português");

        // Uma sessao de pratica que mistura as duas provas: 2 acertos e 1 erro na FCC, 1 acerto na FGV.
        StudySession session = sessionService.startRefazer(List.of(a.get(0).getId(), a.get(1).getId(),
                a.get(2).getId(), b.get(0).getId()));
        sessionService.answer(session.getId(), a.get(0).getId(), "A", 1000L);
        sessionService.answer(session.getId(), a.get(1).getId(), "A", 1000L);
        sessionService.answer(session.getId(), a.get(2).getId(), "B", 1000L);
        sessionService.answer(session.getId(), b.get(0).getId(), "A", 1000L);
        sessionService.finish(session.getId());

        Overview overview = dashboardService.overview();
        assertEquals(List.of("FCC", "FGV"), overview.groups().stream().map(DashboardData.ProvaGroup::banca).toList());
        ProvaCard cardFcc = overview.groups().get(0).provas().get(0);
        ProvaCard cardFgv = overview.groups().get(1).provas().get(0);
        assertEquals("Prova 1 – Conhecimentos Gerais", cardFcc.job().getDisplayName());
        assertEquals("fgv-prova", cardFgv.job().getDisplayName());
        assertEquals(4, cardFcc.totalQuestions());
        assertEquals(3, cardFcc.answered());
        assertEquals(2, cardFcc.correct());
        assertEquals(1, cardFcc.wrong());
        assertEquals(1, cardFgv.answered());
        assertEquals(1, cardFgv.correct());
        assertEquals(0, cardFgv.wrong());
        assertEquals(1, cardFcc.sessions());

        ProvaPanel panel = dashboardService.prova(fcc.getId());
        SubjectProgress portugues = panel.subjects().get(0);
        assertEquals("Português", portugues.label());
        assertEquals(3, portugues.questions(), "so as 3 questoes de Portugues da FCC, nao as da FGV");
        assertEquals(3, portugues.answered());
        assertEquals(2, portugues.correct());
        assertEquals(1, portugues.wrong());
        assertEquals("Outra", panel.subjects().get(1).label());
        assertEquals(0, panel.subjects().get(1).answered());
        assertEquals(1, panel.errors());
        assertEquals(1, panel.sessions().size());
        assertEquals(2, panel.sessions().get(0).correct());
        assertEquals(1, dashboardService.prova(fgv.getId()).sessions().get(0).correct(), "na FGV so conta o que foi da FGV");

        // Bloco com perfil: sessao finalizada aparece no painel do bloco com a nota.
        Long profileId = null;
        StudyBlock block = blockService.save(blockService.draft(null, "Meu bloco", null, null, profileId,
                List.of(new BlockService.SourceForm(fcc.getId().toString(), "1-3", null, null, null, null, null, false)), ""));
        StudySession blockSession = sessionService.startBloco(block.getId());
        sessionService.answer(blockSession.getId(), a.get(0).getId(), "A", 500L);
        sessionService.finish(blockSession.getId());
        DashboardData.BlockPanel blockPanel = dashboardService.bloco(block.getId());
        assertEquals(3, blockPanel.questions());
        assertEquals(1, blockPanel.sessions().size());
        assertEquals(1, dashboardService.overview().blocks().get(0).sessions());

        // Todas as telas renderizam.
        for (String url : new String[]{"/dashboard", "/dashboard/prova/" + fcc.getId(), "/dashboard/prova/" + fgv.getId(),
                "/dashboard/bloco/" + block.getId(), "/caderno-erros", "/caderno-erros?prova=" + fcc.getId(),
                "/questoes", "/import", "/import/result/" + fcc.getId(), "/import/" + fcc.getId() + "/questoes",
                "/blocos", "/blocos/novo", "/import/gabarito"}) {
            mvc.perform(get(url)).andExpect(status().isOk());
        }
        mvc.perform(get("/dashboard")).andExpect(content().string(org.hamcrest.Matchers.containsString("Prova 1 – Conhecimentos Gerais")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("fgv-prova")));
        mvc.perform(get("/caderno-erros?prova=" + fgv.getId()))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Nenhuma quest")));

        // Pratica so de uma prova, criar bloco da prova, editar dados da prova.
        mvc.perform(post("/questoes/iniciar").param("importJobId", fcc.getId().toString()).param("quantity", "10")
                .param("keepExamOrder", "true")).andExpect(status().is3xxRedirection());
        StudySession practice = sessionService.getSession(
                sessionService.startPractice(null, null, null, null, fgv.getId(), 10, true).getId());
        assertEquals(4, practice.questionIds().size());
        assertTrue(questionRepository.findAllById(practice.questionIds()).stream()
                .allMatch(q -> fgv.getId().equals(q.getImportJobId())));

        mvc.perform(post("/import/" + fgv.getId() + "/bloco")).andExpect(status().is3xxRedirection());
        assertNotNull(blockService.findAll().stream().filter(x -> x.getName().equals("fgv-prova")).findFirst().orElse(null));
        mvc.perform(post("/import/" + fgv.getId() + "/editar").param("title", "FGV Prova X")
                .param("organization", "FGV").param("year", "2024")).andExpect(status().is3xxRedirection());
        assertEquals("FGV Prova X", importJobRepository.findById(fgv.getId()).orElseThrow().getDisplayName());
        assertEquals(2024, questionRepository.findByImportJobId(fgv.getId()).get(0).getYear());
        assertEquals("Questao 1", questionRepository.findByImportJobId(fgv.getId()).get(0).getStatement());
    }
}
