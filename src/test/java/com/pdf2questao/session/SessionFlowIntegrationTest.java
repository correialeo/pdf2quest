package com.pdf2questao.session;

import com.pdf2questao.Pdf2QuestaoApplication;
import com.pdf2questao.importer.ImportJob;
import com.pdf2questao.importer.ImportJobRepository;
import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionRepository;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Resolver: sessao vazia, sessao finalizada travada, cronometro e recorrecao quando o gabarito chega depois. */
@SpringBootTest(classes = Pdf2QuestaoApplication.class)
@AutoConfigureMockMvc
class SessionFlowIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("test.db"));
        registry.add("pdf2questao.pdf-dir", () -> tempDir.resolve("pdfs").toString());
    }

    @Autowired
    StudySessionService sessionService;
    @Autowired
    QuestionRepository questionRepository;
    @Autowired
    ImportJobRepository importJobRepository;
    @Autowired
    AttemptRepository attemptRepository;
    @Autowired
    StudySessionRepository sessionRepository;
    @Autowired
    MockMvc mvc;

    private List<Question> prova(String answer, int count) {
        ImportJob job = new ImportJob();
        job.setFileName("prova.pdf");
        job.setStartedAt(LocalDateTime.now());
        job = importJobRepository.save(job);
        for (int n = 1; n <= count; n++) {
            Question q = new Question();
            q.setImportJobId(job.getId());
            q.setQuestionNumber(n);
            q.setStatement("Questao " + n);
            q.setAlternativeA("a");
            q.setAlternativeB("b");
            q.setCorrectAnswer(answer);
            questionRepository.save(q);
        }
        return questionRepository.findByImportJobId(job.getId());
    }

    @Test
    void filtroSemQuestoesNaoCriaSessaoEVoltaComAviso() throws Exception {
        long before = sessionRepository.count();
        mvc.perform(post("/questoes/iniciar").param("subject", "Disciplina que nao existe")
                        .param("quantity", "10").header("Referer", "http://localhost/questoes"))
                .andExpect(redirectedUrl("/questoes"))
                .andExpect(flash().attribute("globalError", "Nenhuma questão encontrada com esses filtros."));
        assertEquals(before, sessionRepository.count());
        assertThrows(EmptySessionException.class, () -> sessionService.startRefazer(List.of()));
    }

    @Test
    void sessaoFinalizadaNaoAceitaRespostaEOResolverLevaAoResultado() throws Exception {
        List<Question> qs = prova("A", 2);
        StudySession session = sessionService.startProva(qs.get(0).getImportJobId());
        assertEquals(SessionMode.PROVA, session.getMode());
        sessionService.answer(session.getId(), qs.get(0).getId(), "A", 1000L);
        sessionService.finish(session.getId());
        StudySession again = sessionService.finish(session.getId());   // finalizar de novo nao muda nada

        assertThrows(SessionFinishedException.class,
                () -> sessionService.answer(session.getId(), qs.get(1).getId(), "B", 1000L));
        assertEquals(1, again.getCorrectCount());
        assertNull(again.getNotaDataprev(), "prova completa nao e simulado Dataprev");
        mvc.perform(get("/resolver/" + session.getId())).andExpect(redirectedUrl("/resultado/" + session.getId()));
        mvc.perform(post("/resolver/" + session.getId() + "/responder").contentType("application/json")
                .content("{\"questionId\":" + qs.get(1).getId() + ",\"selectedAnswer\":\"B\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void tempoDaQuestaoESessaoAcumulaEntreVisitas() {
        List<Question> qs = prova("A", 2);
        StudySession session = sessionService.startProva(qs.get(0).getImportJobId());
        Long q = qs.get(0).getId();

        sessionService.answer(session.getId(), q, "B", 3000L, 3000L, false);
        sessionService.addTime(session.getId(), q, 2000L, 2500L);            // saiu da questao depois
        sessionService.answer(session.getId(), q, "A", 1000L, 1000L, null);  // voltou e trocou a resposta
        sessionService.addTime(session.getId(), qs.get(1).getId(), 4000L, 4000L);   // sem resposta: so a sessao

        assertEquals(6000L, attemptRepository.findBySessionIdAndQuestionId(session.getId(), q)
                .orElseThrow().getElapsedMilliseconds());
        StudySession finished = sessionService.finish(session.getId());
        assertEquals(10500L, finished.getTotalElapsedMillis());
    }

    @Test
    void gabaritoQueChegaDepoisRecorrigeAsRespostasEANotaDaSessao() {
        List<Question> qs = prova(null, 3);
        StudySession session = sessionService.startProva(qs.get(0).getImportJobId());
        sessionService.answer(session.getId(), qs.get(0).getId(), "A", 1000L);
        sessionService.answer(session.getId(), qs.get(1).getId(), "B", 1000L);
        StudySession finished = sessionService.finish(session.getId());
        assertEquals(0, finished.getCorrectCount());

        qs.forEach(q -> q.setCorrectAnswer("A"));
        qs.get(2).setAnnulled(true);
        questionRepository.saveAll(qs);
        int recounted = sessionService.regrade(qs.stream().map(Question::getId).toList());

        assertEquals(1, recounted);
        assertTrue(attemptRepository.findBySessionIdAndQuestionId(session.getId(), qs.get(0).getId())
                .orElseThrow().getCorrect());
        StudySession regraded = sessionService.getSession(session.getId());
        assertEquals(2, regraded.getCorrectCount(), "acertou a 1 e a 3 foi anulada");
        assertEquals(1, regraded.getWrongCount());
    }
}
