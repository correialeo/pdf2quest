package com.concursos.study.question;

import com.concursos.study.session.StudySession;
import com.concursos.study.session.StudySessionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class QuestionController {

    private final QuestionRepository questionRepository;
    private final StudySessionService sessionService;

    public QuestionController(QuestionRepository questionRepository, StudySessionService sessionService) {
        this.questionRepository = questionRepository;
        this.sessionService = sessionService;
    }

    @GetMapping("/questoes")
    public String filters(Model model) {
        model.addAttribute("subjects", questionRepository.findDistinctSubjects());
        model.addAttribute("topics", questionRepository.findDistinctTopics());
        model.addAttribute("years", questionRepository.findDistinctYears());
        model.addAttribute("organizations", questionRepository.findDistinctOrganizations());
        model.addAttribute("total", questionRepository.count());
        return "questoes";
    }

    @PostMapping("/questoes/iniciar")
    public String iniciar(@RequestParam(required = false) String subject,
                           @RequestParam(required = false) String topic,
                           @RequestParam(required = false) Integer year,
                           @RequestParam(required = false) String organization,
                           @RequestParam int quantity,
                           @RequestParam(defaultValue = "false") boolean keepExamOrder) {
        StudySession session = sessionService.startPractice(
                blankToNull(subject), blankToNull(topic), year, blankToNull(organization), quantity, keepExamOrder);
        return "redirect:/resolver/" + session.getId() + "?index=0";
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
