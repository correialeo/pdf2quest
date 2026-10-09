package com.pdf2questao.importer;

import com.pdf2questao.importer.parser.ExamParser;
import com.pdf2questao.importer.parser.ExamParserRegistry;
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

    public ImportService(QuestionRepository questionRepository, ImportJobRepository importJobRepository,
                          ExamParserRegistry examParserRegistry, GabaritoParserRegistry gabaritoParserRegistry,
                          PassageRepository passageRepository, QuestionImageRepository questionImageRepository,
                          PdfStorage pdfStorage, SpecificSubjectClassifier subjectClassifier) {
        this.questionRepository = questionRepository;
        this.importJobRepository = importJobRepository;
        this.examParserRegistry = examParserRegistry;
        this.gabaritoParserRegistry = gabaritoParserRegistry;
        this.passageRepository = passageRepository;
        this.questionImageRepository = questionImageRepository;
        this.pdfStorage = pdfStorage;
        this.subjectClassifier = subjectClassifier;
    }

    public ImportJob importPdf(MultipartFile file, Banca banca, String examOverride, String organizationOverride,
                                Integer yearOverride) {
        ImportJob job = new ImportJob();
        job.setFileName(file.getOriginalFilename());
        job.setBanca(banca.name());
        job.setStartedAt(LocalDateTime.now());

        ExamParser parser = examParserRegistry.find(banca).orElse(null);
        if (parser == null) {
            job.setImportedQuestions(0);
            job.setFailedQuestions(0);
            job.setErrorLog("Banca ainda nao suportada: " + banca.getLabel());
            job.setFinishedAt(LocalDateTime.now());
            return importJobRepository.save(job);
        }

        byte[] bytes;
        PdfRichTextExtractor.Extraction extraction;
        try {
            bytes = file.getBytes();
            extraction = extract(bytes);
        } catch (IOException e) {
            job.setImportedQuestions(0);
            job.setFailedQuestions(0);
            job.setErrorLog("Falha ao ler o PDF: " + e.getMessage());
            job.setFinishedAt(LocalDateTime.now());
            return importJobRepository.save(job);
        }

        ParseResult result = parser.parse(extraction.text());

        String organization = hasText(organizationOverride) ? organizationOverride : banca.getLabel();
        Integer year = yearOverride != null ? yearOverride : result.year();

        job.setImportedQuestions(0);
        job.setFailedQuestions(result.failures().size());
        job.setErrorLog(failureLog(result));
        job.setDetectedOrganization(result.organization());
        job.setDetectedYear(result.year());
        job.setFinishedAt(LocalDateTime.now());
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
                    q.setSource(file.getOriginalFilename());
                    q.setImportJobId(savedJob.getId());
                    applyParsedContent(q, pq, attachments);
                    q.setSubject(subjectFor(pq));
                    return q;
                })
                .collect(Collectors.toList());
        questionRepository.saveAll(toSave);

        savedJob.setImportedQuestions(toSave.size());
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
            extraction = extract(bytes);
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

    private PdfRichTextExtractor.Extraction extract(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return PdfRichTextExtractor.extract(document);
        }
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

        Map<Integer, String> answers = gabaritoParser.parse(text, titulo);
        if (answers.isEmpty()) {
            return new GabaritoLinkResult(0, 0, 0, List.of(),
                    "Titulo nao encontrado no PDF de gabarito: " + titulo);
        }

        List<Question> questions = questionRepository.findByImportJobId(importJobId);

        int matched = 0;
        List<Integer> unmatched = new ArrayList<>();
        for (Question q : questions) {
            Integer number = q.getQuestionNumber();
            String letter = number != null ? answers.get(number) : null;
            if (letter != null) {
                q.setCorrectAnswer(letter);
                matched++;
            } else {
                unmatched.add(number);
            }
        }
        questionRepository.saveAll(questions);

        return new GabaritoLinkResult(matched, answers.size(), questions.size(), unmatched, null);
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
