package com.pdf2questao.aula;

import com.pdf2questao.web.SimpleMarkdown;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/** Lista de aulas e a tela da aula com o quiz (resposta corrigida na hora, com o porque de cada alternativa). */
@Controller
public class LessonController {

    private final LessonService lessonService;

    public LessonController(LessonService lessonService) {
        this.lessonService = lessonService;
    }

    @GetMapping("/aulas")
    public String list(Model model) {
        model.addAttribute("lessons", lessonService.all());
        model.addAttribute("stats", lessonService.stats());
        return "aulas";
    }

    @GetMapping("/aulas/{id}")
    public String lesson(@PathVariable Long id, Model model) {
        Lesson lesson = lessonService.find(id);
        model.addAttribute("lesson", lesson);
        model.addAttribute("contentHtml", SimpleMarkdown.toHtml(lesson.getContent()));
        model.addAttribute("questions", lessonService.questions(id));
        model.addAttribute("answers", lessonService.latest(id));
        return "aula";
    }

    public record AnswerRequest(Long questionId, String selected) {
    }

    @PostMapping("/aulas/{id}/responder")
    @ResponseBody
    public LessonService.AnswerResult answer(@PathVariable Long id, @RequestBody AnswerRequest request) {
        return lessonService.answer(request.questionId(), request.selected());
    }

    @PostMapping("/aulas/{id}/refazer")
    public String restart(@PathVariable Long id) {
        lessonService.restart(id);
        return "redirect:/aulas/" + id + "#quiz";
    }
}
