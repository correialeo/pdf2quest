package com.pdf2questao.dashboard;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/")
    public String root() {
        return "redirect:/hoje";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        model.addAttribute("data", dashboardService.overview());
        return "dashboard";
    }

    @GetMapping("/dashboard/prova/{id}")
    public String prova(@PathVariable Long id, Model model) {
        model.addAttribute("p", dashboardService.prova(id));
        return "dashboard-prova";
    }

    @GetMapping("/dashboard/bloco/{id}")
    public String bloco(@PathVariable Long id, Model model) {
        model.addAttribute("b", dashboardService.bloco(id));
        return "dashboard-bloco";
    }
}
