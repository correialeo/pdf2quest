package com.pdf2questao.study;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;

/** Cronograma de estudos: lista por data, criar/editar, concluir/reabrir e apagar tarefas. */
@Controller
public class StudyTaskController {

    private final StudyTaskService taskService;
    private final StudyPageService pageService;
    private final StudySubjects subjects;
    private final DaySummaryService daySummaryService;
    private final SpacedRepetitionService spacedRepetition;

    public StudyTaskController(StudyTaskService taskService, StudyPageService pageService, StudySubjects subjects,
                               DaySummaryService daySummaryService, SpacedRepetitionService spacedRepetition) {
        this.spacedRepetition = spacedRepetition;
        this.taskService = taskService;
        this.pageService = pageService;
        this.subjects = subjects;
        this.daySummaryService = daySummaryService;
    }

    /** Abre (ou cria, na primeira vez) o resumo do dia. */
    @PostMapping("/estudos/cronograma/dia/{date}/resumo")
    public String daySummary(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return "redirect:/estudos/paginas/" + daySummaryService.open(date).getId();
    }

    @GetMapping("/estudos/cronograma")
    public String list(@RequestParam(defaultValue = "") String ver, Model model) {
        LocalDate today = LocalDate.now();
        model.addAttribute("today", today);
        boolean showAll = ver.equals("todas");
        spacedRepetition.syncReviewTask(today);
        var schedule = taskService.schedule(today, showAll);
        var groups = schedule.groups();
        model.addAttribute("groups", groups);
        model.addAttribute("showAll", showAll);
        model.addAttribute("hiddenDays", schedule.hiddenDays());
        // O botao "trazer atrasadas para hoje" aparece so no primeiro grupo atrasado.
        model.addAttribute("overdueFirst", groups.stream().filter(g -> g.kind().equals("overdue"))
                .map(StudyTaskService.TaskGroup::date).findFirst().orElse(null));
        var dates = groups.stream().map(StudyTaskService.TaskGroup::date).filter(java.util.Objects::nonNull).toList();
        model.addAttribute("summaries", dates.isEmpty() ? java.util.Map.of()
                : daySummaryService.existing(java.util.Collections.min(dates), java.util.Collections.max(dates)));
        model.addAttribute("week", taskService.week(today));
        model.addAttribute("pages", pageService.flatTree());
        model.addAttribute("subjects", subjects.all());
        return "estudos-cronograma";
    }

    @PostMapping("/estudos/cronograma")
    public String create(@RequestParam(defaultValue = "") String title,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) String subject,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate plannedDate,
                         @RequestParam(required = false) Integer estimatedMinutes,
                         @RequestParam(required = false) Long pageId,
                         @RequestHeader(value = "Referer", required = false) String referer,
                         RedirectAttributes redirectAttributes) {
        try {
            taskService.create(new StudyTaskService.TaskForm(title, description, subject, plannedDate,
                    estimatedMinutes, null, pageId));
            redirectAttributes.addFlashAttribute("message", "Tarefa adicionada ao cronograma.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + StudyController.sameSite(referer, "/estudos/cronograma");
    }

    @GetMapping("/estudos/cronograma/{id}/editar")
    public String edit(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        var task = taskService.forEdit(id);
        if (task.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Esta tarefa não existe mais.");
            return "redirect:/estudos/cronograma";
        }
        model.addAttribute("task", task.get());
        model.addAttribute("pages", pageService.flatTree());
        model.addAttribute("subjects", subjects.all());
        return "estudos-tarefa";
    }

    @PostMapping("/estudos/cronograma/{id}/editar")
    public String update(@PathVariable Long id,
                         @RequestParam(defaultValue = "") String title,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) String subject,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate plannedDate,
                         @RequestParam(required = false) Integer estimatedMinutes,
                         @RequestParam(required = false) Integer spentMinutes,
                         @RequestParam(required = false) Long pageId,
                         RedirectAttributes redirectAttributes) {
        try {
            taskService.update(id, new StudyTaskService.TaskForm(title, description, subject, plannedDate,
                    estimatedMinutes, spentMinutes, pageId));
            redirectAttributes.addFlashAttribute("message", "Tarefa atualizada.");
            return "redirect:/estudos/cronograma";
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/estudos/cronograma/" + id + "/editar";
        }
    }

    @PostMapping("/estudos/cronograma/{id}/concluir")
    public String done(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean done,
                       @RequestParam(required = false) Integer spentMinutes,
                       @RequestHeader(value = "Referer", required = false) String referer) {
        taskService.setDone(id, done, spentMinutes);
        return "redirect:" + StudyController.sameSite(referer, "/estudos/cronograma");
    }

    /** Adia a tarefa para outro dia ("para" = AAAA-MM-DD). */
    @PostMapping("/estudos/cronograma/{id}/adiar")
    public String postpone(@PathVariable Long id, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate para,
                           @RequestHeader(value = "Referer", required = false) String referer,
                           RedirectAttributes redirectAttributes) {
        try {
            taskService.reschedule(id, para);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + StudyController.sameSite(referer, "/estudos/cronograma");
    }

    @PostMapping("/estudos/cronograma/atrasadas/hoje")
    public String overdueToToday(@RequestHeader(value = "Referer", required = false) String referer,
                                 RedirectAttributes redirectAttributes) {
        int moved = taskService.moveOverdueTo(LocalDate.now());
        redirectAttributes.addFlashAttribute("message", moved == 1 ? "1 tarefa atrasada veio para hoje."
                : moved + " tarefas atrasadas vieram para hoje.");
        return "redirect:" + StudyController.sameSite(referer, "/estudos/cronograma");
    }

    /** Liga/desliga a revisao espacada da tarefa ("ativa" = true/false). */
    @PostMapping("/estudos/cronograma/{id}/revisao")
    public String reviewEnabled(@PathVariable Long id, @RequestParam boolean ativa,
                                @RequestHeader(value = "Referer", required = false) String referer,
                                RedirectAttributes redirectAttributes) {
        LocalDate today = LocalDate.now();
        try {
            spacedRepetition.setEnabled(id, ativa, today);
            spacedRepetition.syncReviewTask(today);
            redirectAttributes.addFlashAttribute("message", ativa ? "A tarefa volta a entrar na revisão espaçada."
                    : "A tarefa não entra mais na revisão espaçada.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + StudyController.sameSite(referer, "/estudos/cronograma");
    }

    /** Edita a descricao direto na lista. */
    @PostMapping("/estudos/cronograma/{id}/descricao")
    public String description(@PathVariable Long id, @RequestParam(required = false) String description,
                              @RequestHeader(value = "Referer", required = false) String referer,
                              RedirectAttributes redirectAttributes) {
        try {
            taskService.setDescription(id, description);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + StudyController.sameSite(referer, "/estudos/cronograma");
    }

    /** Corrige os minutos dedicados (campo vazio apaga o registro). */
    @PostMapping("/estudos/cronograma/{id}/tempo")
    public String spent(@PathVariable Long id, @RequestParam(required = false) Integer spentMinutes,
                        @RequestHeader(value = "Referer", required = false) String referer,
                        RedirectAttributes redirectAttributes) {
        try {
            taskService.setSpent(id, spentMinutes);
            redirectAttributes.addFlashAttribute("message", "Tempo atualizado.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + StudyController.sameSite(referer, "/estudos/cronograma");
    }

    /**
     * Revisao espacada de um assunto: {@code lembrei=true} avanca nos intervalos; {@code false} volta para o primeiro
     * e sai da tarefa de revisao de hoje.
     */
    @PostMapping("/estudos/cronograma/{id}/revisar")
    public String review(@PathVariable Long id, @RequestParam boolean lembrei,
                         @RequestHeader(value = "Referer", required = false) String referer) {
        LocalDate today = LocalDate.now();
        if (lembrei) {
            spacedRepetition.remembered(id, today);
        } else {
            spacedRepetition.forgot(id, today);
        }
        spacedRepetition.syncReviewTask(today);
        return "redirect:" + StudyController.sameSite(referer, "/estudos");
    }

    @PostMapping("/estudos/cronograma/{id}/excluir")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        taskService.delete(id);
        redirectAttributes.addFlashAttribute("message", "Tarefa apagada.");
        return "redirect:/estudos/cronograma";
    }
}
