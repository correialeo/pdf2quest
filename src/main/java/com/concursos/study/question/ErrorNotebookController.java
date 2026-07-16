package com.concursos.study.question;

import com.concursos.study.session.StudySession;
import com.concursos.study.session.StudySessionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class ErrorNotebookController {

    private final QuestionRepository questionRepository;
    private final StudySessionService sessionService;

    public ErrorNotebookController(QuestionRepository questionRepository, StudySessionService sessionService) {
        this.questionRepository = questionRepository;
        this.sessionService = sessionService;
    }

    @GetMapping("/caderno-erros")
    public String list(Model model) {
        model.addAttribute("questions", questionRepository.findErrorNotebook());
        return "caderno-erros";
    }

    @PostMapping("/caderno-erros/refazer")
    public String refazer() {
        java.util.List<Long> ids = questionRepository.findErrorNotebook().stream()
                .map(Question::getId)
                .toList();
        StudySession session = sessionService.startRefazer(ids);
        return "redirect:/resolver/" + session.getId() + "?index=0";
    }
}
