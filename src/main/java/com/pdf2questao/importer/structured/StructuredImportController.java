package com.pdf2questao.importer.structured;

import com.pdf2questao.importer.ImportJobRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.Map;

/** Telas da importacao por arquivo (JSON, CSV) e do gabarito separado em arquivo. */
@Controller
public class StructuredImportController {

    private final StructuredImportService importService;
    private final AnswerKeyService answerKeyService;
    private final ImportJobRepository importJobRepository;

    public StructuredImportController(StructuredImportService importService, AnswerKeyService answerKeyService,
                                      ImportJobRepository importJobRepository) {
        this.importService = importService;
        this.answerKeyService = answerKeyService;
        this.importJobRepository = importJobRepository;
    }

    @GetMapping("/import/arquivo")
    public String form() {
        return "import-arquivo";
    }

    @PostMapping("/import/json")
    public String uploadJson(@RequestParam("file") MultipartFile file, RedirectAttributes redirectAttributes)
            throws IOException {
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Escolha o arquivo JSON.");
            return "redirect:/import/arquivo";
        }
        ImportPreview preview = importService.previewJson(file.getOriginalFilename(), file.getBytes());
        return "redirect:/import/previa/" + preview.getToken();
    }

    /** JSON colado (ex.: copiado do chat do Claude no fim de um treino). */
    @PostMapping("/import/json-texto")
    public String pasteJson(@RequestParam(defaultValue = "") String json, RedirectAttributes redirectAttributes) {
        String text = extractJson(json);
        if (text.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Cole o JSON das questões.");
            return "redirect:/import/arquivo";
        }
        ImportPreview preview = importService.previewJson("treino-" + java.time.LocalDate.now() + ".json",
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "redirect:/import/previa/" + preview.getToken();
    }

    /** Tira o que vier em volta do JSON ao copiar do chat (```json ... ```, texto antes ou depois). */
    static String extractJson(String pasted) {
        int start = pasted.indexOf('{');
        int end = pasted.lastIndexOf('}');
        return start < 0 || end < start ? pasted.trim() : pasted.substring(start, end + 1);
    }

    @PostMapping("/import/csv")
    public String uploadCsv(@RequestParam("file") MultipartFile file, RedirectAttributes redirectAttributes)
            throws IOException {
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Escolha a planilha (arquivo .csv).");
            return "redirect:/import/arquivo";
        }
        CsvUpload upload = importService.uploadCsv(file.getOriginalFilename(), file.getBytes());
        if (upload.table().rows().isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "A planilha está vazia.");
            return "redirect:/import/arquivo";
        }
        return "redirect:/import/csv/" + upload.token();
    }

    @GetMapping("/import/csv/{token}")
    public String mapping(@PathVariable String token, @RequestParam(required = false) String delimitador,
                          Model model, RedirectAttributes redirectAttributes) {
        var upload = delimitador != null && !delimitador.isEmpty()
                ? importService.rereadCsv(token, delimiter(delimitador)) : importService.csvUpload(token);
        if (upload.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Esta planilha expirou. Envie o arquivo de novo.");
            return "redirect:/import/arquivo";
        }
        model.addAttribute("upload", upload.get());
        model.addAttribute("mapping", upload.get().mapping());
        model.addAttribute("fields", CsvMapping.Field.values());
        model.addAttribute("delimiters", Map.of(";", "Ponto e vírgula (;)", ",", "Vírgula (,)",
                "tab", "Tabulação", "|", "Barra vertical (|)"));
        model.addAttribute("delimiterValue", delimiterValue(upload.get().mapping().getDelimiter()));
        return "import-csv-mapear";
    }

    @PostMapping("/import/csv/{token}")
    public String applyMapping(@PathVariable String token,
                               @RequestParam Map<String, String> params,
                               RedirectAttributes redirectAttributes) {
        CsvMapping mapping = new CsvMapping();
        mapping.setDelimiter(delimiter(params.getOrDefault("delimitador", ";")));
        mapping.setHeader(params.containsKey("cabecalho"));
        mapping.setAlternativesMode("UMA_COLUNA".equals(params.get("modoAlternativas"))
                ? CsvMapping.AlternativesMode.UMA_COLUNA : CsvMapping.AlternativesMode.COLUNAS);
        mapping.setAlternativesSeparator(params.getOrDefault("separadorAlternativas", "|"));
        for (CsvMapping.Field field : CsvMapping.Field.values()) {
            String value = params.get("col_" + field.name());
            if (value != null && value.matches("\\d+")) {
                mapping.map(field, Integer.parseInt(value));
            }
        }
        Integer year = params.getOrDefault("ano", "").matches("\\d{4}") ? Integer.parseInt(params.get("ano")) : null;
        var preview = importService.previewCsv(token, mapping, params.get("banca"), params.get("concurso"), year,
                params.get("disciplina"));
        if (preview.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Esta planilha expirou. Envie o arquivo de novo.");
            return "redirect:/import/arquivo";
        }
        return "redirect:/import/previa/" + preview.get().getToken() + "?csv=" + token;
    }

    @GetMapping("/import/previa/{token}")
    public String preview(@PathVariable String token, @RequestParam(required = false) String csv, Model model,
                          RedirectAttributes redirectAttributes) {
        var preview = importService.preview(token);
        if (preview.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Esta prévia expirou. Envie o arquivo de novo.");
            return "redirect:/import/arquivo";
        }
        model.addAttribute("preview", preview.get());
        model.addAttribute("csvToken", csv);
        return "import-previa";
    }

    @PostMapping("/import/previa/{token}/confirmar")
    public String confirm(@PathVariable String token,
                          @RequestParam(defaultValue = "") String title,
                          @RequestParam(defaultValue = "false") boolean updateExisting,
                          @RequestParam(defaultValue = "false") boolean includeDuplicates,
                          @RequestParam(defaultValue = "false") boolean acceptInvalid,
                          @RequestParam(required = false) String csv,
                          RedirectAttributes redirectAttributes) {
        ImportOutcome outcome = importService.confirm(token,
                new ImportOptions(title, updateExisting, includeDuplicates, acceptInvalid));
        if (outcome.error() != null) {
            redirectAttributes.addFlashAttribute("error", outcome.error());
            return importService.preview(token).isPresent()
                    ? "redirect:/import/previa/" + token + (csv != null ? "?csv=" + csv : "") : "redirect:/import/arquivo";
        }
        redirectAttributes.addFlashAttribute("info", outcome.created() + " questões importadas"
                + (outcome.updated() > 0 ? ", " + outcome.updated() + " atualizadas" : "")
                + (outcome.skipped() > 0 ? ", " + outcome.skipped() + " não importadas (detalhes no relatório)" : "")
                + ".");
        if (outcome.trainingSessionId() != null) {
            // Treino com as respostas do usuario: o que interessa e o resultado (acertos, erros, disciplinas).
            redirectAttributes.addFlashAttribute("message", "Treino importado: " + outcome.created()
                    + " questões novas. Os erros já estão no caderno de erros.");
            return "redirect:/resultado/" + outcome.trainingSessionId();
        }
        return "redirect:/import/result/" + outcome.importJobId();
    }

    @PostMapping("/import/previa/{token}/cancelar")
    public String cancel(@PathVariable String token) {
        importService.discard(token);
        return "redirect:/import/arquivo";
    }

    @GetMapping("/import/gabarito/arquivo")
    public String answerKeyForm(Model model) {
        model.addAttribute("jobs", importJobRepository.findAllByOrderByStartedAtDesc());
        return "import-gabarito-arquivo";
    }

    @PostMapping("/import/gabarito/arquivo")
    public String answerKeyUpload(@RequestParam(value = "file", required = false) MultipartFile file,
                                  @RequestParam(defaultValue = "") String texto,
                                  @RequestParam(defaultValue = "ID") AnswerKeyPreview.Mode modo,
                                  @RequestParam(required = false) Long importJobId,
                                  RedirectAttributes redirectAttributes) throws IOException {
        byte[] content = file != null && !file.isEmpty() ? file.getBytes()
                : texto.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (content.length == 0 || new String(content, java.nio.charset.StandardCharsets.UTF_8).isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Escolha o arquivo do gabarito ou cole as respostas.");
            return "redirect:/import/gabarito/arquivo";
        }
        String name = file != null && !file.isEmpty() ? file.getOriginalFilename() : "texto colado";
        AnswerKeyPreview preview = answerKeyService.preview(name, content, modo,
                modo == AnswerKeyPreview.Mode.NUMERO ? importJobId : null);
        return "redirect:/import/gabarito/previa/" + preview.getToken();
    }

    @GetMapping("/import/gabarito/previa/{token}")
    public String answerKeyPreview(@PathVariable String token, Model model, RedirectAttributes redirectAttributes) {
        var preview = answerKeyService.get(token);
        if (preview.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Esta prévia expirou. Envie o gabarito de novo.");
            return "redirect:/import/gabarito/arquivo";
        }
        model.addAttribute("preview", preview.get());
        if (preview.get().getImportJobId() != null) {
            importJobRepository.findById(preview.get().getImportJobId()).ifPresent(j -> model.addAttribute("job", j));
        }
        return "import-gabarito-previa";
    }

    @PostMapping("/import/gabarito/previa/{token}/confirmar")
    public String answerKeyConfirm(@PathVariable String token,
                                   @RequestParam(defaultValue = "false") boolean replaceConflicts,
                                   @RequestParam(defaultValue = "false") boolean confirmedNumbering,
                                   RedirectAttributes redirectAttributes) {
        ImportOutcome outcome = answerKeyService.apply(token, replaceConflicts, confirmedNumbering);
        if (outcome.error() != null) {
            redirectAttributes.addFlashAttribute("error", outcome.error());
            return answerKeyService.get(token).isPresent()
                    ? "redirect:/import/gabarito/previa/" + token : "redirect:/import/gabarito/arquivo";
        }
        String message = outcome.created() + " respostas gravadas"
                + (outcome.skipped() > 0 ? "; " + outcome.skipped() + " não aplicadas" : "") + ".";
        redirectAttributes.addFlashAttribute("message", message);
        redirectAttributes.addFlashAttribute("info", message);
        return outcome.importJobId() != null ? "redirect:/import/result/" + outcome.importJobId()
                : "redirect:/import/gabarito/arquivo";
    }

    private static char delimiter(String value) {
        return "tab".equals(value) || "\t".equals(value) ? '\t' : value.charAt(0);
    }

    private static String delimiterValue(char d) {
        return d == '\t' ? "tab" : String.valueOf(d);
    }
}
