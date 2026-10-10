package com.pdf2questao.question;

import com.pdf2questao.session.AttemptRepository;
import com.pdf2questao.session.StudySession;
import com.pdf2questao.session.StudySessionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.HashMap;
import java.util.Map;

@Controller
public class ErrorNotebookController {

    private final QuestionRepository questionRepository;
    private final StudySessionService sessionService;
    private final AttemptRepository attemptRepository;

    public ErrorNotebookController(QuestionRepository questionRepository, StudySessionService sessionService,
                                   AttemptRepository attemptRepository) {
        this.questionRepository = questionRepository;
        this.sessionService = sessionService;
        this.attemptRepository = attemptRepository;
    }

    @GetMapping("/caderno-erros")
    public String list(Model model) {
        model.addAttribute("questions", questionRepository.findErrorNotebook());
        Map<Long, String> wrongAnswers = new HashMap<>();
        for (Object[] row : attemptRepository.findLatestWrongAnswers()) {
            wrongAnswers.put(((Number) row[0]).longValue(), (String) row[1]);
        }
        model.addAttribute("wrongAnswers", wrongAnswers);
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
