package com.pdf2questao.importer;

import com.pdf2questao.importer.parser.ExamParserRegistry;
import com.pdf2questao.importer.parser.GabaritoParserRegistry;
import com.pdf2questao.question.Passage;
import com.pdf2questao.question.PassageRepository;
import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionRepository;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Controller
public class ImportController {

    private final ImportService importService;
    private final ImportJobRepository importJobRepository;
    private final ExamParserRegistry examParserRegistry;
    private final GabaritoParserRegistry gabaritoParserRegistry;
    private final PdfStorage pdfStorage;
    private final QuestionRepository questionRepository;
    private final PassageRepository passageRepository;

    public ImportController(ImportService importService, ImportJobRepository importJobRepository,
                             ExamParserRegistry examParserRegistry, GabaritoParserRegistry gabaritoParserRegistry,
                             PdfStorage pdfStorage, QuestionRepository questionRepository,
                             PassageRepository passageRepository) {
        this.importService = importService;
        this.importJobRepository = importJobRepository;
        this.examParserRegistry = examParserRegistry;
        this.gabaritoParserRegistry = gabaritoParserRegistry;
        this.pdfStorage = pdfStorage;
        this.questionRepository = questionRepository;
        this.passageRepository = passageRepository;
    }

    @GetMapping("/import")
    public String form(Model model) {
        model.addAttribute("bancas", Banca.values());
        model.addAttribute("supportedBancas", examParserRegistry.supportedBancas());
        model.addAttribute("jobs", importJobRepository.findAllByOrderByStartedAtDesc());
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
        model.addAttribute("hasPdf", pdfStorage.exists(job));
        return "import-result";
    }

    @GetMapping("/import/{id}/questoes")
    public String questions(@PathVariable Long id, Model model) {
        ImportJob job = importJobRepository.findById(id).orElseThrow();
        List<Question> questions = questionRepository.findByImportJobId(id).stream()
                .sorted(Comparator.comparing(Question::getQuestionNumber,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        Map<Long, Passage> passages = passageRepository.findAllById(questions.stream()
                        .map(Question::getPassageId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(Passage::getId, p -> p));
        model.addAttribute("job", job);
        model.addAttribute("questions", questions);
        model.addAttribute("passages", passages);
        model.addAttribute("hasPdf", pdfStorage.exists(job));
        return "prova-questoes";
    }

    @PostMapping("/import/{id}/reprocessar")
    public String reprocess(@PathVariable Long id,
                            @RequestParam(value = "file", required = false) MultipartFile file,
                            RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("reprocess", importService.reprocess(id, file));
        return "redirect:/import/result/" + id;
    }

    @GetMapping("/import/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) throws IOException {
        ImportJob job = importJobRepository.findById(id).orElseThrow();
        if (!pdfStorage.exists(job)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(job.getFileName() == null ? "prova.pdf" : job.getFileName(), StandardCharsets.UTF_8)
                        .build().toString())
                .body(pdfStorage.read(job));
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
