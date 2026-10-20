package com.pdf2questao.study;

import com.pdf2questao.dashboard.DashboardService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;

/** Visao geral da area de Estudos e caixa de entrada. */
@Controller
public class StudyController {

    private final StudyPageService pageService;
    private final StudyTaskService taskService;
    private final InboxService inboxService;
    private final SpacedRepetitionService spacedRepetition;
    private final StudySubjects subjects;
    private final DaySummaryService daySummaryService;
    private final DashboardService dashboardService;

    public StudyController(StudyPageService pageService, StudyTaskService taskService, InboxService inboxService,
                           StudySubjects subjects, DaySummaryService daySummaryService,
                           SpacedRepetitionService spacedRepetition, DashboardService dashboardService) {
        this.dashboardService = dashboardService;
        this.spacedRepetition = spacedRepetition;
        this.pageService = pageService;
        this.taskService = taskService;
        this.inboxService = inboxService;
        this.subjects = subjects;
        this.daySummaryService = daySummaryService;
    }

    /**
     * Tela inicial "Hoje": tarefas e revisoes do dia, sessoes para continuar, caderno de erros, ultimo simulado
     * e anotacao rapida. /estudos e o endereco antigo da mesma tela.
     */
    @GetMapping({"/hoje", "/estudos"})
    public String overview(Model model) {
        LocalDate today = LocalDate.now();
        model.addAttribute("today", today);
        spacedRepetition.syncReviewTask(today);
        model.addAttribute("dueTasks", taskService.dueBy(today));
        model.addAttribute("dueReviews", spacedRepetition.due(today));
        model.addAttribute("todaySummary", daySummaryService.existing(today, today).get(today));
        model.addAttribute("week", taskService.week(today));
        model.addAttribute("recentPages", pageService.recent());
        model.addAttribute("inboxCount", inboxService.count());
        model.addAttribute("practice", dashboardService.today());
        return "estudos";
    }

    @GetMapping("/estudos/caixa-de-entrada")
    public String inbox(Model model) {
        model.addAttribute("notes", inboxService.all());
        model.addAttribute("pages", pageService.flatTree());
        model.addAttribute("subjects", subjects.all());
        return "estudos-caixa";
    }

    @PostMapping("/estudos/caixa-de-entrada")
    public String addNote(@RequestParam(defaultValue = "") String content,
                          @RequestParam(required = false) String subject,
                          @RequestHeader(value = "Referer", required = false) String referer,
                          RedirectAttributes redirectAttributes) {
        try {
            inboxService.add(content, subject);
            redirectAttributes.addFlashAttribute("message", "Anotação guardada na caixa de entrada.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + sameSite(referer, "/estudos/caixa-de-entrada");
    }

    @PostMapping("/estudos/caixa-de-entrada/{id}/disciplina")
    public String noteSubject(@PathVariable Long id, @RequestParam(defaultValue = "") String subject,
                              RedirectAttributes redirectAttributes) {
        inboxService.setSubject(id, subject);
        redirectAttributes.addFlashAttribute("message", "Disciplina atualizada.");
        return "redirect:/estudos/caixa-de-entrada";
    }

    @PostMapping("/estudos/caixa-de-entrada/{id}/virar-pagina")
    public String noteToPage(@PathVariable Long id, @RequestParam(required = false) Long parentId,
                             RedirectAttributes redirectAttributes) {
        StudyPage page = inboxService.toPage(id, parentId);
        redirectAttributes.addFlashAttribute("message", "A anotação virou uma página.");
        return "redirect:/estudos/paginas/" + page.getId();
    }

    @PostMapping("/estudos/caixa-de-entrada/{id}/excluir")
    public String deleteNote(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        inboxService.delete(id);
        redirectAttributes.addFlashAttribute("message", "Anotação apagada.");
        return "redirect:/estudos/caixa-de-entrada";
    }

    /** Volta para a tela de onde a anotacao rapida veio, se for desta aplicacao. */
    static String sameSite(String referer, String fallback) {
        if (referer == null) {
            return fallback;
        }
        int path = referer.indexOf('/', referer.indexOf("//") + 2);
        String target = path < 0 ? fallback : referer.substring(path);
        return target.startsWith("/") && !target.startsWith("//") ? target : fallback;
    }
}
