package com.concursos.study.importer;

import com.concursos.study.question.Question;
import com.concursos.study.question.QuestionRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ImportService {

    private final QuestionRepository questionRepository;
    private final ImportJobRepository importJobRepository;
    private final PdfQuestionParser parser = new PdfQuestionParser();

    public ImportService(QuestionRepository questionRepository, ImportJobRepository importJobRepository) {
        this.questionRepository = questionRepository;
        this.importJobRepository = importJobRepository;
    }

    public ImportJob importPdf(MultipartFile file, String examOverride, String organizationOverride, Integer yearOverride) {
        ImportJob job = new ImportJob();
        job.setFileName(file.getOriginalFilename());
        job.setStartedAt(LocalDateTime.now());

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

        String organization = hasText(organizationOverride) ? organizationOverride : result.organization();
        Integer year = yearOverride != null ? yearOverride : result.year();

        List<Question> toSave = result.questions().stream()
                .map(pq -> toQuestion(pq, organization, year, examOverride, file.getOriginalFilename()))
                .collect(Collectors.toList());
        questionRepository.saveAll(toSave);

        job.setImportedQuestions(toSave.size());
        job.setFailedQuestions(result.failures().size());
        job.setErrorLog(result.failures().stream()
                .map(f -> "Questao " + f.number() + ": " + f.reason())
                .collect(Collectors.joining("\n")));
        job.setDetectedOrganization(result.organization());
        job.setDetectedYear(result.year());
        job.setFinishedAt(LocalDateTime.now());
        return importJobRepository.save(job);
    }

    private Question toQuestion(ParsedQuestion pq, String organization, Integer year, String exam, String source) {
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
        return q;
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
