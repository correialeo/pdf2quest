package com.pdf2questao.importer;

import com.pdf2questao.importer.parser.ExamParser;
import com.pdf2questao.importer.parser.ExamParserRegistry;
import com.pdf2questao.importer.parser.GabaritoParserRegistry;
import com.pdf2questao.importer.parser.GabaritoTableParser;
import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ImportService {

    private final QuestionRepository questionRepository;
    private final ImportJobRepository importJobRepository;
    private final ExamParserRegistry examParserRegistry;
    private final GabaritoParserRegistry gabaritoParserRegistry;

    public ImportService(QuestionRepository questionRepository, ImportJobRepository importJobRepository,
                          ExamParserRegistry examParserRegistry, GabaritoParserRegistry gabaritoParserRegistry) {
        this.questionRepository = questionRepository;
        this.importJobRepository = importJobRepository;
        this.examParserRegistry = examParserRegistry;
        this.gabaritoParserRegistry = gabaritoParserRegistry;
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

        String text;
        try {
            byte[] bytes = file.getBytes();
            try (PDDocument document = Loader.loadPDF(bytes)) {
                text = new PDFTextStripper().getText(document);
            }
        } catch (IOException e) {
            job.setImportedQuestions(0);
            job.setFailedQuestions(0);
            job.setErrorLog("Falha ao ler o PDF: " + e.getMessage());
            job.setFinishedAt(LocalDateTime.now());
            return importJobRepository.save(job);
        }

        ParseResult result = parser.parse(text);

        String organization = hasText(organizationOverride) ? organizationOverride : banca.getLabel();
        Integer year = yearOverride != null ? yearOverride : result.year();

        job.setImportedQuestions(0);
        job.setFailedQuestions(result.failures().size());
        job.setErrorLog(result.failures().stream()
                .map(f -> "Questao " + f.number() + ": " + f.reason())
                .collect(Collectors.joining("\n")));
        job.setDetectedOrganization(result.organization());
        job.setDetectedYear(result.year());
        job.setFinishedAt(LocalDateTime.now());
        ImportJob savedJob = importJobRepository.save(job);

        List<Question> toSave = result.questions().stream()
                .map(pq -> toQuestion(pq, organization, year, examOverride, file.getOriginalFilename(), savedJob.getId()))
                .collect(Collectors.toList());
        questionRepository.saveAll(toSave);

        savedJob.setImportedQuestions(toSave.size());
        return importJobRepository.save(savedJob);
    }

    private Question toQuestion(ParsedQuestion pq, String organization, Integer year, String exam, String source,
                                 Long importJobId) {
        Question q = new Question();
        q.setStatement(pq.statement());
        q.setAlternativeA(pq.alternativeA());
        q.setAlternativeB(pq.alternativeB());
        q.setAlternativeC(pq.alternativeC());
        q.setAlternativeD(pq.alternativeD());
        q.setAlternativeE(pq.alternativeE());
        q.setCorrectAnswer(pq.correctAnswer());
        q.setQuestionNumber(pq.number());
        q.setSubject(pq.subject());
        q.setCategory(pq.category());
        q.setOrganization(organization);
        q.setYear(year);
        q.setExam(exam);
        q.setSource(source);
        q.setImportJobId(importJobId);
        return q;
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
