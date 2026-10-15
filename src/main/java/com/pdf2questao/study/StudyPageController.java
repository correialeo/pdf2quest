package com.pdf2questao.study;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/** Paginas e resumos: lista em arvore, editor com salvamento automatico, subpaginas. */
@Controller
public class StudyPageController {

    private final StudyPageService pageService;
    private final StudyTaskService taskService;
    private final StudySubjects subjects;
    private final DocxImportService docxImportService;

    public StudyPageController(StudyPageService pageService, StudyTaskService taskService, StudySubjects subjects,
                               DocxImportService docxImportService) {
        this.pageService = pageService;
        this.taskService = taskService;
        this.subjects = subjects;
        this.docxImportService = docxImportService;
    }

    @PostMapping("/estudos/importar")
    public String importDocx(@RequestParam(value = "file", required = false) MultipartFile file,
                             RedirectAttributes redirectAttributes) throws IOException {
        if (file == null || file.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Escolha um arquivo do Word (.docx).");
            return "redirect:/estudos/paginas";
        }
        try {
            var preview = docxImportService.preview(file.getOriginalFilename(), file.getBytes(), null);
            return "redirect:/estudos/importar/" + preview.token();
        } catch (DocxReader.InvalidDocxException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/estudos/paginas";
        }
    }

    @GetMapping("/estudos/importar/{token}")
    public String importPreview(@PathVariable String token, Model model, RedirectAttributes redirectAttributes) {
        var preview = docxImportService.get(token);
        if (preview.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Esta prévia expirou. Envie o arquivo de novo.");
            return "redirect:/estudos/paginas";
        }
        model.addAttribute("preview", preview.get());
        model.addAttribute("pages", pageService.flatTree());
        return "estudos-importar";
    }

    @PostMapping("/estudos/importar/{token}")
    public String importConfirm(@PathVariable String token, @RequestParam(defaultValue = "") String title,
                                @RequestParam(required = false) Long parentId,
                                @RequestParam(defaultValue = "false") boolean createTasks,
                                RedirectAttributes redirectAttributes) {
        try {
            var outcome = docxImportService.confirm(token, title, parentId, createTasks);
            redirectAttributes.addFlashAttribute("message", "Documento importado como página"
                    + (outcome.tasksCreated() > 0 ? " e " + outcome.tasksCreated() + " tarefas criadas no cronograma" : "")
                    + ".");
            return "redirect:/estudos/paginas/" + outcome.pageId();
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/estudos/paginas";
        }
    }

    @GetMapping("/estudos/paginas")
    public String list(@RequestParam(required = false) String disciplina, Model model) {
        model.addAttribute("tree", pageService.flatTree());
        model.addAttribute("subjects", subjects.all());
        model.addAttribute("disciplina", disciplina == null || disciplina.isBlank() ? null : disciplina);
        return "estudos-paginas";
    }

    @PostMapping("/estudos/paginas")
    public String create(@RequestParam(required = false) Long parentId,
                         @RequestParam(defaultValue = "") String title,
                         @RequestParam(required = false) String subject) {
        StudyPage page = pageService.create(parentId, title, subject, null);
        return "redirect:/estudos/paginas/" + page.getId();
    }

    @GetMapping("/estudos/paginas/{id}")
    public String page(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes,
                       jakarta.servlet.http.HttpServletResponse response) {
        // Sem cache: ao voltar pelo navegador, o editor tem que receber a versao atual da pagina, senao a proxima
        // gravacao automatica e recusada como se outra aba tivesse alterado.
        response.setHeader("Cache-Control", "no-store");
        var page = pageService.find(id);
        if (page.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Esta página não existe mais.");
            return "redirect:/estudos/paginas";
        }
        model.addAttribute("page", page.get());
        model.addAttribute("breadcrumbs", pageService.breadcrumbs(page.get()));
        model.addAttribute("children", pageService.children(id));
        model.addAttribute("tree", pageService.flatTree());
        model.addAttribute("descendants", pageService.descendantIds(id));
        model.addAttribute("tasks", taskService.forPage(id));
        model.addAttribute("subjects", subjects.all());
        return "estudos-pagina";
    }

    /** Corpo do salvamento automatico: {"title": ..., "content": "<p>...</p>", "version": 3}. */
    public record SaveRequest(String title, String content, Integer version) {
    }

    @PutMapping("/estudos/paginas/{id}/conteudo")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> save(@PathVariable Long id, @RequestBody SaveRequest request) {
        if (pageService.find(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Esta página foi apagada."));
        }
        try {
            var result = pageService.saveContent(id, request.title(), request.content(),
                    request.version() == null ? -1 : request.version());
            return ResponseEntity.ok(Map.of("version", result.version(), "savedAt", result.savedAt().toString()));
        } catch (StudyPageService.StaleVersionException e) {
            // Devolve o que esta gravado: o editor compara e, se o conteudo for o mesmo, so adota a versao.
            StudyPage current = pageService.find(id).orElseThrow();
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message", "Esta página foi alterada em outra aba ou janela.", "version", e.getCurrentVersion(),
                    "title", current.getTitle() == null ? "" : current.getTitle(),
                    "content", current.getContent() == null ? "" : current.getContent()));
        }
    }

    /** Mesmo salvamento via navigator.sendBeacon (so faz POST), usado ao fechar a aba com alteracoes pendentes. */
    @PostMapping("/estudos/paginas/{id}/conteudo")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveBeacon(@PathVariable Long id, @RequestBody SaveRequest request) {
        return save(id, request);
    }

    @PostMapping("/estudos/paginas/{id}/detalhes")
    public String details(@PathVariable Long id, @RequestParam(required = false) String subject,
                          @RequestParam(required = false) Long parentId, RedirectAttributes redirectAttributes) {
        try {
            pageService.updateDetails(id, subject, parentId);
            redirectAttributes.addFlashAttribute("message", "Página atualizada.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/estudos/paginas/" + id;
    }

    @PostMapping("/estudos/paginas/{id}/mover")
    public String move(@PathVariable Long id, @RequestParam int direcao,
                       @RequestParam(defaultValue = "/estudos/paginas") String voltar) {
        pageService.move(id, direcao);
        return "redirect:" + StudyController.sameSite("http://x" + voltar, "/estudos/paginas");
    }

    @PostMapping("/estudos/paginas/{id}/excluir")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        Long parentId = pageService.find(id).map(StudyPage::getParentId).orElse(null);
        int deleted = pageService.delete(id);
        redirectAttributes.addFlashAttribute("message", deleted == 1 ? "Página apagada."
                : "Página e " + (deleted - 1) + " subpáginas apagadas.");
        return parentId != null ? "redirect:/estudos/paginas/" + parentId : "redirect:/estudos/paginas";
    }
}
