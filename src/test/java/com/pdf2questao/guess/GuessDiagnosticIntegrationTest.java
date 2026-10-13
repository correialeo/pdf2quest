package com.pdf2questao.guess;

import com.pdf2questao.Pdf2QuestaoApplication;
import com.pdf2questao.guess.GuessDiagnostic.Report;
import com.pdf2questao.importer.ImportJob;
import com.pdf2questao.importer.ImportJobRepository;
import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionRepository;
import com.pdf2questao.session.AttemptRepository;
import com.pdf2questao.session.StudySession;
import com.pdf2questao.session.StudySessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Pdf2QuestaoApplication.class)
@AutoConfigureMockMvc
class GuessDiagnosticIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("guess.db"));
        registry.add("pdf2questao.pdf-dir", () -> tempDir.resolve("pdfs").toString());
    }

    @Autowired GuessDiagnosticService service;
    @Autowired StudySessionService sessionService;
    @Autowired QuestionRepository questionRepository;
    @Autowired ImportJobRepository importJobRepository;
    @Autowired AttemptRepository attemptRepository;
    @Autowired MockMvc mvc;

    @BeforeEach
    void clearAttempts() {
        attemptRepository.deleteAll();   // os testes dividem o banco; cada um parte sem respostas
    }

    private List<Question> questions(ImportJob job, int count, String subject) {
        List<Question> list = new ArrayList<>();
        for (int n = 1; n <= count; n++) {
            Question q = new Question();
            q.setImportJobId(job.getId());
            q.setQuestionNumber(n);
            q.setStatement("Enunciado " + subject + " " + n);
            q.setAlternativeA("alt a");
            q.setAlternativeB("alt b");
            q.setCorrectAnswer("A");
            q.setSubject(subject);
            list.add(questionRepository.save(q));
        }
        return list;
    }

    private ImportJob job(String title) {
        ImportJob job = new ImportJob();
        job.setBanca("FCC");
        job.setTitle(title);
        job.setFileName(title + ".pdf");
        job.setImportedQuestions(5);
        job.setStartedAt(LocalDateTime.now());
        return importJobRepository.save(job);
    }

    @Test
    void diagnosticoSeparaChutesDeAcertosSegurosUsandoAUltimaRespostaDeCadaQuestao() throws Exception {
        ImportJob jobA = job("Prova A");
        ImportJob jobB = job("Prova B");
        List<Question> a = questions(jobA, 5, "Português");
        List<Question> b = questions(jobB, 2, "Matemática");

        StudySession s1 = sessionService.startRefazer(List.of(a.get(0).getId(), a.get(1).getId(), a.get(2).getId(),
                a.get(3).getId(), a.get(4).getId(), b.get(0).getId()));
        sessionService.answer(s1.getId(), a.get(0).getId(), "A", 1000L, true);    // acertou no chute
        sessionService.answer(s1.getId(), a.get(1).getId(), "A", 1000L, false);   // acertou seguro
        sessionService.answer(s1.getId(), a.get(2).getId(), "B", 1000L, true);    // errou no chute
        sessionService.answer(s1.getId(), a.get(3).getId(), "B", 1000L, false);   // errou seguro
        sessionService.answer(s1.getId(), a.get(4).getId(), "A", 1000L);          // sem marcacao
        sessionService.answer(s1.getId(), b.get(0).getId(), "A", 1000L, true);    // acertou no chute (outra prova)

        Report all = service.report(null);
        assertEquals(1, all.counts().safeCorrect());
        assertEquals(2, all.counts().guessCorrect());
        assertEquals(1, all.counts().guessWrong());
        assertEquals(1, all.counts().safeWrong());
        assertEquals(1, all.counts().unknown());
        assertEquals(5, all.counts().informed());
        assertEquals(3 * 100.0 / 5, all.counts().rawPercent(), 0.001);
        assertEquals(1 * 100.0 / 5, all.counts().realPercent(), 0.001);
        assertEquals(2 * 100.0 / 3, all.counts().luckySharePercent(), 0.001);
        assertEquals(List.of(b.get(0).getId(), a.get(0).getId()),
                all.lucky().stream().map(l -> l.question().getId()).toList(), "ordenado por disciplina");
        // Mais acertos no chute primeiro; empate desempata pelo nome.
        assertEquals(List.of("Matemática", "Português"), all.subjects().stream().map(r -> r.label()).toList());
        assertEquals(1, all.subjects().get(0).counts().guessCorrect());
        assertEquals(1, all.subjects().get(1).counts().guessCorrect());
        assertEquals(1, all.subjects().get(1).counts().safeCorrect());
        assertEquals(2, all.subjects().size(), "disciplina so com respostas sem marcacao nao aparece");

        Report onlyB = service.report(jobB.getId());
        assertEquals(1, onlyB.counts().guessCorrect());
        assertEquals(1, onlyB.lucky().size());
        assertEquals(2, onlyB.jobs().size() , "o filtro mostra todas as provas com chutes");

        // Responder de novo SEM informar o chute mantem o que estava marcado.
        sessionService.answer(s1.getId(), a.get(0).getId(), "A", 2000L);
        assertEquals(true, attemptRepository.findBySessionIdAndQuestionId(s1.getId(), a.get(0).getId())
                .orElseThrow().getGuessed());

        // Refazer sabendo (desmarca o chute) tira a questao da lista de estudo: vale a ultima resposta.
        Thread.sleep(5);
        StudySession s2 = sessionService.startRefazer(List.of(a.get(0).getId()));
        sessionService.answer(s2.getId(), a.get(0).getId(), "A", 800L, false);
        Report after = service.report(null);
        assertEquals(1, after.counts().guessCorrect());
        assertEquals(2, after.counts().safeCorrect());
        assertEquals(List.of(b.get(0).getId()), service.luckyQuestionIds(null));
    }

    @Test
    void fluxoCompletoNaTelaResponderComChuteVoltarVerDiagnosticoERefazer() throws Exception {
        ImportJob job = job("Prova C");
        List<Question> qs = questions(job, 2, "Direito");
        StudySession session = sessionService.startRefazer(List.of(qs.get(0).getId(), qs.get(1).getId()));

        // A tela de resolver tem o checkbox e nada marcado antes de responder.
        mvc.perform(get("/resolver/" + session.getId()).param("index", "0")).andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"guessCheck\"")))
                .andExpect(content().string(containsString("É chute?")));
        assertNull(attemptRepository.findBySessionIdAndQuestionId(session.getId(), qs.get(0).getId()).orElse(null));

        // Responde marcando chute (corpo JSON, como o navegador envia).
        mvc.perform(post("/resolver/" + session.getId() + "/responder").contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":" + qs.get(0).getId() + ",\"selectedAnswer\":\"A\",\"elapsedMillis\":900,\"guess\":true}"))
                .andExpect(status().isOk());
        // E a segunda sem chute, e sem o campo (cliente antigo).
        mvc.perform(post("/resolver/" + session.getId() + "/responder").contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":" + qs.get(1).getId() + ",\"selectedAnswer\":\"B\",\"elapsedMillis\":900}"))
                .andExpect(status().isOk());
        assertNull(attemptRepository.findBySessionIdAndQuestionId(session.getId(), qs.get(1).getId()).orElseThrow().getGuessed());

        // Voltando a questao, a alternativa e o chute voltam marcados.
        String html = mvc.perform(get("/resolver/" + session.getId()).param("index", "0")).andReturn()
                .getResponse().getContentAsString();
        assertTrue(html.matches("(?s).*id=\"optA\"[^>]*checked=\"checked\".*"), "alternativa A reposta");
        assertTrue(html.matches("(?s).*id=\"guessCheck\"[^>]*checked=\"checked\".*"), "chute reposto");
        assertTrue(!html.matches("(?s).*id=\"optB\"[^>]*checked=\"checked\".*"));

        sessionService.finish(session.getId());
        mvc.perform(get("/resultado/" + session.getId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Acertou no chute: vale revisar o assunto.")))
                .andExpect(content().string(containsString("Chutes (acerto / erro)")))
                .andExpect(content().string(containsString("data-guess=\"true\"")));

        for (String url : new String[]{"/chutes", "/chutes?prova=" + job.getId(), "/dashboard",
                "/dashboard/prova/" + job.getId()}) {
            mvc.perform(get(url)).andExpect(status().isOk());
        }
        mvc.perform(get("/chutes?prova=" + job.getId())).andExpect(content().string(containsString("Enunciado Direito 1")))
                .andExpect(content().string(containsString("Resposta certa")));
        mvc.perform(post("/chutes/refazer").param("prova", job.getId().toString()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("/resolver/*?index=0"));
    }
}
