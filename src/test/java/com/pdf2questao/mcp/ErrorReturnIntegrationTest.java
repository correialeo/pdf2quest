package com.pdf2questao.mcp;

import com.pdf2questao.Pdf2QuestaoApplication;
import com.pdf2questao.aula.LessonService;
import com.pdf2questao.aula.QuizAttempt;
import com.pdf2questao.aula.QuizAttemptRepository;
import com.pdf2questao.aula.QuizQuestion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Volta dos erros: o erro de quiz com 2+ dias vira um passo da tela Hoje e sai dela quando e respondido de novo. */
@SpringBootTest(classes = Pdf2QuestaoApplication.class)
@AutoConfigureMockMvc
class ErrorReturnIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("test.db"));
        registry.add("pdf2questao.pdf-dir", () -> tempDir.resolve("pdfs").toString());
    }

    @Autowired
    LessonMcpTools tools;
    @Autowired
    LessonService lessonService;
    @Autowired
    QuizAttemptRepository attempts;
    @Autowired
    MockMvc mvc;

    @Test
    void erroAntigoVoltaNoHojeESaiQuandoRespondidoDeNovo() throws Exception {
        var quiz = tools.createQuiz(null, null, "AFO", "Créditos adicionais", "basico", null, null,
                List.of(LessonMcpToolsIntegrationTest.item("Créditos", "B"),
                        LessonMcpToolsIntegrationTest.item("Créditos", "B"),
                        LessonMcpToolsIntegrationTest.item("Créditos", "B")), null, null, null, null, null);
        List<QuizQuestion> qs = lessonService.questions(quiz.quizId());
        answer(quiz.quizId(), qs.get(0), "A");
        answer(quiz.quizId(), qs.get(1), "A");
        answer(quiz.quizId(), qs.get(2), "B");
        LocalDate today = LocalDate.now();

        // Erro de hoje ainda não volta: responder de novo no mesmo dia só mede memória curta.
        var fresh = lessonService.errorReturn(today);
        assertFalse(fresh.pendente());
        assertEquals(0, fresh.vencidos());
        mvc.perform(get("/hoje")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Erros para rever hoje"))));

        backdate(qs.get(0), 3);   // já pode voltar
        backdate(qs.get(1), 1);   // ainda não
        var due = lessonService.errorReturn(today);
        assertEquals(List.of(qs.get(0).getId()), due.hoje().stream().map(e -> e.pergunta().getId()).toList());
        assertEquals(1, due.vencidos());
        assertEquals(0, due.feitosHoje());
        mvc.perform(get("/hoje")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Erros para rever hoje")))
                .andExpect(content().string(containsString("/aulas/erros?hoje=true")));
        mvc.perform(get("/aulas/erros").param("hoje", "true")).andExpect(status().isOk())
                .andExpect(content().string(containsString("data-id=\"" + qs.get(0).getId() + "\"")))
                .andExpect(content().string(not(containsString("data-id=\"" + qs.get(1).getId() + "\""))));
        // A caixa completa continua mostrando os dois erros.
        mvc.perform(get("/aulas/erros")).andExpect(status().isOk())
                .andExpect(content().string(containsString("data-id=\"" + qs.get(1).getId() + "\"")));

        // Errou de novo: sai do passo de hoje (volta em 2 dias), mas conta como revisto.
        answer(quiz.quizId(), qs.get(0), "A");
        var after = lessonService.errorReturn(today);
        assertTrue(after.concluido());
        assertEquals(0, after.vencidos());
        assertEquals(1, after.feitosHoje());
        mvc.perform(get("/hoje")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Erros de hoje revistos (1)")));
        mvc.perform(get("/aulas/erros").param("hoje", "true")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Erros de hoje revistos (1).")));
    }

    @Test
    void cotaDoDiaLimitaOsErrosQueVoltam() throws Exception {
        int total = LessonService.ERRORS_PER_DAY + 3;
        var quiz = tools.createQuiz(null, null, "Contabilidade", "CPC 00", "basico", null, null,
                java.util.stream.IntStream.range(0, total)
                        .mapToObj(i -> LessonMcpToolsIntegrationTest.item("CPC 00", i % 2 == 0 ? "B" : "C")).toList(),
                null, null, null, null, null);
        List<QuizQuestion> qs = lessonService.questions(quiz.quizId());
        for (QuizQuestion q : qs) {
            answer(quiz.quizId(), q, "A");
            backdate(q, 5);
        }
        // O banco é o mesmo do outro teste: o que já foi revisto hoje desconta da cota.
        var due = lessonService.errorReturn(LocalDate.now());
        assertEquals(LessonService.ERRORS_PER_DAY - due.feitosHoje(), due.hoje().size());
        assertTrue(due.vencidos() >= total);

        // Acertou um: sai da caixa e a cota do dia encolhe em vez de puxar outro.
        answer(quiz.quizId(), due.hoje().get(0).pergunta(), due.hoje().get(0).pergunta().getCorrectAnswer());
        var after = lessonService.errorReturn(LocalDate.now());
        assertEquals(due.hoje().size() - 1, after.hoje().size());
        assertEquals(due.feitosHoje() + 1, after.feitosHoje());
    }

    private void answer(Long quizId, QuizQuestion q, String letter) throws Exception {
        mvc.perform(post("/quizzes/" + quizId + "/responder").contentType("application/json")
                .content("{\"questionId\":" + q.getId() + ",\"selected\":\"" + letter + "\"}")).andExpect(status().isOk());
    }

    /** Leva as respostas da pergunta para {@code days} dias atrás. */
    private void backdate(QuizQuestion q, int days) {
        List<QuizAttempt> list = attempts.findByQuizQuestionIdInOrderByAnsweredAt(List.of(q.getId()));
        list.forEach(a -> a.setAnsweredAt(a.getAnsweredAt().minusDays(days)));
        attempts.saveAll(list);
    }
}
