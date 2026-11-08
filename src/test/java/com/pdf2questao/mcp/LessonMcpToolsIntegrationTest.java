package com.pdf2questao.mcp;

import com.pdf2questao.Pdf2QuestaoApplication;
import com.pdf2questao.aula.LessonService;
import com.pdf2questao.aula.TodayFlowService;
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
public class LessonMcpToolsIntegrationTest {

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

    /** Estilo da banca e questões reais lidas: obrigatórios no nível prova (o padrão). */
    public static final String ESTILO = "FCC cobra créditos adicionais com enunciado curto e direto, comando "
            + "\"é correto afirmar\" ou \"EXCETO\", e distratores que trocam especial por suplementar, a autorização "
            + "legislativa e a indicação de recursos; também usa casos concretos de abertura de crédito por decreto, "
            + "pedindo o instrumento correto e o limite, e pegadinhas com a vigência do crédito extraordinário.";
    public static final String REFS = "questão local #1\nhttps://www.pciconcursos.com.br/provas/fcc";

    /** Aula em slides mínima aceita pelo criar_aula: 2 slides (com conceito) e 3 questões de fixação. */
    public static final String SLIDES = "=== Regra {#regra}\nTexto.\n[[questão 1]]\n=== Exceção {#excecao}\nTexto.\n"
            + "[[questão 2]]\n[[questão 3]]";

    public static List<QuizItem> fixacao() {
        return List.of(item("Fixação", "A"), item("Fixação", "B"), item("Fixação", "C"));
    }

    /** Aula de página única (formato antigo), gravada direto no serviço para testar os quizzes dela. */
    private LessonMcpTools.LessonCreated plain(String title, String subject, String topic, String content, Long taskId) {
        var l = lessonService.create(title, subject, topic, content, taskId);
        return new LessonMcpTools.LessonCreated(l.getId(), l.getTitle(), null, 0, 0, null);
    }

    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

    /** Pergunta sempre diferente das anteriores (o sistema recusa repetida); as explicações são fixas. */
    public static QuizItem item(String topic, String correct) {
        int n = SEQ.incrementAndGet();
        String tag = java.util.stream.IntStream.range(0, 12).mapToObj(i -> "termo" + n + "x" + i)
                .collect(java.util.stream.Collectors.joining(" "));
        return new QuizItem("Crédito para despesa sem dotação específica é (" + tag + "):", topic, List.of(
                new QuizAlternative("Suplementar " + tag.substring(0, 20), "Errada: reforça dotação já existente."),
                new QuizAlternative("Especial " + tag.substring(20), "Certa: despesa sem dotação específica."),
                new QuizAlternative("Extraordinário " + tag, "Errada: despesa urgente e imprevisível.")), correct,
                List.of("regra"), null);
    }

    private void answer(Long quizId, Long questionId, String letter) throws Exception {
        mvc.perform(post("/quizzes/" + quizId + "/responder").contentType("application/json")
                .content("{\"questionId\":" + questionId + ",\"selected\":\"" + letter + "\"}"));
    }

    @Test
    void aulaComVariosQuizzesQueRetomamOsErros() throws Exception {
        long attemptsBefore = attemptRepository.count();
        var lesson = plain("Créditos adicionais", "AFO", "Créditos adicionais",
                "## Créditos\n> A FCC troca **especial** e **suplementar**\n```\nLei 4.320, art. 41\n```", null);
        assertThrows(IllegalArgumentException.class, () -> tools.createQuiz(lesson.aulaId(), null, null, null, null,
                null, null, List.of(new QuizItem("x", null,
                        List.of(new QuizAlternative("a", ""), new QuizAlternative("b", "ok")), "A")), null, null, null, ESTILO, REFS),
                "toda alternativa precisa de explicação");
        var quiz1 = tools.createQuiz(lesson.aulaId(), null, null, null, "basico", null, null,
                List.of(item("Créditos especiais", "B"), item("Créditos suplementares", "B")), null, null, null, ESTILO, REFS);
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
                "prova", "confundo especial com suplementar", null, List.of(item("Créditos especiais", "B")), null, null, null, ESTILO, REFS),
                "sem retomar os erros anteriores");
        var quiz2 = tools.createQuiz(lesson.aulaId(), null, null, null, "prova", "confundo especial com suplementar",
                List.of(errors.get(0).perguntaId()), List.of(item("Créditos especiais", "B")), null, null, null, ESTILO, REFS);
        assertEquals("Quiz 2", quiz2.titulo());
        assertEquals(2, tools.listLessons(lesson.aulaId(), null).get(0).quizzes().size());
        mvc.perform(get("/quizzes/" + quiz2.quizId())).andExpect(content().string(containsString("Retoma erros anteriores")))
                .andExpect(content().string(containsString("confundo especial com suplementar")));
        answer(quiz2.quizId(), lessonService.questions(quiz2.quizId()).get(0).getId(), "B");
        assertTrue(tools.previousErrors(lesson.aulaId(), null, null).isEmpty(), "acertou: o erro saiu da lista");

        // Quiz avulso (sem aula) por disciplina/tópico; também retoma erros do mesmo tópico.
        var loose = tools.createQuiz(null, null, "AFO", "Créditos suplementares", "intermediario", null, null,
                List.of(item("Créditos suplementares", "A")), null, null, null, ESTILO, REFS);
        assertEquals(null, tools.quizResult(loose.quizId()).aulaId());
        assertThrows(IllegalArgumentException.class, () -> tools.createQuiz(null, null, null, null, null, null, null,
                List.of(item("x", "A")), null, null, null, ESTILO, REFS), "avulso precisa de assunto");

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
        // Aula sem tarefa nao entra na fila do dia (fica em Aulas).
        mvc.perform(get("/hoje")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Créditos adicionais"))));
    }

    private static QuizItem scrum(String statement, String a, String b) {
        return new QuizItem(statement, "Scrum", List.of(new QuizAlternative(a, "Errada."),
                new QuizAlternative(b, "Certa.")), "B");
    }

    @Test
    void perguntaJaFeitaNoConcursoEhRecusadaEPerguntasJaFeitasMostraOResultado() throws Exception {
        String enunciado = "No Scrum Guide 2020, quem tem autoridade para cancelar uma Sprint antes do fim do timebox?";
        var quiz = tools.createQuiz(null, "Scrum 1", "Scrum", "Eventos", "prova", null, null,
                List.of(scrum(enunciado, "O Scrum Master, ouvido o time", "Somente o Product Owner")), null, null, null, ESTILO, REFS);

        // O mesmo enunciado, ou o mesmo reescrito de leve, não entra em outro quiz.
        var igual = assertThrows(IllegalArgumentException.class, () -> tools.createQuiz(null, "Scrum 2", "Scrum", "Eventos",
                "prova", null, null, List.of(scrum(enunciado, "O Scrum Master, ouvido o time", "Somente o Product Owner")),
                null, null, null, ESTILO, REFS));
        assertTrue(igual.getMessage().contains("repete a #"), igual.getMessage());
        assertThrows(IllegalArgumentException.class, () -> tools.createQuiz(null, "Scrum 2", "Scrum", "Eventos", "prova",
                null, null, List.of(scrum("Quem tem autoridade para cancelar uma Sprint antes do fim do timebox, segundo "
                        + "o Scrum Guide 2020?", "O Scrum Master, ouvido o time", "Somente o Product Owner")), null, null, null, ESTILO, REFS));
        assertThrows(IllegalArgumentException.class, () -> tools.addQuiz(quiz.quizId(),
                List.of(scrum(enunciado, "O Scrum Master, ouvido o time", "Somente o Product Owner"))));

        // Duas iguais no mesmo envio também não.
        assertThrows(IllegalArgumentException.class, () -> tools.createQuiz(null, "Scrum 3", "Scrum", "Eventos", "prova",
                null, null, List.of(scrum("A Daily Scrum dura no máximo quantos minutos para os Developers do time?", "30", "15"),
                        scrum("A Daily Scrum dura no máximo quantos minutos para os Developers do time?", "30", "15")),
                null, null, null, ESTILO, REFS));

        // Outro ponto do mesmo assunto entra normalmente.
        var outro = tools.createQuiz(null, "Scrum 4", "Scrum", "Eventos", "prova", null, null,
                List.of(scrum("Em uma Sprint de um mês, qual o limite da Sprint Review segundo o guia?", "Oito horas",
                        "Quatro horas")), null, null, null, ESTILO, REFS);
        assertEquals(1, lessonService.questions(outro.quizId()).size());

        // perguntas_ja_feitas traz o que já foi perguntado e se acertou.
        answer(quiz.quizId(), lessonService.questions(quiz.quizId()).get(0).getId(), "A");
        var asked = tools.askedQuestions(null, "Scrum", null, null, null);
        assertEquals(2, asked.size());
        var first = asked.stream().filter(q -> q.quizId().equals(quiz.quizId())).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, first.acertou());
        assertTrue(first.enunciado().startsWith("No Scrum Guide 2020"));
        assertNull(asked.stream().filter(q -> q.quizId().equals(outro.quizId())).findFirst().orElseThrow().acertou());
        assertEquals(1, tools.askedQuestions(null, "Scrum", "Scrum", 1, null).size());
    }

    @Test
    void trechoMarcadoNoEnunciadoApareceSublinhadoEOrestoEhEscapado() throws Exception {
        var quiz = tools.createQuiz(null, "Sintaxe com destaque", "Português", "Sintaxe", "prova", null, null,
                List.of(new QuizItem("Na frase \"É necessário ⟦u⟧que todos entreguem os documentos⟦/u⟧ até sexta-feira\", "
                        + "a oração sublinhada é <script>alert(1)</script> subordinada substantiva", "Sintaxe",
                        List.of(new QuizAlternative("subjetiva, pois é o sujeito de ⟦b⟧é necessário⟦/b⟧", "Certa."),
                                new QuizAlternative("objetiva direta", "Errada."),
                                new QuizAlternative("predicativa", "Errada.")), "A")), null, null, null, ESTILO, REFS);

        mvc.perform(get("/quizzes/" + quiz.quizId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("<u>que todos entreguem os documentos</u>")))
                .andExpect(content().string(containsString("<strong>é necessário</strong>")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("⟦u⟧"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<script>alert(1)</script>"))))
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")));
    }

    @Autowired
    com.pdf2questao.study.StudyTaskRepository taskRepository;
    @Autowired
    StudyMcpTools studyTools;
    @Autowired
    com.pdf2questao.aula.TodayFlowService flowService;

    @Test
    void aulaEQuizLigadosATarefaAparecemNaTarefaENoHoje() throws Exception {
        var task = new com.pdf2questao.study.StudyTask();
        task.setTitle("AFO – Receita pública");
        task.setPlannedDate(java.time.LocalDate.now());
        task = taskRepository.save(task);
        Long taskId = task.getId();

        assertThrows(IllegalArgumentException.class, () -> tools.createLesson("x", "AFO", null, "y", "fonte", 999_999L, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> tools.createLesson("x", "AFO", null, "y", " ", taskId, null, null, null, null, null, null, null));
        // Fonte sem link é recusada.
        assertThrows(IllegalArgumentException.class,
                () -> tools.createLesson("x", "AFO", null, "y", "Lei 4.320 (planalto.gov.br)", taskId, null, null, null, null, null, null, null));
        // Fonte "de memória" ou "só resumo" não entra em 'fontes'; o que não foi confirmado exige 'tentativas'.
        String link = "[Lei 4.320](https://www.planalto.gov.br/ccivil_03/leis/l4320.htm)";
        assertThrows(IllegalArgumentException.class, () -> tools.createLesson("x", "AFO", null, "y",
                link + "\nart. 43 de memória", taskId, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> tools.createLesson("x", "AFO", null, "y",
                link, taskId, null, "art. 43, §3º", null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> tools.createLesson("x", "AFO", null, "y",
                link, taskId, null, "art. 43, §3º", "não abriu", null, null, null, null));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> tools.createLesson("Página única", "AFO",
                "Receita", "## Sem slides", link, null, null, null, null, null, null, null, null)).getMessage()
                .contains("slides"), "aula nova sem slides é recusada");
        var comPendencia = tools.createLesson("Com pendência", "AFO", "Receita", SLIDES, link, null, null,
                "art. 43, §3º",
                "Tentei o Planalto (resumo apenas), baixei o PDF da UFSM e extraí o texto com pdftotext.", fixacao(),
                ESTILO, REFS, null);
        assertTrue(lessonService.find(comPendencia.aulaId()).getContent()
                .contains("### Não confirmado"));
        var lesson = plain("Receita pública", "AFO", "Receita", "Estágios da receita", taskId);
        var quiz = tools.createQuiz(lesson.aulaId(), null, null, null, null, null, null,
                List.of(item("Receita", "B")), null, null, null, ESTILO, REFS);
        var loose = tools.createQuiz(null, "Quiz de receita", "AFO", "Receita corrente", null, null, null,
                List.of(item("Receita corrente", "B")), null, null, null, ESTILO, REFS);
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

        mvc.perform(get("/hoje")).andExpect(content().string(containsString("Quiz de receita")))
                .andExpect(content().string(containsString("Começar aula")));

        // Fila do dia: Aula -> Quiz da aula -> Quiz avulso (ja feito) -> Concluir.
        var flow = flowService.flow(lessonService.task(taskId));
        assertEquals(List.of("aula", "quiz", "quiz"), flow.steps().stream().map(TodayFlowService.Step::kind).toList());
        assertEquals("aula", flow.action().kind());
        assertEquals("Começar aula", flow.action().label());
        assertTrue(flow.steps().get(2).done(), "o avulso respondido conta como feito");
        // Respondeu o quiz da aula: a aula conta como lida e o proximo passo vira concluir.
        answer(quiz.quizId(), lessonService.questions(quiz.quizId()).get(0).getId(), "B");
        flow = flowService.flow(lessonService.task(taskId));
        assertTrue(flow.steps().get(0).done());
        assertEquals("concluir", flow.action().kind());
        // Concluir e seguir: grava o tempo e vai para o proximo passo; endereco de fora e ignorado.
        mvc.perform(post("/estudos/cronograma/" + taskId + "/concluir").param("spentMinutes", "37")
                        .param("voltar", "/aulas/" + lesson.aulaId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/aulas/" + lesson.aulaId()));
        assertEquals(37, lessonService.task(taskId).getSpentMinutes());
        mvc.perform(post("/estudos/cronograma/" + taskId + "/concluir").param("done", "false")
                        .param("voltar", "//exemplo.com"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/estudos/cronograma"));
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
                null, null, "certo_errado", ESTILO, REFS);
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
                        new QuizAlternative("", "Errado.")), "C")), null, null, "certo_errado", ESTILO, REFS);
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
                        new QuizAlternative("B", "b")), "A")), null, null, null, ESTILO, REFS);
        assertThrows(IllegalArgumentException.class,
                () -> lessonService.answer(lessonService.questions(mc.quizId()).get(0).getId(), "-"));
    }

    @Test
    void quizNivelProvaExigePesquisaDaBancaERecusaPistas() {
        var semEstilo = assertThrows(IllegalArgumentException.class, () -> tools.createQuiz(null, "Prova", "AFO",
                "Empenho", null, null, null, List.of(item("Empenho", "B")), null, null, null, null, null));
        assertTrue(semEstilo.getMessage().contains("estiloDaBanca"), "vazio = nível prova");
        assertThrows(IllegalArgumentException.class, () -> tools.createQuiz(null, "Prova", "AFO", "Empenho", "prova",
                null, null, List.of(item("Empenho", "B")), null, null, null, ESTILO, "só uma"));
        var basico = tools.createQuiz(null, "Básico", "AFO", "Empenho", "basico", null, null,
                List.of(item("Empenho", "B")), null, null, null, null, null);
        assertEquals("basico", lessonService.quiz(basico.quizId()).getLevel());

        // Correta sempre a mais longa: dá para acertar sem saber.
        var lotes = List.of(
                new QuizItem("Na liquidação da despesa municipal, o que se verifica?", "Despesa", List.of(
                        new QuizAlternative("A dotação", "Errada."),
                        new QuizAlternative("O direito adquirido do credor, com base nos títulos e documentos", "Certa."),
                        new QuizAlternative("A cota", "Errada.")), "B"),
                new QuizItem("Restos a pagar processados são despesas que já passaram por qual estágio?", "Restos",
                        List.of(new QuizAlternative("Liquidação, faltando apenas o pagamento ao credor no exercício seguinte",
                                "Certa."), new QuizAlternative("Fixação", "Errada."),
                                new QuizAlternative("Programação", "Errada.")), "A"),
                new QuizItem("O empenho por estimativa é usado quando o montante é?", "Empenho", List.of(
                        new QuizAlternative("Fixo", "Errada."), new QuizAlternative("Nulo", "Errada."),
                        new QuizAlternative("Impossível de determinar previamente, como contas de água e luz", "Certa.")),
                        "C"),
                new QuizItem("Ordem bancária no pagamento de fornecedores da União serve para?", "Pagamento", List.of(
                        new QuizAlternative("Transferir o valor ao credor pela conta única do Tesouro Nacional", "Certa."),
                        new QuizAlternative("Empenhar", "Errada."), new QuizAlternative("Orçar", "Errada.")), "A"));
        var pista = assertThrows(IllegalArgumentException.class, () -> tools.createQuiz(null, "Pista", "AFO",
                "Liquidação", "prova", null, null, lotes, null, null, null, ESTILO, REFS));
        assertTrue(pista.getMessage().contains("mais longa"), pista.getMessage());
        var ok = tools.createQuiz(null, "Prova", "AFO", "Empenho", null, null, null, List.of(item("Empenho", "B")),
                null, null, null, ESTILO, REFS);
        assertEquals("prova", lessonService.quiz(ok.quizId()).getLevel());
    }

    @Test
    void excluirQuizApagaPerguntasERespostasSoComConfirmacao() throws Exception {
        var quiz = tools.createQuiz(null, "Para apagar", "AFO", "Excluir", "basico", null, null,
                List.of(item("Excluir", "B")), null, null, null, null, null);
        answer(quiz.quizId(), lessonService.questions(quiz.quizId()).get(0).getId(), "A");
        assertEquals(1, tools.previousErrors(null, "AFO", "Excluir").size());

        assertThrows(IllegalArgumentException.class, () -> tools.deleteQuiz(quiz.quizId(), null));
        var deleted = tools.deleteQuiz(quiz.quizId(), true);
        assertEquals(1, deleted.perguntas());
        assertEquals(1, deleted.respostas());
        assertTrue(lessonService.questions(quiz.quizId()).isEmpty());
        assertTrue(tools.previousErrors(null, "AFO", "Excluir").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> tools.deleteQuiz(quiz.quizId(), true));
    }

    @Test
    void aulaEmSlidesComQuestoesDeFixacaoNoMeio() throws Exception {
        String link = "[NBC TSP 12](https://www.legisweb.com.br/legislacao/?id=492485)";
        String slides = "=== DFC {#regra}\nFluxos de **investimento**.\n[[questão 1]]\n=== DMPL {#excecao}\nMutações do PL.\n"
                + "[[questão 2]]\n[[questão 3]]";
        var semQuestoes = assertThrows(IllegalArgumentException.class, () -> tools.createLesson("Slides", "Contabilidade",
                "DCASP", slides, link, null, null, null, null, null, null, null, null));
        assertTrue(semQuestoes.getMessage().contains("perguntasDaAula"), semQuestoes.getMessage());
        assertThrows(IllegalArgumentException.class, () -> tools.createLesson("Slides", "Contabilidade", "DCASP",
                slides, link, null, null, null, null, List.of(item("DFC", "B"), item("DFC", "B"), item("DMPL", "B"),
                        item("DMPL", "B")), ESTILO, REFS, null), "a questão 4 não aparece na aula");

        var aula = tools.createLesson("Slides", "Contabilidade", "DCASP", slides, link, null, null, null, null,
                List.of(item("DFC", "B"), item("DMPL", "B"), item("DMPL", "A")), ESTILO, REFS, null);
        assertEquals(3, aula.slides(), "DFC, DMPL e Fontes");
        assertEquals(3, aula.questoesDeFixacao());
        var fixacao = lessonService.fixation(aula.aulaId());
        assertTrue(fixacao.isEmbedded());
        assertEquals("prova", fixacao.getLevel());

        var perguntas = lessonService.questions(fixacao.getId());
        mvc.perform(get("/aulas/" + aula.aulaId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("lesson-deck")))
                .andExpect(content().string(containsString(">DFC<")))
                .andExpect(content().string(containsString(">Fontes<")))
                .andExpect(content().string(containsString("data-id=\"" + perguntas.get(1).getId() + "\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<p>[[questão"))));
        answer(fixacao.getId(), perguntas.get(0).getId(), "A");

        // Reescrever troca o conteúdo e a fixação; o erro da fixação anterior precisa ser retomado.
        String novo = "=== DFC {#regra}\nCaixa e equivalentes.\n[[questão 1]]\n=== PL {#excecao}\nResidual.\n"
                + "[[questão 2]]\n[[questão 3]]";
        assertThrows(IllegalArgumentException.class, () -> tools.rewriteLesson(aula.aulaId(), null, novo, link, null,
                null, fixacao(), ESTILO, REFS, null, null));
        var errosAntes = tools.previousErrors(aula.aulaId(), null, null);
        var reescrita = tools.rewriteLesson(aula.aulaId(), "Slides v2", novo, link, null, null,
                fixacao(), ESTILO, REFS, null, List.of(errosAntes.get(0).perguntaId()));
        assertEquals("Slides v2", reescrita.titulo());
        assertEquals(1, lessonService.quizzes(aula.aulaId()).size(), "a fixação antiga saiu");
        assertTrue(lessonService.find(aula.aulaId()).getContent().contains("Caixa e equivalentes"));
    }

    @Test
    void caixasEColunasLadoALadoNosSlides() {
        var slides = com.pdf2questao.aula.LessonSlides.parse("""
                === Merge
                Texto.
                :::exemplo Duas tabelas
                **contribuinte**
                | id | nome |
                |---|---|
                | 1 | Ana |
                +++
                **resultado**
                | nome |
                |---|
                | Ana |
                :::
                :::pegadinha
                Padrão é **`inner`**.
                :::
                Fim.""");
        String html = slides.get(0).parts().stream().map(com.pdf2questao.aula.LessonSlides.Part::html)
                .collect(java.util.stream.Collectors.joining());
        assertTrue(html.contains("lesson-box lesson-box-exemplo"), html);
        assertTrue(html.contains("<div class=\"lesson-box-title\">Duas tabelas</div>"), html);
        assertEquals(2, html.split("class=\"lesson-col\"").length - 1, html);
        assertTrue(html.contains("<td>Ana</td>"), html);
        assertTrue(html.contains("Pegadinha da banca"), html);
        assertTrue(html.contains("<strong><code>inner</code></strong>"), html);
        assertTrue(html.endsWith("<p>Fim.</p>"), html);
    }

    @Test
    void textoAntesDasColunasFicaEmCimaNaLarguraToda() {
        var slides = com.pdf2questao.aula.LessonSlides.parse("""
                === Dados
                :::dica Os dados
                Todos os exemplos usam estas tabelas.
                +++
                | id |
                |---|
                | 1 |
                +++
                | id |
                |---|
                | 10 |
                :::""");
        String html = slides.get(0).parts().get(0).html();
        assertTrue(html.contains("<div class=\"lesson-box-intro\"><p>Todos os exemplos usam estas tabelas.</p></div>"
                + "<div class=\"lesson-cols\">"), html);
        assertEquals(2, html.split("class=\"lesson-col\"").length - 1, "só as duas tabelas viram colunas");
    }

    private static QuizItem with(QuizItem base, String extra, List<String> conceitos, Boolean ampliacao) {
        return new QuizItem(base.enunciado() + extra, base.topico(), base.alternativas(), base.correta(), conceitos,
                ampliacao);
    }

    @Test
    void quizDaAulaSoCobraConceitosDaAulaComDadosDiferentesDoExemplo() throws Exception {
        String link = "[Lei 4.320](https://www.planalto.gov.br/ccivil_03/leis/l4320.htm)";
        String slides = """
                === Inscrição em RP {#rp-inscricao}
                Empenho de 100.000, liquidado 60.000: inscreve 40.000 em restos a pagar.
                [[questão 1]]
                === Cancelamento {#rp-cancelamento}
                :::pegadinha Prazo {#rp-prazo}
                Cancela no fim do exercício seguinte.
                :::
                [[questão 2]]
                [[questão 3]]""";

        var semConceito = assertThrows(IllegalArgumentException.class, () -> tools.createLesson("RP", "AFO", "Restos",
                slides.replace(" {#rp-cancelamento}", ""), link, null, null, null, null, fixacao(), ESTILO, REFS, null));
        assertTrue(semConceito.getMessage().contains("\"Cancelamento\""), semConceito.getMessage());

        // P1: a fixação do slide 1 reaproveita 100.000 e 60.000 do exemplo.
        var mesmosDados = assertThrows(IllegalArgumentException.class, () -> tools.createLesson("RP", "AFO", "Restos",
                slides, link, null, null, null, null, List.of(
                        with(item("RP", "A"), " Empenho de R$ 100.000 e liquidação de 60.000.", List.of("rp-inscricao"), null),
                        with(item("RP", "B"), "", List.of("rp-cancelamento"), null),
                        with(item("RP", "B"), "", List.of("rp-prazo"), null)), ESTILO, REFS, null));
        assertTrue(mesmosDados.getMessage().contains("repete os valores 100000, 60000 do exemplo do slide 1"),
                mesmosDados.getMessage());

        var aula = tools.createLesson("RP", "AFO", "Restos", slides, link, null, null, null, null, List.of(
                with(item("RP", "A"), " Empenho de R$ 250.000 e liquidação de 90.000.", List.of("rp-inscricao"), null),
                with(item("RP", "B"), "", List.of("#rp-cancelamento"), null),
                with(item("RP", "B"), "", List.of("{#rp-prazo}"), null)), ESTILO, REFS, null);

        // P2: conceito fora da aula ou pergunta sem conceito são recusados; ampliacao=true passa e fica marcada.
        var fora = assertThrows(IllegalArgumentException.class, () -> tools.createQuiz(aula.aulaId(), null, null, null,
                null, null, null, List.of(with(item("RP", "A"), "", List.of("rp-ordem-shuffle"), null)), null, null,
                null, ESTILO, REFS));
        assertTrue(fora.getMessage().contains("{#rp-ordem-shuffle}, que não está em nenhum slide"), fora.getMessage());
        assertTrue(fora.getMessage().contains("rp-inscricao (slide 1: Inscrição em RP)"), fora.getMessage());
        assertThrows(IllegalArgumentException.class, () -> tools.createQuiz(aula.aulaId(), null, null, null, null,
                null, null, List.of(with(item("RP", "A"), "", null, null)), null, null, null, ESTILO, REFS));

        var quiz = tools.createQuiz(aula.aulaId(), null, null, null, null, null, null, List.of(
                with(item("RP", "A"), "", List.of("rp-inscricao"), null),
                with(item("RP", "B"), "", List.of("rp-prazo"), null),
                with(item("RP", "A"), "", null, true)), null, null, null, ESTILO, REFS);
        var result = tools.quizResult(quiz.quizId());
        assertEquals(List.of(3), result.alemDaAula());
        assertTrue(result.incluirNaAula().contains("reescrever_aula"));
        assertEquals(List.of("slide 2: Cancelamento"), result.questoes().get(1).revise());

        mvc.perform(get("/quizzes/" + quiz.quizId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("/aulas/" + aula.aulaId() + "#c-rp-inscricao")))
                .andExpect(content().string(containsString("Revise: slide")))
                .andExpect(content().string(containsString("Além da aula")))
                .andExpect(content().string(containsString("(Veja o slide 2: Cancelamento.)")));
        mvc.perform(get("/aulas/" + aula.aulaId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"c-rp-inscricao\"")))
                .andExpect(content().string(containsString("id=\"c-rp-prazo\"")));

        var cobertura = tools.lessonCoverage(aula.aulaId());
        assertEquals(List.of(), cobertura.semPergunta().stream().map(c -> c.conceito()).toList());
        assertEquals(2, cobertura.conceitos().get(0).perguntas(), "fixação + quiz");
        assertEquals(1, cobertura.alemDaAula().size());

        // Acerto por conceito: errar a pergunta de rp-inscricao do quiz deixa o conceito "reler" na tela da aula.
        var perguntasQuiz = lessonService.questions(quiz.quizId());
        answer(quiz.quizId(), perguntasQuiz.get(0).getId(), "C");
        var inscricao = tools.lessonCoverage(aula.aulaId()).conceitos().get(0);
        assertEquals(1, inscricao.respondidas());
        assertEquals(0, inscricao.acertos());
        assertTrue(inscricao.fraco());
        mvc.perform(get("/aulas/" + aula.aulaId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Desempenho por conceito")))
                .andExpect(content().string(containsString("0 de 1 (0%)")))
                .andExpect(content().string(containsString(">reler<")))
                .andExpect(content().string(containsString("href=\"#c-rp-prazo\"")));

        // "Incluir na aula": depois de reescrever_aula com o conceito, a pergunta passa a apontar para ele.
        assertThrows(IllegalArgumentException.class, () -> tools.fixQuizQuestion(quiz.quizId(), 3, null, null, null,
                null, null, List.of("nao-existe"), false));
        tools.fixQuizQuestion(quiz.quizId(), 3, null, null, null, null, null, List.of("rp-cancelamento"), false);
        assertEquals(List.of(), tools.quizResult(quiz.quizId()).alemDaAula());
    }
}
