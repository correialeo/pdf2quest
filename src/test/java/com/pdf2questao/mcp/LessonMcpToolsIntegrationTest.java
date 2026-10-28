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
import static org.junit.jupiter.api.Assertions.assertNull;
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
                "## Créditos\n> A FCC troca **especial** e **suplementar**\n```\nLei 4.320, art. 41\n```", "[Lei 4.320, art. 41](https://www.planalto.gov.br/ccivil_03/leis/l4320.htm)", null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> tools.createQuiz(lesson.aulaId(), null, null, null, null,
                null, null, List.of(new QuizItem("x", null,
                        List.of(new QuizAlternative("a", ""), new QuizAlternative("b", "ok")), "A")), null, null, null),
                "toda alternativa precisa de explicação");
        var quiz1 = tools.createQuiz(lesson.aulaId(), null, null, null, "basico", null, null,
                List.of(item("Créditos especiais", "B"), item("Créditos suplementares", "B")), null, null, null);
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
                "prova", "confundo especial com suplementar", null, List.of(item("Créditos especiais", "B")), null, null, null),
                "sem retomar os erros anteriores");
        var quiz2 = tools.createQuiz(lesson.aulaId(), null, null, null, "prova", "confundo especial com suplementar",
                List.of(errors.get(0).perguntaId()), List.of(item("Créditos especiais", "B")), null, null, null);
        assertEquals("Quiz 2", quiz2.titulo());
        assertEquals(2, tools.listLessons(lesson.aulaId(), null).get(0).quizzes().size());
        mvc.perform(get("/quizzes/" + quiz2.quizId())).andExpect(content().string(containsString("Retoma erros anteriores")))
                .andExpect(content().string(containsString("confundo especial com suplementar")));
        answer(quiz2.quizId(), lessonService.questions(quiz2.quizId()).get(0).getId(), "B");
        assertTrue(tools.previousErrors(lesson.aulaId(), null, null).isEmpty(), "acertou: o erro saiu da lista");

        // Quiz avulso (sem aula) por disciplina/tópico; também retoma erros do mesmo tópico.
        var loose = tools.createQuiz(null, null, "AFO", "Créditos suplementares", "intermediario", null, null,
                List.of(item("Créditos suplementares", "A")), null, null, null);
        assertEquals(null, tools.quizResult(loose.quizId()).aulaId());
        assertThrows(IllegalArgumentException.class, () -> tools.createQuiz(null, null, null, null, null, null, null,
                List.of(item("x", "A")), null, null, null), "avulso precisa de assunto");

        var perf = tools.quizPerformance(null);
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

        assertThrows(IllegalArgumentException.class, () -> tools.createLesson("x", "AFO", null, "y", "fonte", 999_999L, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> tools.createLesson("x", "AFO", null, "y", " ", taskId, null, null, null));
        // Fonte sem link é recusada.
        assertThrows(IllegalArgumentException.class,
                () -> tools.createLesson("x", "AFO", null, "y", "Lei 4.320 (planalto.gov.br)", taskId, null, null, null));
        // Fonte "de memória" ou "só resumo" não entra em 'fontes'; o que não foi confirmado exige 'tentativas'.
        String link = "[Lei 4.320](https://www.planalto.gov.br/ccivil_03/leis/l4320.htm)";
        assertThrows(IllegalArgumentException.class, () -> tools.createLesson("x", "AFO", null, "y",
                link + "\nart. 43 de memória", taskId, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> tools.createLesson("x", "AFO", null, "y",
                link, taskId, null, "art. 43, §3º", null));
        assertThrows(IllegalArgumentException.class, () -> tools.createLesson("x", "AFO", null, "y",
                link, taskId, null, "art. 43, §3º", "não abriu"));
        var comPendencia = tools.createLesson("Com pendência", "AFO", "Receita", "y", link, null, null,
                "art. 43, §3º",
                "Tentei o Planalto (resumo apenas), baixei o PDF da UFSM e extraí o texto com pdftotext.");
        assertTrue(lessonService.find(comPendencia.aulaId()).getContent()
                .contains("### Não confirmado"));
        var lesson = tools.createLesson("Receita pública", "AFO", "Receita", "Estágios da receita",
                "[Lei 4.320](https://www.planalto.gov.br/ccivil_03/leis/l4320.htm)", taskId, null, null, null);
        var quiz = tools.createQuiz(lesson.aulaId(), null, null, null, null, null, null,
                List.of(item("Receita", "B")), null, null, null);
        var loose = tools.createQuiz(null, "Quiz de receita", "AFO", "Receita corrente", null, null, null,
                List.of(item("Receita corrente", "B")), null, null, null);
        tools.linkTask(taskId, null, loose.quizId());

        var linked = lessonService.forTask(taskId);
        assertEquals(2, linked.size(), "o quiz da aula aparece dentro dela, o avulso à parte");
        assertEquals("Aula", linked.get(0).tipo());
        assertEquals("0 de 1 quizzes respondidos", linked.get(0).andamento());
        assertEquals("Quiz de receita", linked.get(1).titulo());

        answer(loose.quizId(), lessonService.questions(loose.quizId()).get(0).getId(), "B");
        assertEquals("1/1 acertos", lessonService.forTask(taskId).get(1).andamento());
        var view = studyTools.todayTasks(null, null).stream().filter(t -> t.id().equals(taskId)).findFirst().orElseThrow();
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

    @Test
    void quizCertoErradoUsaCeEEDescontaOErro() throws Exception {
        var ce = tools.createQuiz(null, "C/E de ML", "TI", "Validação cruzada", "prova", null, null, List.of(
                new QuizItem("Vazamento de dados infla a métrica de validação.", null, List.of(
                        new QuizAlternative("", "Certo: o modelo viu informação do teste."),
                        new QuizAlternative("", "Errado não: o item está correto.")), "Certo"),
                new QuizItem("k-fold dispensa o conjunto de teste.", null, List.of(
                        new QuizAlternative("", "Errado: o teste final continua separado."),
                        new QuizAlternative("", "Errado: o teste final continua separado.")), "E")),
                null, null, "certo_errado");
        var qs = lessonService.questions(ce.quizId());
        assertEquals("C", qs.get(0).getCorrectAnswer());
        assertEquals("E", qs.get(1).getCorrectAnswer());
        assertEquals("Certo", qs.get(0).alternativeList().get(0).texto());
        assertThrows(IllegalArgumentException.class, () -> tools.addQuiz(ce.quizId(), List.of(new QuizItem("x", null,
                List.of(new QuizAlternative("", "só uma")), "C"))), "C/E precisa das duas explicações");

        mvc.perform(get("/quizzes/" + ce.quizId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("um erro anula um acerto")));
        answer(ce.quizId(), qs.get(0).getId(), "C");
        answer(ce.quizId(), qs.get(1).getId(), "C");
        var result = tools.quizResult(ce.quizId());
        assertEquals("certo_errado", result.formato());
        assertEquals(0, result.liquido(), "1 certo - 1 errado");
    }

    @Test
    void certoErradoAceitaEmBrancoQueNaoPontuaNemAnulaAcerto() throws Exception {
        var ce = tools.createQuiz(null, "C/E em branco", "TI", "Estado", "prova", null, null, List.of(
                new QuizItem("Item 1.", null, List.of(new QuizAlternative("", "Certo."),
                        new QuizAlternative("", "Errado.")), "C"),
                new QuizItem("Item 2.", null, List.of(new QuizAlternative("", "Certo."),
                        new QuizAlternative("", "Errado.")), "E"),
                new QuizItem("Item 3.", null, List.of(new QuizAlternative("", "Certo."),
                        new QuizAlternative("", "Errado.")), "C")), null, null, "certo_errado");
        var qs = lessonService.questions(ce.quizId());

        mvc.perform(get("/quizzes/" + ce.quizId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("quiz-blank")));
        answer(ce.quizId(), qs.get(0).getId(), "C");   // acerto
        answer(ce.quizId(), qs.get(1).getId(), "-");   // em branco
        var parcial = tools.quizResult(ce.quizId());
        assertEquals(2, parcial.respondidas());
        assertEquals(1, parcial.acertos());
        assertEquals(1, parcial.brancos());
        assertEquals(1, parcial.liquido(), "o branco nao anula o acerto");
        assertFalse(parcial.concluido());

        answer(ce.quizId(), qs.get(2).getId(), "E");   // erro
        var result = tools.quizResult(ce.quizId());
        assertTrue(result.concluido(), "em branco conta como respondido");
        assertEquals(0, result.liquido(), "1 certo - 1 errado, o branco nao conta");
        var branco = result.questoes().get(1);
        assertTrue(branco.emBranco());
        assertNull(branco.acertou());
        assertEquals("em branco", branco.suaResposta());

        // O item em branco volta para revisao como lacuna, marcado como "em branco".
        var erros = tools.previousErrors(null, "TI", "Estado");
        assertTrue(erros.stream().anyMatch(e -> "em branco".equals(e.suaResposta())), erros.toString());

        // Na multipla escolha nao existe "em branco".
        var mc = tools.createQuiz(null, "MC", "Redes", "Camadas", "prova", null, null, List.of(
                new QuizItem("Pergunta?", null, List.of(new QuizAlternative("A", "a"),
                        new QuizAlternative("B", "b")), "A")), null, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> lessonService.answer(lessonService.questions(mc.quizId()).get(0).getId(), "-"));
    }
}
