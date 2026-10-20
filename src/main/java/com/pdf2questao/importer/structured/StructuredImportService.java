package com.pdf2questao.importer.structured;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdf2questao.importer.ImportJob;
import com.pdf2questao.importer.ImportJobRepository;
import com.pdf2questao.importer.parser.support.RichText;
import com.pdf2questao.question.Passage;
import com.pdf2questao.question.PassageRepository;
import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionRepository;
import com.pdf2questao.session.StudySessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Importacao de questoes por arquivo JSON ou CSV, em duas etapas: {@code preview*} le e valida sem tocar no banco
 * (o resultado fica em memoria, por um token); {@link #confirm} grava tudo numa transacao so. Se qualquer gravacao
 * falhar, nada fica no banco.
 */
@Service
public class StructuredImportService {

    /** Previas esquecidas (o usuario fechou a aba) somem depois deste tempo. */
    private static final long EXPIRY_HOURS = 6;

    private final QuestionRepository questionRepository;
    private final ImportJobRepository importJobRepository;
    private final PassageRepository passageRepository;
    private final TransactionTemplate transactions;
    private final StudySessionService sessionService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final StructuredQuestionValidator validator = new StructuredQuestionValidator();
    private final Map<String, ImportPreview> previews = new ConcurrentHashMap<>();
    /** Planilhas entre o envio e a previa (a tela de mapeamento pode reler com outro separador). */
    private final Map<String, PendingCsv> csvTexts = new ConcurrentHashMap<>();

    private record PendingCsv(String fileName, String text, LocalDateTime createdAt) {
    }

    public StructuredImportService(QuestionRepository questionRepository, ImportJobRepository importJobRepository,
                                   PassageRepository passageRepository, PlatformTransactionManager transactionManager,
                                   StudySessionService sessionService) {
        this.sessionService = sessionService;
        this.questionRepository = questionRepository;
        this.importJobRepository = importJobRepository;
        this.passageRepository = passageRepository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ImportPreview previewJson(String fileName, byte[] content) {
        ImportPreview preview = new ImportPreview(newToken(), "JSON", fileName);
        new JsonQuestionReader().read(content, preview);
        return store(preview);
    }

    /** Primeira etapa do CSV: le a planilha e sugere o mapeamento das colunas. */
    public CsvUpload uploadCsv(String fileName, byte[] content) {
        String text = TextDecoding.decode(content);
        expireOld();
        String token = newToken();
        csvTexts.put(token, new PendingCsv(fileName, text, LocalDateTime.now()));
        return read(token, fileName, text, CsvTable.detectDelimiter(text));
    }

    /** Releitura com outro separador escolhido na tela (o mapeamento sugerido e refeito). */
    public Optional<CsvUpload> rereadCsv(String token, char delimiter) {
        PendingCsv pending = csvTexts.get(token);
        return pending == null ? Optional.empty() : Optional.of(read(token, pending.fileName(), pending.text(), delimiter));
    }

    private CsvUpload read(String token, String fileName, String text, char delimiter) {
        CsvTable table = CsvTable.parse(text, delimiter);
        CsvMapping mapping = CsvMapping.guess(table.rows().isEmpty() ? List.of() : table.rows().get(0), delimiter);
        return new CsvUpload(token, fileName, table, mapping);
    }

    public Optional<CsvUpload> csvUpload(String token) {
        PendingCsv pending = csvTexts.get(token);
        return pending == null ? Optional.empty()
                : Optional.of(read(token, pending.fileName(), pending.text(), CsvTable.detectDelimiter(pending.text())));
    }

    /** Segunda etapa do CSV: aplica o mapeamento escolhido e valida como no JSON. */
    public Optional<ImportPreview> previewCsv(String token, CsvMapping mapping, String banca, String exam, Integer year,
                                              String subject) {
        PendingCsv pending = csvTexts.get(token);
        if (pending == null) {
            return Optional.empty();
        }
        CsvTable table = CsvTable.parse(pending.text(), mapping.getDelimiter());
        ImportPreview preview = new ImportPreview(newToken(), "CSV", pending.fileName());
        if (mapping.column(CsvMapping.Field.ENUNCIADO) == null) {
            preview.getFileErrors().add("Escolha qual coluna tem o enunciado das questões.");
        } else {
            List<StructuredQuestion> questions = new CsvQuestionReader().read(table, mapping);
            if (questions.isEmpty()) {
                preview.getFileErrors().add("A planilha não tem nenhuma linha de questão.");
            }
            for (StructuredQuestion q : questions) {
                q.organization = q.organization != null ? q.organization : JsonQuestionReader.blankToNull(banca);
                q.exam = q.exam != null ? q.exam : JsonQuestionReader.blankToNull(exam);
                q.year = q.year != null ? q.year : year;
                q.subject = q.subject != null ? q.subject : JsonQuestionReader.blankToNull(subject);
            }
            preview.setQuestions(questions);
            preview.setBanca(JsonQuestionReader.blankToNull(banca));
            preview.setTitle(JsonQuestionReader.blankToNull(exam));
        }
        return Optional.of(store(preview));
    }

    public Optional<ImportPreview> preview(String token) {
        return Optional.ofNullable(previews.get(token));
    }

    public void discard(String token) {
        previews.remove(token);
    }

    /**
     * Grava a previa. Valida de novo contra o banco atual (outra importacao pode ter entrado no meio) e grava
     * tudo numa transacao: qualquer erro desfaz a importacao inteira.
     */
    public ImportOutcome confirm(String token, ImportOptions options) {
        ImportPreview preview = previews.get(token);
        if (preview == null) {
            return ImportOutcome.failure("Esta prévia expirou. Envie o arquivo de novo.");
        }
        if (!preview.getFileErrors().isEmpty()) {
            return ImportOutcome.failure("O arquivo tem erros que impedem a importação.");
        }
        List<PreviewItem> items = validator.validate(preview.getQuestions(), existingQuestions());
        preview.setItems(items);
        boolean hasInvalid = items.stream().anyMatch(i -> i.getStatus() == PreviewItem.Status.INVALIDA);
        if (hasInvalid && !options.acceptInvalid()) {
            return ImportOutcome.failure("Há questões com erro. Corrija o arquivo ou marque a opção de importar "
                    + "só as questões sem erro.");
        }
        ImportOutcome outcome;
        try {
            outcome = transactions.execute(status -> save(preview, items, options, null));
        } catch (RuntimeException e) {
            return ImportOutcome.failure("Não foi possível gravar a importação; nada foi alterado. Detalhe: "
                    + rootMessage(e));
        }
        previews.remove(token);
        return outcome;
    }

    /**
     * Acrescenta as questoes de um JSON a uma prova ja existente (ex.: simulado criado pelo Claude, que chega em
     * lotes). So questoes novas e validas entram; as com erro voltam no relatorio. A numeracao continua a da prova.
     */
    public ImportOutcome appendJson(Long importJobId, byte[] json) {
        ImportJob job = importJobRepository.findById(importJobId).orElseThrow();
        ImportPreview preview = previewJson(job.getFileName(), json);
        previews.remove(preview.getToken());
        if (!preview.getFileErrors().isEmpty()) {
            return ImportOutcome.failure(String.join(" ", preview.getFileErrors()));
        }
        try {
            return transactions.execute(status -> save(preview, preview.getItems(),
                    new ImportOptions(null, false, false, true), job));
        } catch (RuntimeException e) {
            return ImportOutcome.failure("Não foi possível gravar as questões; nada foi alterado. Detalhe: "
                    + rootMessage(e));
        }
    }

    private ImportOutcome save(ImportPreview preview, List<PreviewItem> items, ImportOptions options,
                               ImportJob appendTo) {
        ImportJob job = appendTo;
        int offset = 0;
        if (job == null) {
            job = new ImportJob();
            job.setFileName(preview.getFileName());
            job.setSourceFormat(preview.getFormat());
            job.setBanca(preview.getBanca());
            job.setTitle(firstNonBlank(options.title(), preview.getTitle()));
            job.setStartedAt(LocalDateTime.now());
            job = importJobRepository.save(job);
        } else {
            offset = questionRepository.findByImportJobId(job.getId()).stream()
                    .map(Question::getQuestionNumber).filter(java.util.Objects::nonNull)
                    .max(Integer::compare).orElse(0);
        }

        int created = 0;
        int updated = 0;
        int skipped = 0;
        int position = 0;
        Map<String, Long> passages = new HashMap<>();
        List<String> report = new ArrayList<>();
        Integer detectedYear = null;
        // Respostas do usuario no arquivo (treino feito fora): questao gravada -> resposta, na ordem do arquivo.
        Map<Long, PreviewItem> answered = new LinkedHashMap<>();
        for (PreviewItem item : items) {
            position++;
            StructuredQuestion q = item.getSource();
            switch (item.getStatus()) {
                case INVALIDA -> {
                    skipped++;
                    report.add(q.label + ": não importada (" + String.join(" ", item.getErrors()) + ")");
                    continue;
                }
                case POSSIVEL_DUPLICATA -> {
                    if (!options.includeDuplicates()) {
                        skipped++;
                        report.add(q.label + ": não importada, possível duplicata de " + item.getDuplicateOf());
                        if (item.getExistingQuestionId() != null) {
                            answered.putIfAbsent(item.getExistingQuestionId(), item);   // resposta vale na ja gravada
                        }
                        continue;
                    }
                }
                case JA_EXISTE -> {
                    if (!options.updateExisting()) {
                        skipped++;
                        report.add(q.label + ": ID \"" + q.externalId + "\" já cadastrado, mantido como estava");
                        answered.putIfAbsent(item.getExistingQuestionId(), item);
                        continue;
                    }
                    Question existing = questionRepository.findById(item.getExistingQuestionId()).orElseThrow();
                    fill(existing, item, passageId(q, existing.getImportJobId(), passages));
                    questionRepository.save(existing);
                    answered.putIfAbsent(existing.getId(), item);
                    updated++;
                    report.add(q.label + ": ID \"" + q.externalId + "\" atualizado (questão #" + existing.getId() + ")");
                    continue;
                }
                default -> {
                }
            }
            Question question = new Question();
            question.setImportJobId(job.getId());
            question.setExternalId(q.externalId);
            // Sem numero no arquivo, a posicao no arquivo vira o numero (ordem das listas e faixas dos blocos).
            question.setQuestionNumber(q.number != null ? q.number : offset + position);
            fill(question, item, passageId(q, job.getId(), passages));
            questionRepository.save(question);
            answered.putIfAbsent(question.getId(), item);
            created++;
            if (detectedYear == null) {
                detectedYear = q.year;
            }
        }
        int failed = (int) items.stream().filter(i -> i.getStatus() == PreviewItem.Status.INVALIDA).count();
        if (appendTo == null) {
            job.setImportedQuestions(created);
            job.setFailedQuestions(failed);
            job.setDetectedOrganization(preview.getBanca());
            job.setDetectedYear(detectedYear);
        } else {
            job.setImportedQuestions(job.getImportedQuestions() + created);
            job.setFailedQuestions(job.getFailedQuestions() + failed);
        }
        job.setFinishedAt(LocalDateTime.now());
        report.add(0, "Importação " + preview.getFormat() + ": " + created + " criadas, " + updated
                + " atualizadas, " + skipped + " não importadas.");
        Long trainingSessionId = null;
        List<StudySessionService.TrainingAnswer> answers = answered.entrySet().stream()
                .filter(e -> e.getValue().getUserAnswer() != null)
                .map(e -> new StudySessionService.TrainingAnswer(e.getKey(), e.getValue().getUserAnswer(),
                        e.getValue().getSource().userGuess))
                .toList();
        if (!answers.isEmpty()) {
            trainingSessionId = sessionService.recordTraining(firstNonBlank(job.getTitle(), job.getFileName()),
                    answers).getId();
            report.add(1, answers.size() + " respostas suas registradas como sessão de treino #" + trainingSessionId + ".");
        }
        job.setReport(appendTo == null || job.getReport() == null ? String.join("\n", report)
                : job.getReport() + "\n" + String.join("\n", report));
        importJobRepository.save(job);
        return new ImportOutcome(job.getId(), created, updated, skipped, null, trainingSessionId);
    }

    /** Copia o conteudo validado para a questao. Usado tambem na atualizacao: id, prova e numero nao mudam. */
    private void fill(Question question, PreviewItem item, Long passageId) {
        StructuredQuestion q = item.getSource();
        question.setStatement(clean(q.statement));
        question.setQuestionType(item.getType());
        Map<String, String> alt = item.getAlternatives();
        question.setAlternativeA(clean(alt.get("A")));
        question.setAlternativeB(clean(alt.get("B")));
        question.setAlternativeC(clean(alt.get("C")));
        question.setAlternativeD(clean(alt.get("D")));
        question.setAlternativeE(clean(alt.get("E")));
        question.setAnnulled(q.annulled);
        question.setCorrectAnswer(q.annulled ? null : item.getAnswer());
        question.setExplanation(q.explanation);
        question.setOrganization(q.organization);
        question.setExam(q.exam);
        question.setYear(q.year);
        question.setSubject(q.subject);
        question.setTopic(q.topic);
        question.setSubtopics(q.subtopics.isEmpty() ? null : String.join("\n", q.subtopics));
        question.setTags(q.tags.isEmpty() ? null : String.join(", ", q.tags));
        question.setDifficulty(q.difficulty);
        question.setSource(q.source);
        question.setCategory(q.category == null ? null : StructuredQuestionValidator.category(q.category));
        question.setPassageId(passageId);
        question.setExtraMetadata(metadata(q));
    }

    private Long passageId(StructuredQuestion q, Long jobId, Map<String, Long> passages) {
        if (q.passage == null) {
            return null;
        }
        return passages.computeIfAbsent(q.passage, text -> {
            Passage passage = new Passage();
            passage.setImportJobId(jobId);
            passage.setContent(clean(text));
            return passageRepository.save(passage).getId();
        });
    }

    private String metadata(StructuredQuestion q) {
        if (q.references.isEmpty() && q.metadata.isEmpty()) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        if (!q.references.isEmpty()) {
            map.put("referencias", q.references);
        }
        if (!q.metadata.isEmpty()) {
            map.put("metadados", q.metadata);
        }
        try {
            return mapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Texto do arquivo nao pode trazer a marcacao interna (imagens, paginas) das questoes de PDF. */
    private static String clean(String text) {
        return text == null ? null : RichText.TOKEN.matcher(text).replaceAll("");
    }

    StructuredQuestionValidator.ExistingQuestions existingQuestions() {
        Map<String, Long> byExternalId = new HashMap<>();
        for (Object[] row : questionRepository.findExternalIds()) {
            byExternalId.put((String) row[1], ((Number) row[0]).longValue());
        }
        Map<String, Long> byStatement = new HashMap<>();
        for (Object[] row : questionRepository.findStatements()) {
            byStatement.putIfAbsent(StructuredQuestionValidator.normalizeStatement((String) row[1]),
                    ((Number) row[0]).longValue());
        }
        return new StructuredQuestionValidator.ExistingQuestions() {
            @Override
            public Long idByExternalId(String externalId) {
                return byExternalId.get(externalId);
            }

            @Override
            public Long idByStatement(String normalizedStatement) {
                return byStatement.get(normalizedStatement);
            }
        };
    }

    private ImportPreview store(ImportPreview preview) {
        if (preview.getFileErrors().isEmpty()) {
            preview.setItems(validator.validate(preview.getQuestions(), existingQuestions()));
        }
        expireOld();
        previews.put(preview.getToken(), preview);
        return preview;
    }

    private void expireOld() {
        LocalDateTime limit = LocalDateTime.now().minusHours(EXPIRY_HOURS);
        previews.values().removeIf(p -> p.getCreatedAt().isBefore(limit));
        csvTexts.values().removeIf(p -> p.createdAt().isBefore(limit));
    }

    private static String newToken() {
        return UUID.randomUUID().toString();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a.trim() : b;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
