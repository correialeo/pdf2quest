package com.pdf2questao.concurso;

import com.pdf2questao.scoring.ScoringProfileService;
import com.pdf2questao.study.StudyController;
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

/** Concursos (workspace): cadastro, home de cada concurso, seletor da barra lateral e cronogramas. */
@Controller
public class ConcursoController {

    private final ConcursoService concursoService;
    private final ConcursoHomeService homeService;
    private final ScoringProfileService profileService;
    private final Workspace workspace;

    public ConcursoController(ConcursoService concursoService, ConcursoHomeService homeService,
                              ScoringProfileService profileService, Workspace workspace) {
        this.concursoService = concursoService;
        this.homeService = homeService;
        this.profileService = profileService;
        this.workspace = workspace;
    }

    @GetMapping("/concursos")
    public String list(Model model) {
        LocalDate today = LocalDate.now();
        model.addAttribute("today", today);
        model.addAttribute("concursos", concursoService.all());
        model.addAttribute("homes", concursoService.all().stream().filter(c -> !c.isArquivado())
                .collect(java.util.stream.Collectors.toMap(Concurso::getId, c -> homeService.home(c.getId()))));
        return "concursos";
    }

    @GetMapping("/concursos/novo")
    public String newForm(Model model) {
        model.addAttribute("concurso", new Concurso());
        return form(model);
    }

    @GetMapping("/concursos/{id}/editar")
    public String edit(@PathVariable Long id, Model model) {
        model.addAttribute("concurso", concursoService.find(id).orElseThrow());
        return form(model);
    }

    private String form(Model model) {
        model.addAttribute("statuses", ConcursoStatus.values());
        model.addAttribute("profiles", profileService.findAll());
        return "concurso-form";
    }

    @PostMapping({"/concursos", "/concursos/{id}/editar"})
    public String save(@PathVariable(required = false) Long id,
                       @RequestParam(defaultValue = "") String nome,
                       @RequestParam(required = false) String orgao,
                       @RequestParam(required = false) String banca,
                       @RequestParam(required = false) String cargo,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataProva,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInscricaoFim,
                       @RequestParam(required = false) String linkEdital,
                       @RequestParam(required = false) ConcursoStatus status,
                       @RequestParam(required = false) Long scoringProfileId,
                       @RequestParam(required = false) String cor,
                       @RequestParam(required = false) String notas,
                       @RequestParam(defaultValue = "false") boolean ativar,
                       RedirectAttributes redirectAttributes) {
        var form = new ConcursoService.ConcursoForm(nome, orgao, banca, cargo, dataProva, dataInscricaoFim, linkEdital,
                status, scoringProfileId, cor, notas);
        try {
            Concurso saved = id == null ? concursoService.create(form) : concursoService.update(id, form);
            if (ativar) {
                workspace.select(saved.getId());
            }
            redirectAttributes.addFlashAttribute("message", id == null ? "Concurso criado." : "Concurso atualizado.");
            return "redirect:/concursos/" + saved.getId();
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:" + (id == null ? "/concursos/novo" : "/concursos/" + id + "/editar");
        }
    }

    @GetMapping("/concursos/{id}")
    public String home(@PathVariable Long id, Model model) {
        model.addAttribute("home", homeService.home(concursoService.find(id).orElseThrow().getId()));
        model.addAttribute("today", LocalDate.now());
        model.addAttribute("profile", concursoService.get(id).getScoringProfileId() == null ? null
                : profileService.find(concursoService.get(id).getScoringProfileId()).orElse(null));
        return "concurso";
    }

    @PostMapping("/concursos/{id}/arquivar")
    public String archive(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean arquivar,
                          RedirectAttributes redirectAttributes) {
        concursoService.archive(id, arquivar);
        redirectAttributes.addFlashAttribute("message", arquivar ? "Concurso arquivado." : "Concurso reaberto.");
        return "redirect:/concursos";
    }

    @PostMapping("/concursos/{id}/excluir")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        concursoService.delete(id);
        redirectAttributes.addFlashAttribute("message",
                "Concurso apagado. Cronogramas, aulas e simulados dele continuam, sem concurso.");
        return "redirect:/concursos";
    }

    /** Troca o concurso ativo (vazio = todos) e volta para a tela de onde veio. */
    @PostMapping("/workspace")
    public String select(@RequestParam(required = false) Long concurso,
                         @RequestHeader(value = "Referer", required = false) String referer) {
        workspace.select(concurso);
        return "redirect:" + StudyController.sameSite(referer, "/hoje");
    }

    // ---- cronogramas ----

    @GetMapping("/estudos/cronogramas")
    public String cronogramas(Model model) {
        model.addAttribute("cronogramas", concursoService.cronogramas());
        model.addAttribute("counts", concursoService.taskCounts());
        model.addAttribute("concursos", concursoService.byId());
        return "estudos-cronogramas";
    }

    @PostMapping({"/estudos/cronogramas", "/estudos/cronogramas/{id}/editar"})
    public String saveCronograma(@PathVariable(required = false) Long id,
                                 @RequestParam(defaultValue = "") String nome,
                                 @RequestParam(required = false) Long concursoId,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim,
                                 @RequestParam(defaultValue = "false") boolean ativo,
                                 @RequestHeader(value = "Referer", required = false) String referer,
                                 RedirectAttributes redirectAttributes) {
        try {
            concursoService.saveCronograma(id, concursoId, nome, inicio, fim, ativo);
            redirectAttributes.addFlashAttribute("message", id == null ? "Cronograma criado." : "Cronograma atualizado.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + StudyController.sameSite(referer, "/estudos/cronogramas");
    }

    @PostMapping("/estudos/cronogramas/{id}/excluir")
    public String deleteCronograma(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        concursoService.deleteCronograma(id);
        redirectAttributes.addFlashAttribute("message", "Cronograma apagado. As tarefas dele ficaram avulsas.");
        return "redirect:/estudos/cronogramas";
    }
}
