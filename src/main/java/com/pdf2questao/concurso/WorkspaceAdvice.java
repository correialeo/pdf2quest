package com.pdf2questao.concurso;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.ui.Model;

/** Seletor de concurso da barra lateral: o ativo e a lista, em todas as telas. */
@ControllerAdvice(annotations = Controller.class)
public class WorkspaceAdvice {

    private final ConcursoService concursoService;

    public WorkspaceAdvice(ConcursoService concursoService) {
        this.concursoService = concursoService;
    }

    @ModelAttribute
    public void workspace(Model model) {
        model.addAttribute("concursoAtivo", concursoService.current().orElse(null));
        model.addAttribute("concursosMenu", concursoService.active());
        model.addAttribute("cronogramasMenu", concursoService.cronogramas());
    }
}
