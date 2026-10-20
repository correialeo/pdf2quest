package com.pdf2questao.importer.structured;

import com.pdf2questao.importer.ImportJob;
import com.pdf2questao.importer.ImportJobRepository;
import com.pdf2questao.question.Question;
import com.pdf2questao.session.StudySessionService;
import com.pdf2questao.question.QuestionRepository;
import com.pdf2questao.question.QuestionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Gabarito separado em arquivo (JSON, CSV ou texto). Como na importacao de questoes: a previa nao grava nada e
 * mostra o que sera aplicado, conflitos, respostas sem questao e questoes sem resposta; {@link #apply} grava numa
 * transacao. Nada e associado quando ha ambiguidade.
 */
@Service
public class AnswerKeyService {

    /** Por numero, ao menos esta fracao das respostas precisa achar questao na prova escolhida. */
    static final double MIN_NUMBER_MATCH = 0.5;

    private final QuestionRepository questionRepository;
    private final ImportJobRepository importJobRepository;
    private final TransactionTemplate transactions;
    private final Map<String, AnswerKeyPreview> previews = new ConcurrentHashMap<>();
    private final StudySessionService sessionService;

    public AnswerKeyService(QuestionRepository questionRepository, ImportJobRepository importJobRepository,
                            PlatformTransactionManager transactionManager, StudySessionService sessionService) {
        this.sessionService = sessionService;
        this.questionRepository = questionRepository;
        this.importJobRepository = importJobRepository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public AnswerKeyPreview preview(String fileName, byte[] content, AnswerKeyPreview.Mode mode, Long importJobId) {
        AnswerKeyPreview preview = new AnswerKeyPreview(UUID.randomUUID().toString(), mode, importJobId, fileName);
        List<AnswerKeyReader.Entry> entries = new AnswerKeyReader().read(content, preview.getFileErrors());
        if (preview.getFileErrors().isEmpty()) {
            if (mode == AnswerKeyPreview.Mode.NUMERO) {
                byNumber(entries, importJobId, preview);
            } else {
                byId(entries, preview);
            }
        }
        LocalDateTime limit = LocalDateTime.now().minusHours(6);
        previews.values().removeIf(p -> p.getCreatedAt().isBefore(limit));
        previews.put(preview.getToken(), preview);
        return preview;
    }

    public Optional<AnswerKeyPreview> get(String token) {
        return Optional.ofNullable(previews.get(token));
    }

    private void byId(List<AnswerKeyReader.Entry> entries, AnswerKeyPreview preview) {
        Map<String, AnswerKeyReader.Entry> unique = dedupe(entries, preview);
        Map<String, Question> questions = questionRepository.findByExternalIdIn(unique.keySet()).stream()
                .collect(Collectors.toMap(Question::getExternalId, q -> q));
        if (questions.isEmpty() && unique.keySet().stream().allMatch(k -> k.matches("\\d+"))) {
            preview.getFileErrors().add("Nenhum ID do arquivo corresponde a uma questão cadastrada. As chaves são "
                    + "números: se forem os números das questões na prova, escolha \"pelo número da questão\".");
            return;
        }
        for (AnswerKeyReader.Entry entry : unique.values()) {
            classify(entry, questions.get(entry.key()), preview);
        }
        Set<Long> jobs = questions.values().stream().map(Question::getImportJobId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (Long jobId : jobs) {
            withoutAnswer(questionRepository.findByImportJobId(jobId), preview);
        }
    }

    private void byNumber(List<AnswerKeyReader.Entry> entries, Long importJobId, AnswerKeyPreview preview) {
        if (importJobId == null || importJobRepository.findById(importJobId).isEmpty()) {
            preview.getFileErrors().add("Escolha a prova à qual o gabarito pertence.");
            return;
        }
        List<String> notNumbers = entries.stream().map(AnswerKeyReader.Entry::key).filter(k -> !k.matches("\\d+"))
                .limit(5).toList();
        if (!notNumbers.isEmpty()) {
            preview.getFileErrors().add("Pelo número da questão, todas as chaves devem ser números. Exemplos fora "
                    + "disso: " + String.join(", ", notNumbers) + ". Se forem IDs, escolha \"pelo ID\".");
            return;
        }
        Map<String, AnswerKeyReader.Entry> unique = dedupe(entries, preview);
        List<Question> questions = questionRepository.findByImportJobId(importJobId);
        Map<Integer, List<Question>> byNumber = questions.stream().filter(q -> q.getQuestionNumber() != null)
                .collect(Collectors.groupingBy(Question::getQuestionNumber));
        long found = unique.keySet().stream().filter(k -> byNumber.containsKey(Integer.parseInt(k))).count();
        if (unique.isEmpty() || found < Math.ceil(unique.size() * MIN_NUMBER_MATCH)) {
            preview.getFileErrors().add("Só " + found + " de " + unique.size() + " respostas correspondem a números "
                    + "de questões desta prova. Confira se escolheu a prova certa; nada foi associado.");
            return;
        }
        for (AnswerKeyReader.Entry entry : unique.values()) {
            List<Question> same = byNumber.getOrDefault(Integer.parseInt(entry.key()), List.of());
            if (same.size() > 1) {
                preview.invalid.add(line(entry, null, "A prova tem " + same.size() + " questões com o número "
                        + entry.key() + "; não dá para saber qual é. Use IDs."));
                continue;
            }
            classify(entry, same.isEmpty() ? null : same.get(0), preview);
        }
        withoutAnswer(questions, preview);
    }

    /** Chave repetida: com a mesma resposta vale uma vez; com respostas diferentes, nenhuma e aplicada. */
    private Map<String, AnswerKeyReader.Entry> dedupe(List<AnswerKeyReader.Entry> entries, AnswerKeyPreview preview) {
        Map<String, List<AnswerKeyReader.Entry>> byKey = entries.stream()
                .collect(Collectors.groupingBy(AnswerKeyReader.Entry::key, LinkedHashMap::new, Collectors.toList()));
        Map<String, AnswerKeyReader.Entry> unique = new LinkedHashMap<>();
        byKey.forEach((key, same) -> {
            long distinct = same.stream().map(e -> Answers.clean(e.answer())).distinct().count();
            if (distinct > 1) {
                String where = same.stream().map(e -> e.label() + " = " + e.answer()).collect(Collectors.joining(", "));
                preview.invalid.add(line(same.get(0), null, "\"" + key + "\" aparece com respostas diferentes ("
                        + where + "); nenhuma foi aplicada."));
            } else {
                unique.put(key, same.get(0));
            }
        });
        return unique;
    }

    private void classify(AnswerKeyReader.Entry entry, Question q, AnswerKeyPreview preview) {
        if (q == null) {
            preview.unmatched.add(line(entry, null, "Nenhuma questão com " + (preview.getMode() == AnswerKeyPreview.Mode.ID
                    ? "o ID \"" : "o número ") + entry.key() + (preview.getMode() == AnswerKeyPreview.Mode.ID ? "\"" : "")
                    + "."));
            return;
        }
        String current = q.isAnnulled() ? "anulada" : q.getCorrectAnswer();
        if (Answers.isAnnulledMarker(entry.answer())) {
            add(preview, entry, q, current, null, true);
            return;
        }
        QuestionType type = Answers.typeOf(q);
        String letter = Answers.normalize(entry.answer(), type, q.alternatives().keySet());
        if (letter == null) {
            preview.invalid.add(new AnswerKeyLine(entry.label(), entry.key(), entry.answer(), q.getId(), label(q),
                    current, null, false, type == QuestionType.CERTO_ERRADO
                    ? "A questão é de Certo/Errado: use C ou E."
                    : "A questão só tem as alternativas " + String.join(", ", q.alternatives().keySet()) + "."));
            return;
        }
        add(preview, entry, q, current, letter, false);
    }

    private void add(AnswerKeyPreview preview, AnswerKeyReader.Entry entry, Question q, String current,
                     String letter, boolean annul) {
        String next = annul ? "anulada" : letter;
        AnswerKeyLine line = new AnswerKeyLine(entry.label(), entry.key(), entry.answer(), q.getId(), label(q),
                current, letter, annul, current != null && !current.equals(next)
                ? "Já tem a resposta " + current + "; o arquivo diz " + next + "." : null);
        if (current == null) {
            preview.toApply.add(line);
        } else if (current.equals(next)) {
            preview.unchanged.add(line);
        } else {
            preview.conflicts.add(line);
        }
    }

    /** Questoes sem gabarito que este arquivo tambem nao resolve (resposta invalida conta como sem resposta). */
    private void withoutAnswer(List<Question> questions, AnswerKeyPreview preview) {
        Set<Long> answered = preview.toApply.stream().map(AnswerKeyLine::questionId).collect(Collectors.toSet());
        questions.stream()
                .filter(q -> !answered.contains(q.getId()) && q.getCorrectAnswer() == null && !q.isAnnulled())
                .sorted(Comparator.comparing(Question::getQuestionNumber, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(q -> preview.questionsWithoutAnswer.add(label(q)));
    }

    private static AnswerKeyLine line(AnswerKeyReader.Entry entry, Long questionId, String message) {
        return new AnswerKeyLine(entry.label(), entry.key(), entry.answer(), questionId, null, null, null, false, message);
    }

    private static String label(Question q) {
        String number = q.getQuestionNumber() == null ? "#" + q.getId() : "nº " + q.getQuestionNumber();
        return "Questão " + number + (q.getExternalId() != null ? " (" + q.getExternalId() + ")" : "");
    }

    /**
     * Aplica a previa: respostas novas sempre; conflitos so com {@code replaceConflicts}. Por numero, exige
     * {@code confirmedNumbering} (o usuario conferiu que a numeracao e a da prova). Tudo ou nada.
     */
    public ImportOutcome apply(String token, boolean replaceConflicts, boolean confirmedNumbering) {
        AnswerKeyPreview preview = previews.get(token);
        if (preview == null) {
            return ImportOutcome.failure("Esta prévia expirou. Envie o gabarito de novo.");
        }
        if (!preview.getFileErrors().isEmpty()) {
            return ImportOutcome.failure("O gabarito tem erros que impedem a importação.");
        }
        if (preview.getMode() == AnswerKeyPreview.Mode.NUMERO && !confirmedNumbering) {
            return ImportOutcome.failure("Confirme que a numeração do gabarito é a mesma da prova escolhida.");
        }
        List<AnswerKeyLine> lines = new ArrayList<>(preview.toApply);
        if (replaceConflicts) {
            lines.addAll(preview.conflicts);
        }
        int skipped = preview.invalid.size() + preview.unmatched.size() + (replaceConflicts ? 0 : preview.conflicts.size());
        try {
            transactions.executeWithoutResult(status -> {
                Map<Long, Question> questions = new HashMap<>();
                questionRepository.findAllById(lines.stream().map(AnswerKeyLine::questionId).toList())
                        .forEach(q -> questions.put(q.getId(), q));
                List<String> report = new ArrayList<>();
                for (AnswerKeyLine line : lines) {
                    Question q = questions.get(line.questionId());
                    if (q == null) {
                        throw new IllegalStateException("A " + line.questionLabel() + " foi apagada depois da prévia.");
                    }
                    q.setAnnulled(line.annul());
                    q.setCorrectAnswer(line.annul() ? null : line.newAnswer());
                    questionRepository.save(q);
                    report.add(line.questionLabel() + ": " + line.display());
                }
                questions.values().stream().map(Question::getImportJobId).filter(Objects::nonNull).distinct()
                        .forEach(jobId -> importJobRepository.findById(jobId).ifPresent(job -> appendReport(job,
                                "Gabarito " + preview.getFileName() + ": " + report.size() + " respostas aplicadas.")));
                // Respostas dadas antes deste gabarito (ou com o anterior) passam a valer com ele.
                sessionService.regrade(questions.keySet());
            });
        } catch (RuntimeException e) {
            return ImportOutcome.failure("Não foi possível gravar o gabarito; nada foi alterado. " + e.getMessage());
        }
        previews.remove(token);
        return new ImportOutcome(preview.getImportJobId(), lines.size(), 0, skipped, null);
    }

    private void appendReport(ImportJob job, String line) {
        job.setReport(job.getReport() == null || job.getReport().isBlank() ? line : job.getReport() + "\n" + line);
        importJobRepository.save(job);
    }
}
