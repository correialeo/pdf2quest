package com.pdf2questao.discursiva;

import com.pdf2questao.web.SimpleMarkdown;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Lista de discursivas, tela de escrita (rascunho automatico, tempo, linhas) e a correcao. */
@Controller
public class EssayController {

    /** Caracteres por linha da folha de respostas, para estimar as linhas usadas. */
    static final int CHARS_PER_LINE = 70;

    private final EssayService essayService;

    public EssayController(EssayService essayService) {
        this.essayService = essayService;
    }

    @GetMapping("/discursivas")
    public String list(Model model) {
        model.addAttribute("essays", essayService.all());
        return "discursivas";
    }

    @GetMapping("/discursivas/{id}")
    public String essay(@PathVariable Long id, Model model) {
        Essay essay = essayService.find(id);
        model.addAttribute("essay", essay);
        model.addAttribute("charsPerLine", CHARS_PER_LINE);
        model.addAttribute("usedLines", EssayService.lines(essay.getText(), CHARS_PER_LINE));
        model.addAttribute("feedbackHtml", SimpleMarkdown.toHtml(essay.getFeedback()));
        return "discursiva";
    }

    public record DraftRequest(String text, Long millis) {
    }

    @PostMapping("/discursivas/{id}/rascunho")
    @ResponseBody
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void draft(@PathVariable Long id, @RequestBody DraftRequest request) {
        try {
            essayService.saveDraft(id, request.text(), request.millis());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/discursivas/{id}/entregar")
    public String submit(@PathVariable Long id, @RequestParam(defaultValue = "") String text,
                         @RequestParam(required = false) Long millis, RedirectAttributes redirectAttributes) {
        try {
            essayService.submit(id, text, millis);
            redirectAttributes.addFlashAttribute("message", "Discursiva entregue. No Claude Code, peça: "
                    + "\"corrige a discursiva " + id + "\".");
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/discursivas/" + id;
    }
}
