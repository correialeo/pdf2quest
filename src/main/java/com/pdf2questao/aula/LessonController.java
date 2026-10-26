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

    public LessonController(LessonService lessonService, com.pdf2questao.study.StudyTaskService taskService) {
        this.lessonService = lessonService;
        this.taskService = taskService;
    }

    /** Proximo passo depois de terminar: a proxima tarefa aberta de hoje (ou atrasada), com o link da aula/quiz dela. */
    public record NextStep(com.pdf2questao.study.StudyTask task, String link, String label) {
    }

    private NextStep next(Long exceptTaskId) {
        return taskService.dueBy(java.time.LocalDate.now()).stream()
                .filter(t -> !t.isReview() && !t.getId().equals(exceptTaskId)).findFirst()
                .map(t -> {
                    var linked = lessonService.forTask(t.getId()).stream().filter(l -> !l.concluido()).findFirst();
                    return new NextStep(t, linked.map(LessonService.Linked::link)
                            .orElse("/estudos/cronograma/" + t.getId() + "/editar"),
                            linked.map(l -> "Abrir " + l.tipo().toLowerCase()).orElse("Abrir tarefa"));
                }).orElse(null);
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
        model.addAttribute("quizzes", lessonService.quizzes(id));
        Map<Long, LessonService.QuizResult> results = lessonService.quizzes(id).stream()
                .collect(Collectors.toMap(Quiz::getId, q -> lessonService.result(q.getId())));
        model.addAttribute("results", results);
        model.addAttribute("pendingQuiz", lessonService.quizzes(id).stream()
                .filter(q -> !results.get(q.getId()).concluido()).findFirst().orElse(null));
        model.addAttribute("next", next(lesson.getTaskId()));
        return "aula";
    }

    @GetMapping("/quizzes/{id}")
    public String quiz(@PathVariable Long id, Model model) {
        Quiz quiz = lessonService.quiz(id);
        model.addAttribute("quiz", quiz);
        model.addAttribute("task", lessonService.task(lessonService.effectiveTaskId(quiz)));
        model.addAttribute("lesson", quiz.getLessonId() == null ? null : lessonService.find(quiz.getLessonId()));
        model.addAttribute("questions", lessonService.questions(id));
        model.addAttribute("answers", lessonService.latest(id));
        Long taskId = lessonService.effectiveTaskId(quiz);
        model.addAttribute("next", next(taskId));
        // Outro quiz da mesma aula ainda por fazer (para seguir direto).
        model.addAttribute("nextQuiz", quiz.getLessonId() == null ? null : lessonService.quizzes(quiz.getLessonId()).stream()
                .filter(q -> !q.getId().equals(id) && !lessonService.result(q.getId()).concluido()).findFirst().orElse(null));
        return "quiz";
    }

    public record AnswerRequest(Long questionId, String selected) {
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
