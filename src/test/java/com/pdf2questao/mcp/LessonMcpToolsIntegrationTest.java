package com.pdf2questao.mcp;

import com.pdf2questao.Pdf2QuestaoApplication;
import com.pdf2questao.aula.LessonService;
import com.pdf2questao.mcp.LessonMcpTools.QuizAlternative;
import com.pdf2questao.mcp.LessonMcpTools.QuizItem;
import com.pdf2questao.session.AttemptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Aula com quiz: criada pelo MCP, respondida na tela, resultado e desempenho separados das provas. */
@SpringBootTest(classes = Pdf2QuestaoApplication.class)
@AutoConfigureMockMvc
class LessonMcpToolsIntegrationTest {

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
    AttemptRepository attemptRepository;
    @Autowired
    MockMvc mvc;

    private static QuizItem item(String topic, String correct) {
        return new QuizItem("Crédito para despesa sem dotação específica é:", topic, List.of(
                new QuizAlternative("Suplementar", "Errada: reforça dotação já existente."),
                new QuizAlternative("Especial", "Certa: despesa sem dotação específica."),
                new QuizAlternative("Extraordinário", "Errada: despesa urgente e imprevisível.")), correct);
    }

    @Test
    void aulaQuizRespondidoNaTelaEResultado() throws Exception {
        long attemptsBefore = attemptRepository.count();
        var lesson = tools.createLesson("Créditos adicionais", "AFO", "Créditos adicionais",
                "## Créditos\n> A FCC troca **especial** e **suplementar**\n```\nLei 4.320, art. 41\n```", null);
        assertThrows(IllegalArgumentException.class, () -> tools.addQuiz(lesson.aulaId(), List.of(
                new QuizItem("x", null, List.of(new QuizAlternative("a", ""), new QuizAlternative("b", "ok")), "A"))),
                "toda alternativa precisa de explicação");
        tools.addQuiz(lesson.aulaId(), List.of(item("Créditos especiais", "B"), item("Créditos suplementares", "B")));
        var questions = lessonService.questions(lesson.aulaId());

        mvc.perform(get("/aulas/" + lesson.aulaId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("<blockquote>")))
                .andExpect(content().string(containsString("Certa: despesa sem dotação específica.")));
        mvc.perform(post("/aulas/" + lesson.aulaId() + "/responder").contentType("application/json")
                        .content("{\"questionId\":" + questions.get(0).getId() + ",\"selected\":\"a\"}"))
                .andExpect(jsonPath("$.correct").value(false)).andExpect(jsonPath("$.correctAnswer").value("B"))
                .andExpect(jsonPath("$.finished").value(false));
        mvc.perform(post("/aulas/" + lesson.aulaId() + "/responder").contentType("application/json")
                        .content("{\"questionId\":" + questions.get(1).getId() + ",\"selected\":\"B\"}"))
                .andExpect(jsonPath("$.finished").value(true)).andExpect(jsonPath("$.correctCount").value(1));

        var result = tools.quizResult(null);
        assertEquals(2, result.respondidas());
        assertEquals(1, result.acertos());
        assertTrue(result.concluida());
        assertEquals("Errada: reforça dotação já existente.", result.questoes().get(0).explicacaoDaSuaResposta());
        var stats = tools.quizPerformance();
        assertEquals("Créditos especiais", stats.get(0).topico(), "piores primeiro");
        assertEquals(0.0, stats.get(0).percentual());
        assertEquals(attemptsBefore, attemptRepository.count(), "quiz não entra nas métricas de prova/simulado");

        // Refazer: a tela volta em branco, o histórico continua
        mvc.perform(post("/aulas/" + lesson.aulaId() + "/refazer"));
        assertTrue(lessonService.latest(lesson.aulaId()).isEmpty());
        assertFalse(tools.quizResult(lesson.aulaId()).concluida());
        mvc.perform(get("/aulas")).andExpect(status().isOk()).andExpect(content().string(containsString("Créditos adicionais")));
        mvc.perform(get("/hoje")).andExpect(status().isOk()).andExpect(content().string(containsString("Créditos adicionais")));
    }

    @Test
    void telaDeSimuladosListaBlocosEProvasEIniciaPersonalizado() throws Exception {
        mvc.perform(get("/simulado")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Simulados")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Dataprev"))));
    }
}
