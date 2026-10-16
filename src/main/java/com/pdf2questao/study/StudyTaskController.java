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

    public StudyTaskController(StudyTaskService taskService, StudyPageService pageService, StudySubjects subjects,
                               DaySummaryService daySummaryService) {
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
        var schedule = taskService.schedule(today, showAll);
        var groups = schedule.groups();
        model.addAttribute("groups", groups);
        model.addAttribute("showAll", showAll);
        model.addAttribute("hiddenDays", schedule.hiddenDays());
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

    @PostMapping("/estudos/cronograma/{id}/excluir")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        taskService.delete(id);
        redirectAttributes.addFlashAttribute("message", "Tarefa apagada.");
        return "redirect:/estudos/cronograma";
    }
}
