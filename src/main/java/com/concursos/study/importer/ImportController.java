package com.concursos.study.importer;

import com.concursos.study.importer.parser.ExamParserRegistry;
import com.concursos.study.importer.parser.GabaritoParserRegistry;
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
    private final ExamParserRegistry examParserRegistry;
    private final GabaritoParserRegistry gabaritoParserRegistry;

    public ImportController(ImportService importService, ImportJobRepository importJobRepository,
                             ExamParserRegistry examParserRegistry, GabaritoParserRegistry gabaritoParserRegistry) {
        this.importService = importService;
        this.importJobRepository = importJobRepository;
        this.examParserRegistry = examParserRegistry;
        this.gabaritoParserRegistry = gabaritoParserRegistry;
    }

    @GetMapping("/import")
    public String form(Model model) {
        model.addAttribute("bancas", Banca.values());
        model.addAttribute("supportedBancas", examParserRegistry.supportedBancas());
        return "import";
    }

    @PostMapping("/import")
    public String upload(@RequestParam("file") MultipartFile file,
                          @RequestParam("banca") Banca banca,
                          @RequestParam(value = "exam", required = false) String exam,
                          @RequestParam(value = "organizationOverride", required = false) String organizationOverride,
                          @RequestParam(value = "yearOverride", required = false) Integer yearOverride,
                          RedirectAttributes redirectAttributes) {
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Selecione um arquivo PDF.");
            return "redirect:/import";
        }
        ImportJob job = importService.importPdf(file, banca, exam, organizationOverride, yearOverride);
        return "redirect:/import/result/" + job.getId();
    }

    @GetMapping("/import/result/{id}")
    public String result(@PathVariable Long id, Model model) {
        ImportJob job = importJobRepository.findById(id).orElseThrow();
        model.addAttribute("job", job);
        return "import-result";
    }

    @GetMapping("/import/gabarito")
    public String gabaritoForm(Model model) {
        model.addAttribute("jobs", importJobRepository.findAllByOrderByStartedAtDesc());
        model.addAttribute("bancas", Banca.values());
        model.addAttribute("supportedBancas", gabaritoParserRegistry.supportedBancas());
        return "import-gabarito";
    }

    @PostMapping("/import/gabarito")
    public String gabaritoUpload(@RequestParam("file") MultipartFile file,
                                  @RequestParam("banca") Banca banca,
                                  @RequestParam("titulo") String titulo,
                                  @RequestParam("importJobId") Long importJobId,
                                  RedirectAttributes redirectAttributes) {
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Selecione um arquivo PDF de gabarito.");
            return "redirect:/import/gabarito";
        }
        GabaritoLinkResult result = importService.linkGabarito(file, banca, titulo, importJobId);
        redirectAttributes.addFlashAttribute("result", result);
        redirectAttributes.addFlashAttribute("titulo", titulo);
        return "redirect:/import/gabarito/resultado";
    }

    @GetMapping("/import/gabarito/resultado")
    public String gabaritoResult(Model model) {
        if (!model.containsAttribute("result")) {
            return "redirect:/import/gabarito";
        }
        return "import-gabarito-resultado";
    }
}
