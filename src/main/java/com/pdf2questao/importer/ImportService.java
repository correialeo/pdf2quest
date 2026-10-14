package com.pdf2questao.importer;

import com.pdf2questao.importer.ocr.OcrService;
import com.pdf2questao.importer.parser.ExamParser;
import com.pdf2questao.importer.parser.ExamParserRegistry;
import com.pdf2questao.importer.parser.GabaritoEntries;
import com.pdf2questao.importer.parser.GabaritoParserRegistry;
import com.pdf2questao.importer.parser.GabaritoTableParser;
import com.pdf2questao.importer.parser.support.PdfRichTextExtractor;
import com.pdf2questao.importer.parser.support.RichText;
import com.pdf2questao.question.Passage;
import com.pdf2questao.question.PassageRepository;
import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionCategory;
import com.pdf2questao.question.QuestionImage;
import com.pdf2questao.question.QuestionImageRepository;
import com.pdf2questao.question.QuestionRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class ImportService {

    private final QuestionRepository questionRepository;
    private final ImportJobRepository importJobRepository;
    private final ExamParserRegistry examParserRegistry;
    private final GabaritoParserRegistry gabaritoParserRegistry;
    private final PassageRepository passageRepository;
    private final QuestionImageRepository questionImageRepository;
    private final PdfStorage pdfStorage;
    private final SpecificSubjectClassifier subjectClassifier;
    private final OcrService ocrService;
    /** Uma importacao por vez: o OCR ja usa todos os nucleos, e o SQLite tem uma conexao so. */
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "import-pdf");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, OcrService.Progress> progress = new ConcurrentHashMap<>();

    public ImportService(QuestionRepository questionRepository, ImportJobRepository importJobRepository,
                          ExamParserRegistry examParserRegistry, GabaritoParserRegistry gabaritoParserRegistry,
                          PassageRepository passageRepository, QuestionImageRepository questionImageRepository,
                          PdfStorage pdfStorage, SpecificSubjectClassifier subjectClassifier,
                          OcrService ocrService) {
        this.questionRepository = questionRepository;
        this.importJobRepository = importJobRepository;
        this.examParserRegistry = examParserRegistry;
        this.gabaritoParserRegistry = gabaritoParserRegistry;
        this.passageRepository = passageRepository;
        this.questionImageRepository = questionImageRepository;
        this.pdfStorage = pdfStorage;
        this.subjectClassifier = subjectClassifier;
        this.ocrService = ocrService;
    }

    public ImportJob importPdf(MultipartFile file, Banca banca, String examOverride, String organizationOverride,
                                Integer yearOverride) {
        return importPdf(file, banca, examOverride, organizationOverride, yearOverride, null);
    }

    /**
     * @param sectionMapJson mapa de disciplinas por faixa de questoes (ver {@link SectionMap}); tem
     *                       prioridade sobre os titulos de secao detectados no PDF. Opcional.
     */
    public ImportJob importPdf(MultipartFile file, Banca banca, String examOverride, String organizationOverride,
                                Integer yearOverride, String sectionMapJson) {
        ImportJob job = newJob(file.getOriginalFilename(), banca);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            return fail(job, "Falha ao ler o PDF: " + e.getMessage());
        }
        return process(job, bytes, banca, examOverride, organizationOverride, yearOverride, sectionMapJson);
    }

    /**
     * Inicia a importacao em segundo plano e devolve na hora a importacao "em andamento" (sem finishedAt),
     * para a tela acompanhar o progresso do OCR. Le os bytes ja aqui: o arquivo do upload some no fim da requisicao.
     */
    public ImportJob startImport(MultipartFile file, Banca banca, String examOverride, String organizationOverride,
                                 Integer yearOverride, String sectionMapJson) throws IOException {
        byte[] bytes = file.getBytes();
        ImportJob job = importJobRepository.save(newJob(file.getOriginalFilename(), banca));
        Long id = job.getId();
        progress.put(id, new OcrService.Progress(0, 0, 0));
        importExecutor.submit(() -> {
            try {
                process(job, bytes, banca, examOverride, organizationOverride, yearOverride, sectionMapJson);
            } catch (RuntimeException e) {
                fail(job, "Falha inesperada na importacao: " + e);
            } finally {
                progress.remove(id);
            }
        });
        return job;
    }

    /** Paginas lidas pelo OCR da importacao em andamento (vazio se ja terminou ou nao usa OCR ainda). */
    public Optional<OcrService.Progress> progress(Long jobId) {
        return Optional.ofNullable(progress.get(jobId));
    }

    /**
     * Importacoes que estavam em andamento quando o servidor caiu nunca vao terminar: fecha como falha
     * ao subir, para nao ficarem "processando" para sempre.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void closeInterruptedImports() {
        importJobRepository.findAll().stream().filter(j -> j.getFinishedAt() == null)
                .forEach(j -> fail(j, "Importacao interrompida (o servidor foi reiniciado). Importe o PDF de novo."));
    }

    @PreDestroy
    void shutdownExecutor() {
        importExecutor.shutdownNow();
    }

    private ImportJob newJob(String fileName, Banca banca) {
        ImportJob job = new ImportJob();
        job.setFileName(fileName);
        job.setBanca(banca.name());
        job.setStartedAt(LocalDateTime.now());
        return job;
    }

    private ImportJob fail(ImportJob job, String message) {
        job.setImportedQuestions(0);
        job.setFailedQuestions(0);
        job.setErrorLog(message);
        job.setFinishedAt(LocalDateTime.now());
        return importJobRepository.save(job);
    }

    private ImportJob process(ImportJob job, byte[] bytes, Banca banca, String examOverride,
                              String organizationOverride, Integer yearOverride, String sectionMapJson) {
        String fileName = job.getFileName();
        ExamParser parser = examParserRegistry.find(banca).orElse(null);
        if (parser == null) {
            return fail(job, "Banca ainda nao suportada: " + banca.getLabel());
        }

        SectionMap sectionMap;
        try {
            sectionMap = SectionMap.parse(sectionMapJson);
        } catch (IllegalArgumentException e) {
            return fail(job, e.getMessage());
        }

        PdfRichTextExtractor.Extraction extraction;
        boolean usedOcr;
        try {
            usedOcr = ocrService.needsOcr(bytes);
            extraction = usedOcr ? ocr(bytes, job.getId()) : extract(bytes);
        } catch (IOException e) {
            return fail(job, "Falha ao ler o PDF: " + e.getMessage());
        }

        ParseResult result = parser.parse(extraction.text());

        String organization = hasText(organizationOverride) ? organizationOverride : banca.getLabel();
        Integer year = yearOverride != null ? yearOverride : result.year();

        job.setImportedQuestions(0);
        job.setFailedQuestions(result.failures().size());
        job.setErrorLog(failureLog(result));
        job.setDetectedOrganization(result.organization());
        job.setDetectedYear(result.year());
        job.setTitle(hasText(examOverride) ? examOverride.trim() : result.title());
        job.setReport(ImportReport.forExam(fileName, result, sectionMap, usedOcr));
        ImportJob savedJob = importJobRepository.save(job);
        storePdf(savedJob, bytes);

        Attachments attachments = saveAttachments(savedJob.getId(), extraction, result);
        List<Question> toSave = result.questions().stream()
                .map(pq -> {
                    Question q = new Question();
                    q.setCorrectAnswer(pq.correctAnswer());
                    q.setQuestionNumber(pq.number());
                    q.setCategory(pq.category());
                    q.setOrganization(organization);
                    q.setYear(year);
                    q.setExam(examOverride);
                    q.setSource(fileName);
                    q.setImportJobId(savedJob.getId());
                    applyParsedContent(q, pq, attachments);
                    var section = sectionMap.find(pq.number());
                    q.setSubject(section.map(SectionMap.Section::subject).orElseGet(() -> subjectFor(pq)));
                    section.map(SectionMap.Section::topic).ifPresent(q::setTopic);
                    return q;
                })
                .collect(Collectors.toList());
        questionRepository.saveAll(toSave);

        savedJob.setImportedQuestions(toSave.size());
        // So agora a importacao conta como terminada: a tela de progresso espera as questoes estarem salvas.
        savedJob.setFinishedAt(LocalDateTime.now());
        return importJobRepository.save(savedJob);
    }

    /**
     * Atualiza no lugar as questoes de uma importacao (casando pelo numero), sem
     * mexer em ids, gabarito ou disciplina ja preenchida - simulados e tentativas
     * continuam apontando para as mesmas questoes.
     */
    @Transactional
    public ReprocessResult reprocess(Long importJobId, MultipartFile file) {
        ImportJob job = importJobRepository.findById(importJobId).orElseThrow();
        Banca banca = Banca.valueOf(job.getBanca());
        ExamParser parser = examParserRegistry.find(banca).orElse(null);
        if (parser == null) {
            return ReprocessResult.error("Banca ainda nao suportada: " + banca.getLabel());
        }

        byte[] bytes;
        PdfRichTextExtractor.Extraction extraction;
        try {
            if (file != null && !file.isEmpty()) {
                bytes = file.getBytes();
            } else if (pdfStorage.exists(job)) {
                bytes = pdfStorage.read(job);
            } else {
                return ReprocessResult.error("Esta importacao nao tem PDF guardado - envie o arquivo da prova.");
            }
            extraction = ocrService.needsOcr(bytes) ? ocr(bytes) : extract(bytes);
        } catch (IOException e) {
            return ReprocessResult.error("Falha ao ler o PDF: " + e.getMessage());
        }

        ParseResult result = parser.parse(extraction.text());
        Map<Integer, ParsedQuestion> byNumber = result.questions().stream()
                .collect(Collectors.toMap(ParsedQuestion::number, pq -> pq, (a, b) -> a));
        List<Question> existing = questionRepository.findByImportJobId(importJobId);
        long matches = existing.stream()
                .filter(q -> q.getQuestionNumber() != null && byNumber.containsKey(q.getQuestionNumber()))
                .count();
        if (matches == 0) {
            return ReprocessResult.error("Nenhuma questao do PDF bate com as questoes desta importacao "
                    + "- confira se e o mesmo arquivo.");
        }

        storePdf(job, bytes);
        passageRepository.deleteByImportJobId(importJobId);
        questionImageRepository.deleteByImportJobId(importJobId);
        Attachments attachments = saveAttachments(importJobId, extraction, result);

        int updated = 0;
        int subjectsFilled = 0;
        List<Integer> notFound = new ArrayList<>();
        for (Question q : existing) {
            ParsedQuestion pq = q.getQuestionNumber() == null ? null : byNumber.get(q.getQuestionNumber());
            if (pq == null) {
                notFound.add(q.getQuestionNumber());
                continue;
            }
            applyParsedContent(q, pq, attachments);
            if (!hasText(q.getSubject())) {
                q.setSubject(subjectFor(pq));
                subjectsFilled++;
            }
            updated++;
        }
        questionRepository.saveAll(existing);
        importJobRepository.save(job);

        return new ReprocessResult(updated, subjectsFilled, attachments.imageIds().size(),
                attachments.passageIds().size(), notFound, null);
    }

    /**
     * Corrige os dados de uma prova ja importada (titulo, banca exibida e ano), a pedido do usuario.
     * Atualiza tambem as questoes dela; o enunciado, as alternativas e o gabarito nao sao tocados.
     */
    @Transactional
    public void updateInfo(Long importJobId, String title, String organization, Integer year) {
        ImportJob job = importJobRepository.findById(importJobId).orElseThrow();
        job.setTitle(hasText(title) ? title.trim() : null);
        importJobRepository.save(job);
        List<Question> questions = questionRepository.findByImportJobId(importJobId);
        for (Question q : questions) {
            if (hasText(organization)) {
                q.setOrganization(organization.trim());
            }
            if (year != null) {
                q.setYear(year);
            }
        }
        questionRepository.saveAll(questions);
    }

    private PdfRichTextExtractor.Extraction extract(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return PdfRichTextExtractor.extract(document);
        }
    }

    /** PDF sem camada de texto: o texto vem do OCR (com cache em disco), com recortes das questoes que o OCR nao le bem (formula, tabela). */
    private PdfRichTextExtractor.Extraction ocr(byte[] bytes) throws IOException {
        return ocr(bytes, null);
    }

    private PdfRichTextExtractor.Extraction ocr(byte[] bytes, Long jobId) throws IOException {
        if (!ocrService.isAvailable()) {
            throw new IOException("o PDF nao tem camada de texto e o Tesseract com o idioma configurado "
                    + "(por) nao esta instalado - veja pdf2questao.ocr.* no application.properties");
        }
        OcrService.Result result = ocrService.recognize(bytes, p -> {
            if (jobId != null && progress.containsKey(jobId)) {
                progress.put(jobId, p);
            }
        });
        return new PdfRichTextExtractor.Extraction(result.text(), result.images());
    }

    private void storePdf(ImportJob job, byte[] bytes) {
        try {
            job.setPdfPath(pdfStorage.store(job.getId(), bytes));
            importJobRepository.save(job);
        } catch (IOException e) {
            // Sem o PDF guardado a importacao continua valida, so perde o atalho de consulta.
            job.setErrorLog(appendLine(job.getErrorLog(), "Nao foi possivel guardar o PDF: " + e.getMessage()));
        }
    }

    private record Attachments(Map<Integer, Long> imageIds, Map<String, Long> passageIds) {
    }

    private Attachments saveAttachments(Long importJobId, PdfRichTextExtractor.Extraction extraction,
                                        ParseResult result) {
        String allText = result.questions().stream()
                .flatMap(pq -> Stream.of(pq.statement(), pq.alternativeA(), pq.alternativeB(), pq.alternativeC(),
                        pq.alternativeD(), pq.alternativeE(), pq.passage()))
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));

        Map<Integer, Long> imageIds = new HashMap<>();
        for (PdfRichTextExtractor.ExtractedImage img : extraction.images()) {
            if (!allText.contains(RichText.image(img.index()))) {
                continue;
            }
            QuestionImage entity = new QuestionImage();
            entity.setImportJobId(importJobId);
            entity.setPage(img.page());
            entity.setData(img.png());
            imageIds.put(img.index(), questionImageRepository.save(entity).getId());
        }

        Map<String, Long> passageIds = new LinkedHashMap<>();
        for (ParsedQuestion pq : result.questions()) {
            if (pq.passage() == null || passageIds.containsKey(pq.passage())) {
                continue;
            }
            Passage passage = new Passage();
            passage.setImportJobId(importJobId);
            passage.setContent(remapImages(pq.passage(), imageIds));
            passageIds.put(pq.passage(), passageRepository.save(passage).getId());
        }
        return new Attachments(imageIds, passageIds);
    }

    private void applyParsedContent(Question q, ParsedQuestion pq, Attachments attachments) {
        Map<Integer, Long> images = attachments.imageIds();
        q.setStatement(remapImages(pq.statement(), images));
        q.setAlternativeA(remapImages(pq.alternativeA(), images));
        q.setAlternativeB(remapImages(pq.alternativeB(), images));
        q.setAlternativeC(remapImages(pq.alternativeC(), images));
        q.setAlternativeD(remapImages(pq.alternativeD(), images));
        q.setAlternativeE(remapImages(pq.alternativeE(), images));
        q.setPage(pq.page());
        q.setPassageId(pq.passage() == null ? null : attachments.passageIds().get(pq.passage()));
        q.setNeedsReview(pq.needsReview());
        q.setReviewReason(pq.reviewReason());
    }

    private String remapImages(String rich, Map<Integer, Long> imageIds) {
        return RichText.remapImages(rich, i -> imageIds.containsKey(i) ? imageIds.get(i).intValue() : -1);
    }

    private String subjectFor(ParsedQuestion pq) {
        if (hasText(pq.subject()) || pq.category() != QuestionCategory.ESPECIFICO) {
            return pq.subject();
        }
        return subjectClassifier.classify(RichText.plain(String.join("\n", Stream.of(pq.statement(),
                pq.alternativeA(), pq.alternativeB(), pq.alternativeC(), pq.alternativeD(), pq.alternativeE())
                .filter(Objects::nonNull).toList())));
    }

    private String failureLog(ParseResult result) {
        return result.failures().stream()
                .map(f -> "Questao " + f.number() + ": " + f.reason())
                .collect(Collectors.joining("\n"));
    }

    private String appendLine(String log, String line) {
        return hasText(log) ? log + "\n" + line : line;
    }

    public GabaritoLinkResult linkGabarito(MultipartFile file, Banca banca, String titulo, Long importJobId) {
        GabaritoTableParser gabaritoParser = gabaritoParserRegistry.find(banca).orElse(null);
        if (gabaritoParser == null) {
            return new GabaritoLinkResult(0, 0, 0, List.of(), "Banca ainda nao suportada: " + banca.getLabel());
        }

        String text;
        try {
            byte[] bytes = file.getBytes();
            try (PDDocument document = Loader.loadPDF(bytes)) {
                text = new PDFTextStripper().getText(document);
            }
        } catch (IOException e) {
            return new GabaritoLinkResult(0, 0, 0, List.of(), "Falha ao ler o PDF: " + e.getMessage());
        }

        GabaritoEntries entries = gabaritoParser.parseEntries(text, titulo);
        Map<Integer, String> answers = entries.answers();
        if (entries.totalRead() == 0) {
            return new GabaritoLinkResult(0, 0, 0, List.of(),
                    "Titulo nao encontrado no PDF de gabarito: " + titulo);
        }

        List<Question> questions = questionRepository.findByImportJobId(importJobId);

        int matched = 0;
        List<Integer> unmatched = new ArrayList<>();
        List<Integer> annulled = new ArrayList<>();
        for (Question q : questions) {
            Integer number = q.getQuestionNumber();
            if (number != null && entries.annulled().contains(number)) {
                q.setAnnulled(true);
                q.setCorrectAnswer(null);
                annulled.add(number);
                matched++;
                continue;
            }
            String letter = number != null ? answers.get(number) : null;
            if (letter != null) {
                q.setCorrectAnswer(letter);
                q.setAnnulled(false);
                matched++;
            } else {
                unmatched.add(number);
            }
        }
        questionRepository.saveAll(questions);

        List<String> report = ImportReport.gabaritoLines(entries, questions.stream()
                .map(Question::getQuestionNumber).filter(Objects::nonNull).toList());
        importJobRepository.findById(importJobId).ifPresent(job -> {
            job.setReport(appendLine(job.getReport(), String.join("\n", report)));
            importJobRepository.save(job);
        });

        return new GabaritoLinkResult(matched, entries.totalRead(), questions.size(), unmatched, null,
                annulled, report);
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
