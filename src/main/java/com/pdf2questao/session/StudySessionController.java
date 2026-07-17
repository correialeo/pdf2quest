package com.pdf2questao.session;

import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Controller
public class StudySessionController {

    private final StudySessionService sessionService;
    private final QuestionRepository questionRepository;

    public StudySessionController(StudySessionService sessionService, QuestionRepository questionRepository) {
        this.sessionService = sessionService;
        this.questionRepository = questionRepository;
    }

    @GetMapping("/simulado")
    public String simuladoForm() {
        return "simulado";
    }

    @PostMapping("/simulado/iniciar")
    public String iniciarSimulado(@RequestParam int qtyGerais, @RequestParam int qtyEspecificas,
                                   @RequestParam(defaultValue = "false") boolean keepExamOrder) {
        StudySession session = sessionService.startSimulado(qtyGerais, qtyEspecificas, keepExamOrder);
        return "redirect:/resolver/" + session.getId() + "?index=0";
    }

    @GetMapping("/resolver/{id}")
    public String resolver(@PathVariable Long id, @RequestParam(defaultValue = "0") int index, Model model) {
        StudySession session = sessionService.getSession(id);
        List<Long> ids = session.questionIds();
        if (index < 0) {
            index = 0;
        }
        if (index >= ids.size()) {
            index = ids.size() - 1;
        }
        Question question = questionRepository.findById(ids.get(index)).orElseThrow();

        model.addAttribute("studySession", session);
        model.addAttribute("question", question);
        model.addAttribute("index", index);
        model.addAttribute("total", ids.size());
        model.addAttribute("hasPrevious", index > 0);
        model.addAttribute("hasNext", index < ids.size() - 1);
        return "resolver";
    }

    @PostMapping("/resolver/{id}/responder")
    @ResponseBody
    public void responder(@PathVariable Long id, @RequestBody AnswerRequest request) {
        sessionService.answer(id, request.questionId(), request.selectedAnswer(), request.elapsedMillis());
    }

    @PostMapping("/resolver/{id}/finalizar")
    public String finalizar(@PathVariable Long id) {
        sessionService.finish(id);
        return "redirect:/resultado/" + id;
    }

    @GetMapping("/resultado/{id}")
    public String resultado(@PathVariable Long id, Model model) {
        StudySession session = sessionService.getSession(id);
        long totalQuestions = session.questionIds().size();
        long avgMillis = (totalQuestions == 0 || session.getTotalElapsedMillis() == null)
                ? 0 : session.getTotalElapsedMillis() / totalQuestions;

        model.addAttribute("studySession", session);
        model.addAttribute("total", totalQuestions);
        model.addAttribute("avgMillis", avgMillis);
        model.addAttribute("review", sessionService.review(id));
        return "resultado";
    }

    public record AnswerRequest(Long questionId, String selectedAnswer, Long elapsedMillis) {
    }
}
