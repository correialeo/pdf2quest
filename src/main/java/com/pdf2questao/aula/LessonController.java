package com.pdf2questao.aula;

import com.pdf2questao.web.SimpleMarkdown;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;
import java.util.stream.Collectors;

/** Aulas (com seus quizzes), quizzes avulsos e a tela do quiz (corrigida na hora, com o porque de cada alternativa). */
@Controller
public class LessonController {

    private final LessonService lessonService;
    private final com.pdf2questao.study.StudyTaskService taskService;

    private final com.pdf2questao.flashcard.FlashcardService flashcardService;
    private final TodayFlowService flowService;

    public LessonController(LessonService lessonService, com.pdf2questao.study.StudyTaskService taskService,
                            com.pdf2questao.flashcard.FlashcardService flashcardService,
                            TodayFlowService flowService) {
        this.flashcardService = flashcardService;
        this.flowService = flowService;
        this.lessonService = lessonService;
        this.taskService = taskService;
    }

    /** Proximo passo depois de terminar: a proxima tarefa aberta da fila de hoje, com o link da aula/quiz dela. */
    public record NextStep(com.pdf2questao.study.StudyTask task, String link, String label) {
    }

    private NextStep next(Long exceptTaskId) {
        TodayFlowService.Flow f = flowService.next(java.time.LocalDate.now(), exceptTaskId);
        return f == null ? null : new NextStep(f.task(), f.link(), f.action().label());
    }

    /**
     * Barra de sessao da aula/quiz: a tarefa na fila de hoje (posicao, passos) e o proximo passo.
     * Nula quando a aula/quiz nao e de uma tarefa da fila de hoje.
     */
    public record Session(TodayFlowService.Flow flow, int position, int total, int done, NextStep next) {
    }

    private Session session(com.pdf2questao.study.StudyTask task, NextStep next) {
        if (task == null) {
            return null;
        }
        TodayFlowService.Day day = flowService.day(java.time.LocalDate.now());
        if (!day.contains(task.getId())) {
            return null;
        }
        return new Session(flowService.flow(task), day.position(task.getId()), day.total(), day.done().size(), next);
    }

    @GetMapping("/aulas")
    public String list(Model model) {
        model.addAttribute("overview", lessonService.overview());
        model.addAttribute("errorsBySubject", lessonService.openErrorsBySubject());
        model.addAttribute("streak", lessonService.streak());
        model.addAttribute("stats", lessonService.stats());
        model.addAttribute("totals", lessonService.totals());
        return "aulas";
    }

    @GetMapping("/aulas/erros")
    public String errors(@RequestParam(required = false) String disciplina, Model model) {
        model.addAttribute("errors", lessonService.openErrors(disciplina));
        model.addAttribute("errorsBySubject", lessonService.openErrorsBySubject());
        model.addAttribute("disciplina", disciplina);
        return "aulas-erros";
    }

    @GetMapping("/aulas/{id:\\d+}")
    public String lesson(@PathVariable Long id, Model model) {
        Lesson lesson = lessonService.find(id);
        model.addAttribute("lesson", lesson);
        model.addAttribute("task", lessonService.task(lesson.getTaskId()));
        model.addAttribute("contentHtml", SimpleMarkdown.toHtml(lesson.getContent()));
        LessonSlides.Parsed parsed = LessonSlides.parseAll(lesson.getContent());
        model.addAttribute("slides", parsed.slides());
        // Aula interativa: tabelas :::dados para o runner, ultima tentativa dos exercicios e se ha o que carregar.
        model.addAttribute("datasetsJson", toJson(parsed.datasets()));
        // Cobertura por conceito: quantas perguntas cobrem cada slide e o acerto nelas (o que reler antes da prova).
        model.addAttribute("coverage", lessonService.coverage(id));
        model.addAttribute("exerciseJson", toJson(lessonService.exerciseStatus(id)));
        model.addAttribute("hasCode", parsed.blocks().stream().anyMatch(b -> b.kind() == LessonBlocks.Kind.RUN
                || b.kind() == LessonBlocks.Kind.TAREFA));
        model.addAttribute("hasMath", parsed.blocks().stream().anyMatch(b -> b.kind() == LessonBlocks.Kind.FORMULA
                || b.kind() == LessonBlocks.Kind.CALCULO));
        Quiz fixation = lessonService.fixation(id);
        model.addAttribute("fixation", fixation);
        model.addAttribute("fixationQuestions", fixation == null ? Map.of() : lessonService.questions(fixation.getId())
                .stream().collect(Collectors.toMap(QuizQuestion::getPosition, q -> q)));
        model.addAttribute("fixationAnswers", fixation == null ? Map.of() : lessonService.latest(fixation.getId()));
        model.addAttribute("quizzes", lessonService.quizzes(id));
        Map<Long, LessonService.QuizResult> results = lessonService.quizzes(id).stream()
                .collect(Collectors.toMap(Quiz::getId, q -> lessonService.result(q.getId())));
        model.addAttribute("results", results);
        model.addAttribute("pendingQuiz", lessonService.quizzes(id).stream()
                .filter(q -> !q.isEmbedded() && !results.get(q.getId()).concluido()).findFirst().orElse(null));
        NextStep nextStep = next(lesson.getTaskId());
        model.addAttribute("next", nextStep);
        model.addAttribute("fila", session(lessonService.task(lesson.getTaskId()), nextStep));
        return "aula";
    }

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }

    @GetMapping("/quizzes/{id}")
    public String quiz(@PathVariable Long id, Model model) {
        Quiz quiz = lessonService.quiz(id);
        model.addAttribute("quiz", quiz);
        model.addAttribute("task", lessonService.task(lessonService.effectiveTaskId(quiz)));
        model.addAttribute("lesson", quiz.getLessonId() == null ? null : lessonService.find(quiz.getLessonId()));
        model.addAttribute("questions", lessonService.questions(id));
        model.addAttribute("answers", lessonService.latest(id));
        // "Revise: slide X" de cada pergunta: o slide da aula que ensina o conceito que ela cobra.
        // Revisao com flashcards: cartoes primeiro, depois as questoes (as dos conceitos dificeis vem antes).
        model.addAttribute("flashcards", flashcardService.forQuiz(quiz));
        model.addAttribute("reviewSlides", lessonService.conceptsOf(quiz.getLessonId(), lessonService.questions(id)));
        Long taskId = lessonService.effectiveTaskId(quiz);
        NextStep nextStep = next(taskId);
        model.addAttribute("next", nextStep);
        model.addAttribute("fila", session(lessonService.task(taskId), nextStep));
        // Outro quiz da mesma aula ainda por fazer (para seguir direto).
        model.addAttribute("nextQuiz", quiz.getLessonId() == null ? null : lessonService.quizzes(quiz.getLessonId()).stream()
                .filter(q -> !q.getId().equals(id) && !lessonService.result(q.getId()).concluido()).findFirst().orElse(null));
        return "quiz";
    }

    public record AnswerRequest(Long questionId, String selected) {
    }

    public record ExerciseRequest(Integer block, Boolean correct, Integer hints) {
    }

    /** Fim de uma tentativa de exercicio guiado/tarefa de codigo da aula (conta no desempenho, com as dicas). */
    @PostMapping("/aulas/{id}/exercicio")
    @ResponseBody
    public LessonService.ExerciseResult exercise(@PathVariable Long id, @RequestBody ExerciseRequest request) {
        if (request.block() == null) {
            throw new IllegalArgumentException("Informe o bloco.");
        }
        return lessonService.recordExercise(id, request.block(), Boolean.TRUE.equals(request.correct()),
                request.hints() == null ? 0 : request.hints());
    }

    public record FlashcardRequest(Long card, String rating) {
    }

    /** Autoavaliacao de um flashcard do quiz (errei, dificil, facil). */
    @PostMapping("/quizzes/{id}/flashcard")
    @ResponseBody
    public java.util.Map<String, Object> rateFlashcard(@PathVariable Long id, @RequestBody FlashcardRequest request) {
        var r = flashcardService.rate(id, request.card(), request.rating());
        return java.util.Map.of("card", r.getFlashcardId(), "rating", r.getRating());
    }

    @PostMapping("/quizzes/{id}/responder")
    @ResponseBody
    public LessonService.AnswerResult answer(@PathVariable Long id, @RequestBody AnswerRequest request) {
        return lessonService.answer(request.questionId(), request.selected());
    }

    @PostMapping("/quizzes/{id}/refazer")
    public String restart(@PathVariable Long id) {
        lessonService.restart(id);
        return "redirect:/quizzes/" + id;
    }
}
