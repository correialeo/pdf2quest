package com.concursos.study.importer;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ImportController {

    private final ImportService importService;
    private final ImportJobRepository importJobRepository;

    public ImportController(ImportService importService, ImportJobRepository importJobRepository) {
        this.importService = importService;
        this.importJobRepository = importJobRepository;
    }

    @GetMapping("/import")
    public String form() {
        return "import";
    }

    @PostMapping("/import")
    public String upload(@RequestParam("file") MultipartFile file,
                          @RequestParam(value = "exam", required = false) String exam,
                          @RequestParam(value = "organizationOverride", required = false) String organizationOverride,
                          @RequestParam(value = "yearOverride", required = false) Integer yearOverride,
                          RedirectAttributes redirectAttributes) {
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Selecione um arquivo PDF.");
            return "redirect:/import";
        }
        ImportJob job = importService.importPdf(file, exam, organizationOverride, yearOverride);
        return "redirect:/import/result/" + job.getId();
    }

    @GetMapping("/import/result/{id}")
    public String result(@PathVariable Long id, Model model) {
        ImportJob job = importJobRepository.findById(id).orElseThrow();
        model.addAttribute("job", job);
        return "import-result";
    }
}
