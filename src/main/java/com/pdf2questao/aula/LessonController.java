package com.pdf2questao.aula;

import com.pdf2questao.web.SimpleMarkdown;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;
import java.util.stream.Collectors;

/** Aulas (com seus quizzes), quizzes avulsos e a tela do quiz (corrigida na hora, com o porque de cada alternativa). */
@Controller
public class LessonController {

    private final LessonService lessonService;

    public LessonController(LessonService lessonService) {
        this.lessonService = lessonService;
    }

    @GetMapping("/aulas")
    public String list(Model model) {
        model.addAttribute("overview", lessonService.overview());
        model.addAttribute("stats", lessonService.stats());
        model.addAttribute("totals", lessonService.totals());
        return "aulas";
    }

    @GetMapping("/aulas/{id}")
    public String lesson(@PathVariable Long id, Model model) {
        Lesson lesson = lessonService.find(id);
        model.addAttribute("lesson", lesson);
        model.addAttribute("task", lessonService.task(lesson.getTaskId()));
        model.addAttribute("contentHtml", SimpleMarkdown.toHtml(lesson.getContent()));
        model.addAttribute("quizzes", lessonService.quizzes(id));
        Map<Long, LessonService.QuizResult> results = lessonService.quizzes(id).stream()
                .collect(Collectors.toMap(Quiz::getId, q -> lessonService.result(q.getId())));
        model.addAttribute("results", results);
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
