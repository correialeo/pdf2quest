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

    private void answer(Long quizId, Long questionId, String letter) throws Exception {
        mvc.perform(post("/quizzes/" + quizId + "/responder").contentType("application/json")
                .content("{\"questionId\":" + questionId + ",\"selected\":\"" + letter + "\"}"));
    }

    @Test
    void aulaComVariosQuizzesQueRetomamOsErros() throws Exception {
        long attemptsBefore = attemptRepository.count();
        var lesson = tools.createLesson("Créditos adicionais", "AFO", "Créditos adicionais",
                "## Créditos\n> A FCC troca **especial** e **suplementar**\n```\nLei 4.320, art. 41\n```", null);
        assertThrows(IllegalArgumentException.class, () -> tools.createQuiz(lesson.aulaId(), null, null, null, null,
                null, null, List.of(new QuizItem("x", null,
                        List.of(new QuizAlternative("a", ""), new QuizAlternative("b", "ok")), "A")), null),
                "toda alternativa precisa de explicação");
        var quiz1 = tools.createQuiz(lesson.aulaId(), null, null, null, "basico", null, null,
                List.of(item("Créditos especiais", "B"), item("Créditos suplementares", "B")), null);
        assertEquals("Quiz 1", quiz1.titulo());
        var questions = lessonService.questions(quiz1.quizId());

        mvc.perform(get("/aulas/" + lesson.aulaId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("<blockquote>")))
                .andExpect(content().string(containsString("Quiz 1")));
        mvc.perform(get("/quizzes/" + quiz1.quizId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Certa: despesa sem dotação específica.")));
        mvc.perform(post("/quizzes/" + quiz1.quizId() + "/responder").contentType("application/json")
                        .content("{\"questionId\":" + questions.get(0).getId() + ",\"selected\":\"a\"}"))
                .andExpect(jsonPath("$.correct").value(false)).andExpect(jsonPath("$.correctAnswer").value("B"))
                .andExpect(jsonPath("$.finished").value(false));
        answer(quiz1.quizId(), questions.get(1).getId(), "B");

        // Corrige a correta da pergunta 1 para A: a resposta "a" já dada passa a valer como acerto.
        var fixed = tools.fixQuizQuestion(quiz1.quizId(), 1, null, null, null, List.of("Certa agora.", "Errada.",
                "Errada."), "A");
        assertEquals("A", fixed.correta());
        assertEquals(2, tools.quizResult(quiz1.quizId()).acertos());
        assertThrows(IllegalArgumentException.class, () -> tools.fixQuizQuestion(quiz1.quizId(), 1, null, null, null,
                null, "Z"));
        tools.fixQuizQuestion(quiz1.quizId(), 1, null, null, null, List.of("Errada: reforça dotação já existente.",
                "Certa: despesa sem dotação específica.", "Errada: despesa urgente e imprevisível."), "B");

        var result = tools.quizResult(null);
        assertEquals(2, result.respondidas());
        assertEquals(1, result.acertos());
        assertTrue(result.concluido());
        assertEquals("Errada: reforça dotação já existente.", result.questoes().get(0).explicacaoDaSuaResposta());

        // Segundo quiz da mesma aula: precisa retomar o erro do primeiro.
        var errors = tools.previousErrors(lesson.aulaId(), null, null);
        assertEquals(1, errors.size());
        assertEquals("A", errors.get(0).suaResposta());
        assertThrows(IllegalArgumentException.class, () -> tools.createQuiz(lesson.aulaId(), null, null, null,
                "prova", "confundo especial com suplementar", null, List.of(item("Créditos especiais", "B")), null),
                "sem retomar os erros anteriores");
        var quiz2 = tools.createQuiz(lesson.aulaId(), null, null, null, "prova", "confundo especial com suplementar",
                List.of(errors.get(0).perguntaId()), List.of(item("Créditos especiais", "B")), null);
        assertEquals("Quiz 2", quiz2.titulo());
        assertEquals(2, tools.listLessons(lesson.aulaId()).get(0).quizzes().size());
        mvc.perform(get("/quizzes/" + quiz2.quizId())).andExpect(content().string(containsString("Retoma erros anteriores")))
                .andExpect(content().string(containsString("confundo especial com suplementar")));
        answer(quiz2.quizId(), lessonService.questions(quiz2.quizId()).get(0).getId(), "B");
        assertTrue(tools.previousErrors(lesson.aulaId(), null, null).isEmpty(), "acertou: o erro saiu da lista");

        // Quiz avulso (sem aula) por disciplina/tópico; também retoma erros do mesmo tópico.
        var loose = tools.createQuiz(null, null, "AFO", "Créditos suplementares", "intermediario", null, null,
                List.of(item("Créditos suplementares", "A")), null);
        assertEquals(null, tools.quizResult(loose.quizId()).aulaId());
        assertThrows(IllegalArgumentException.class, () -> tools.createQuiz(null, null, null, null, null, null, null,
                List.of(item("x", "A")), null), "avulso precisa de assunto");

        var perf = tools.quizPerformance();
        assertEquals(2, perf.geral().acertos());
        assertEquals(1, perf.geral().erros());
        assertEquals(3, perf.geral().quizzes());
        assertEquals(attemptsBefore, attemptRepository.count(), "quiz não entra nas métricas de prova/simulado");

        // Refazer: a tela volta em branco, o histórico continua
        mvc.perform(post("/quizzes/" + quiz1.quizId() + "/refazer"));
        assertTrue(lessonService.latest(quiz1.quizId()).isEmpty());
        assertFalse(tools.quizResult(quiz1.quizId()).concluido());
        mvc.perform(get("/aulas")).andExpect(status().isOk()).andExpect(content().string(containsString("Créditos adicionais")))
                .andExpect(content().string(containsString("Quizzes avulsos")))
                .andExpect(content().string(containsString("Aproveitamento")));
        mvc.perform(get("/hoje")).andExpect(status().isOk()).andExpect(content().string(containsString("Créditos adicionais")));
    }

    @Autowired
    com.pdf2questao.study.StudyTaskRepository taskRepository;
    @Autowired
    StudyMcpTools studyTools;

    @Test
    void aulaEQuizLigadosATarefaAparecemNaTarefaENoHoje() throws Exception {
        var task = new com.pdf2questao.study.StudyTask();
        task.setTitle("AFO – Receita pública");
        task.setPlannedDate(java.time.LocalDate.now());
        task = taskRepository.save(task);
        Long taskId = task.getId();

        assertThrows(IllegalArgumentException.class, () -> tools.createLesson("x", "AFO", null, "y", 999_999L));
        var lesson = tools.createLesson("Receita pública", "AFO", "Receita", "Estágios da receita", taskId);
        var quiz = tools.createQuiz(lesson.aulaId(), null, null, null, null, null, null,
                List.of(item("Receita", "B")), null);
        var loose = tools.createQuiz(null, "Quiz de receita", "AFO", "Receita corrente", null, null, null,
                List.of(item("Receita corrente", "B")), null);
        tools.linkTask(taskId, null, loose.quizId());

        var linked = lessonService.forTask(taskId);
        assertEquals(2, linked.size(), "o quiz da aula aparece dentro dela, o avulso à parte");
        assertEquals("Aula", linked.get(0).tipo());
        assertEquals("0 de 1 quizzes respondidos", linked.get(0).andamento());
        assertEquals("Quiz de receita", linked.get(1).titulo());

        answer(loose.quizId(), lessonService.questions(loose.quizId()).get(0).getId(), "B");
        assertEquals("1/1 acertos", lessonService.forTask(taskId).get(1).andamento());
        var view = studyTools.todayTasks().stream().filter(t -> t.id().equals(taskId)).findFirst().orElseThrow();
        assertEquals(2, view.aulasEQuizzes().size());

        mvc.perform(get("/hoje")).andExpect(content().string(containsString("Quiz: Quiz de receita")));
        mvc.perform(get("/estudos/cronograma/" + taskId + "/editar"))
                .andExpect(content().string(containsString("Aula: Receita pública")));
        mvc.perform(get("/quizzes/" + quiz.quizId())).andExpect(content().string(containsString("AFO – Receita pública")));

        tools.linkTask(null, null, loose.quizId());
        assertEquals(1, lessonService.forTask(taskId).size());
    }

    @Test
    void telaDeSimuladosListaBlocosEProvasEIniciaPersonalizado() throws Exception {
        mvc.perform(get("/simulado")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Simulados")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Dataprev"))));
    }
}
