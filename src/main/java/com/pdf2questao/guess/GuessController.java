package com.pdf2questao.guess;

import com.pdf2questao.session.StudySession;
import com.pdf2questao.session.StudySessionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
public class GuessController {

    private final GuessDiagnosticService diagnosticService;
    private final StudySessionService sessionService;

    public GuessController(GuessDiagnosticService diagnosticService, StudySessionService sessionService) {
        this.diagnosticService = diagnosticService;
        this.sessionService = sessionService;
    }

    @GetMapping("/chutes")
    public String list(@RequestParam(required = false) Long prova, Model model) {
        model.addAttribute("report", diagnosticService.report(prova));
        return "chutes";
    }

    @PostMapping("/chutes/refazer")
    public String refazer(@RequestParam(required = false) Long prova, RedirectAttributes redirect) {
        List<Long> ids = diagnosticService.luckyQuestionIds(prova);
        if (ids.isEmpty()) {
            redirect.addFlashAttribute("error", "Nenhum acerto no chute para refazer.");
            return "redirect:/chutes";
        }
        StudySession session = sessionService.startRefazer(ids);
        return "redirect:/resolver/" + session.getId() + "?index=0";
    }
}
