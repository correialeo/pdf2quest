package com.pdf2questao.guess;

import com.pdf2questao.guess.GuessDiagnostic.Counts;
import com.pdf2questao.guess.GuessDiagnostic.JobOption;
import com.pdf2questao.guess.GuessDiagnostic.LuckyQuestion;
import com.pdf2questao.guess.GuessDiagnostic.Report;
import com.pdf2questao.guess.GuessDiagnostic.SubjectRow;
import com.pdf2questao.importer.ImportJob;
import com.pdf2questao.importer.ImportJobRepository;
import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionRepository;
import com.pdf2questao.session.Attempt;
import com.pdf2questao.session.AttemptRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Diagnostico dos chutes. Cada questao vale pela sua ULTIMA resposta (a mais recente), como no caderno de
 * erros: se acertou no chute e depois refez sabendo, ela sai da lista de estudo.
 */
@Service
public class GuessDiagnosticService {

    private static final String NO_SUBJECT = "(sem disciplina)";

    private final AttemptRepository attemptRepository;
    private final QuestionRepository questionRepository;
    private final ImportJobRepository importJobRepository;

    public GuessDiagnosticService(AttemptRepository attemptRepository, QuestionRepository questionRepository,
                                  ImportJobRepository importJobRepository) {
        this.attemptRepository = attemptRepository;
        this.questionRepository = questionRepository;
        this.importJobRepository = importJobRepository;
    }

    /** @param jobId limita a uma prova; null = todas. */
    public Report report(Long jobId) {
        Map<Long, Attempt> latest = latestAttempts();
        Map<Long, Question> questions = questionRepository.findAllById(latest.keySet()).stream()
                .collect(Collectors.toMap(Question::getId, q -> q));
        Map<Long, ImportJob> jobs = importJobRepository.findAll().stream()
                .collect(Collectors.toMap(ImportJob::getId, j -> j));

        Map<Long, Long> luckyPerJob = new LinkedHashMap<>();
        for (Attempt a : latest.values()) {
            Question q = questions.get(a.getQuestionId());
            if (q != null && q.getImportJobId() != null && isLuckyCorrect(a)) {
                luckyPerJob.merge(q.getImportJobId(), 1L, Long::sum);
            }
        }
        List<JobOption> options = luckyPerJob.entrySet().stream()
                .filter(e -> jobs.containsKey(e.getKey()))
                .map(e -> new JobOption(jobs.get(e.getKey()), e.getValue()))
                .sorted(Comparator.comparing(o -> o.job().getId())).toList();

        Map<String, long[]> bySubject = new LinkedHashMap<>();
        long[] total = new long[5];
        List<LuckyQuestion> lucky = new ArrayList<>();
        for (Attempt a : latest.values()) {
            Question q = questions.get(a.getQuestionId());
            if (q == null || a.getCorrect() == null || (jobId != null && !jobId.equals(q.getImportJobId()))) {
                continue;
            }
            int slot = slot(a);
            total[slot]++;
            bySubject.computeIfAbsent(q.getSubject() == null || q.getSubject().isBlank() ? NO_SUBJECT : q.getSubject(),
                    k -> new long[5])[slot]++;
            if (isLuckyCorrect(a)) {
                lucky.add(new LuckyQuestion(q, jobs.get(q.getImportJobId())));
            }
        }

        List<SubjectRow> subjects = bySubject.entrySet().stream()
                .map(e -> new SubjectRow(e.getKey(), counts(e.getValue())))
                // Disciplina so com respostas antigas (sem a marcacao) nao tem o que diagnosticar.
                .filter(r -> r.counts().informed() > 0)
                // As disciplinas com mais acertos no chute primeiro: onde mais vale estudar.
                .sorted(Comparator.comparingLong((SubjectRow r) -> r.counts().guessCorrect()).reversed()
                        .thenComparing(SubjectRow::label))
                .toList();
        lucky.sort(Comparator.comparing((LuckyQuestion l) -> l.question().getSubject(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(l -> l.question().getImportJobId(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(l -> l.question().getQuestionNumber(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(l -> l.question().getId()));
        return new Report(counts(total), subjects, lucky, options, jobId);
    }

    /** Ids das questoes acertadas no chute (ultima resposta), para "refazer". */
    public List<Long> luckyQuestionIds(Long jobId) {
        return report(jobId).lucky().stream().map(l -> l.question().getId()).toList();
    }

    /** Contagem geral (todas as provas ou uma), usada nos cartoes do dashboard. */
    public Counts counts(Long jobId) {
        return report(jobId).counts();
    }

    private Map<Long, Attempt> latestAttempts() {
        Map<Long, Attempt> latest = new HashMap<>();
        for (Attempt a : attemptRepository.findAll()) {
            if (a.getQuestionId() == null) {
                continue;
            }
            latest.merge(a.getQuestionId(), a, (x, y) -> newer(x, y) ? x : y);
        }
        return latest;
    }

    private static boolean newer(Attempt x, Attempt y) {
        LocalDateTime tx = x.getAnsweredAt();
        LocalDateTime ty = y.getAnsweredAt();
        if (!Objects.equals(tx, ty)) {
            if (tx == null) {
                return false;
            }
            if (ty == null) {
                return true;
            }
            return tx.isAfter(ty);
        }
        return x.getId() != null && y.getId() != null && x.getId() > y.getId();
    }

    private static boolean isLuckyCorrect(Attempt a) {
        return Boolean.TRUE.equals(a.getCorrect()) && Boolean.TRUE.equals(a.getGuessed());
    }

    /** 0 acerto seguro, 1 acerto no chute, 2 erro seguro, 3 erro no chute, 4 sem informacao. */
    private static int slot(Attempt a) {
        if (a.getGuessed() == null) {
            return 4;
        }
        boolean correct = Boolean.TRUE.equals(a.getCorrect());
        boolean guess = a.getGuessed();
        return correct ? (guess ? 1 : 0) : (guess ? 3 : 2);
    }

    private static Counts counts(long[] c) {
        return new Counts(c[0], c[1], c[2], c[3], c[4]);
    }
}
