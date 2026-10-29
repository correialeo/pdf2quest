package com.pdf2questao.study;

import com.pdf2questao.concurso.ConcursoService;
import com.pdf2questao.concurso.Workspace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Revisao espacada das tarefas estudadas. Os intervalos (em dias) vem de {@code pdf2questao.revisao.intervalos}:
 * a etapa e o indice do intervalo usado no proximo "lembrei". Concluir (ou esquecer) agenda em {@code intervalos[0]}
 * e deixa a etapa em 1; lembrou = proxima revisao em {@code intervalos[etapa]} dias e sobe uma etapa (ate a ultima).
 * <p>
 * Os assuntos a revisar no dia viram uma tarefa por concurso (workspace), no cronograma padrao dele
 * ({@code pdf2questao.revisao.minutos}); concluir a tarefa conta como "lembrei" para os assuntos daquele concurso que
 * ainda estao nela. Assunto de tarefa avulsa (sem cronograma) tem a sua propria tarefa, vista so em "Todos".
 */
@Service
public class SpacedRepetitionService {

    private final StudyTaskRepository taskRepository;
    private final ConcursoService concursoService;
    private final Workspace workspace;
    private final List<Integer> intervals;
    private final int reviewMinutes;

    public SpacedRepetitionService(StudyTaskRepository taskRepository, ConcursoService concursoService,
                                   Workspace workspace,
                                   @Value("${pdf2questao.revisao.intervalos:1,3,7,14,30}") List<Integer> intervals,
                                   @Value("${pdf2questao.revisao.minutos:25}") int reviewMinutes) {
        if (intervals.isEmpty() || intervals.stream().anyMatch(d -> d == null || d < 1)) {
            throw new IllegalStateException("pdf2questao.revisao.intervalos precisa de dias positivos, ex.: 1,3,7,14,30");
        }
        this.taskRepository = taskRepository;
        this.concursoService = concursoService;
        this.workspace = workspace;
        this.intervals = List.copyOf(intervals);
        this.reviewMinutes = reviewMinutes;
    }

    public List<Integer> intervals() {
        return intervals;
    }

    /**
     * Revisoes pendentes do concurso em vigor (em "Todos", de todos): tarefas concluidas com revisao marcada para
     * hoje ou antes.
     */
    public List<StudyTask> due(LocalDate today) {
        Map<Long, Long> concursoBy = concursoByCronograma();
        return taskRepository.findDueReviews(today).stream()
                .filter(t -> workspace.matches(concursoOf(t, concursoBy))).toList();
    }

    /** Revisoes pendentes de um concurso especifico (nulo = assuntos de tarefas avulsas), seja qual for o em vigor. */
    public List<StudyTask> dueFor(Long concursoId, LocalDate today) {
        Map<Long, Long> concursoBy = concursoByCronograma();
        return taskRepository.findDueReviews(today).stream()
                .filter(t -> Objects.equals(concursoId, concursoOf(t, concursoBy))).toList();
    }

    private Map<Long, Long> concursoByCronograma() {
        Map<Long, Long> concursoBy = new HashMap<>();
        concursoService.cronogramasById().forEach((id, c) -> concursoBy.put(id, c.getConcursoId()));
        return concursoBy;
    }

    private static Long concursoOf(StudyTask task, Map<Long, Long> concursoBy) {
        return task.getCronogramaId() == null ? null : concursoBy.get(task.getCronogramaId());
    }

    @Transactional
    public StudyTask remembered(Long id, LocalDate today) {
        StudyTask task = taskRepository.findById(id).orElseThrow();
        int step = Math.min(task.getCurrentStep(), intervals.size() - 1);
        task.setNextReviewDate(today.plusDays(intervals.get(step)));
        task.setCurrentStep(Math.min(step + 1, intervals.size() - 1));
        return taskRepository.save(task);
    }

    @Transactional
    public StudyTask forgot(Long id, LocalDate today) {
        StudyTask task = taskRepository.findById(id).orElseThrow();
        restart(task, today);
        return taskRepository.save(task);
    }

    /**
     * Deixa as tarefas de revisao do dia em dia com os assuntos pendentes, uma por concurso (cada assunto so entra
     * na do seu concurso): cria na primeira vez, traz para hoje uma que ficou aberta de outro dia (ou apaga, se nao
     * sobrou assunto). Independe do concurso em vigor: monta a de todos.
     */
    @Transactional
    public void syncReviewTask(LocalDate today) {
        Map<Long, Long> concursoBy = concursoByCronograma();
        Map<Long, List<StudyTask>> due = byConcurso(taskRepository.findDueReviews(today), concursoBy);
        Map<Long, List<StudyTask>> open = byConcurso(
                taskRepository.findByReviewDateNotNullAndDoneFalseOrderByReviewDateDesc(), concursoBy);
        Map<Long, List<StudyTask>> doneToday = byConcurso(taskRepository.findAllByReviewDate(today).stream()
                .filter(StudyTask::isDone).toList(), concursoBy);
        Set<Long> concursos = new LinkedHashSet<>(due.keySet());
        concursos.addAll(open.keySet());
        for (Long concursoId : concursos) {
            syncReviewTask(concursoId, today, due.getOrDefault(concursoId, List.of()),
                    open.getOrDefault(concursoId, List.of()), doneToday.containsKey(concursoId));
        }
    }

    private void syncReviewTask(Long concursoId, LocalDate today, List<StudyTask> items, List<StudyTask> open,
                                boolean reviewedToday) {
        StudyTask task = open.isEmpty() ? null : open.get(0);
        open.stream().skip(1).forEach(taskRepository::delete);
        boolean stale = task != null && task.getReviewDate().isBefore(today);
        if (stale && (items.isEmpty() || reviewedToday)) {
            taskRepository.delete(task);
            return;
        }
        if (items.isEmpty()) {
            return; // a de hoje (mesmo reaberta) fica como esta
        }
        if (task == null) {
            if (reviewedToday) {
                return; // a de hoje ja foi concluida; o que vencer agora entra na de amanha
            }
            task = new StudyTask();
            task.setEstimatedMinutes(reviewMinutes);
            task.setCronogramaId(concursoId == null ? null : concursoService.defaultCronograma(concursoId).getId());
        }
        task.setReviewDate(today);
        task.setPlannedDate(today);
        task.setTitle("Revisão · " + items.size() + (items.size() == 1 ? " assunto" : " assuntos"));
        task.setDescription(String.join("\n", items.stream()
                .map(t -> "- " + t.getTitle() + (t.getSubject() == null ? "" : " (" + t.getSubject() + ")")).toList()));
        taskRepository.save(task);
    }

    private static Map<Long, List<StudyTask>> byConcurso(List<StudyTask> tasks, Map<Long, Long> concursoBy) {
        Map<Long, List<StudyTask>> out = new LinkedHashMap<>();
        tasks.forEach(t -> out.computeIfAbsent(concursoOf(t, concursoBy), k -> new ArrayList<>()).add(t));
        return out;
    }

    /** Concluir a tarefa de revisao: os assuntos do concurso dela que ainda estao nela contam como lembrados. */
    void completeReview(StudyTask review, LocalDate today) {
        dueFor(concursoService.concursoOfTask(review), today).forEach(t -> remembered(t.getId(), today));
    }

    /** Primeira revisao de uma tarefa recem-concluida (se ainda nao tiver uma). */
    void scheduleFirst(StudyTask task, LocalDate today) {
        if (!task.isReview() && task.isReviewEnabled() && task.getNextReviewDate() == null) {
            restart(task, today);
        }
    }

    /**
     * Liga ou desliga a revisao espacada da tarefa. Desligar tira das revisoes pendentes; religar uma tarefa ja
     * concluida agenda a primeira revisao.
     */
    @Transactional
    public StudyTask setEnabled(Long id, boolean enabled, LocalDate today) {
        StudyTask task = taskRepository.findById(id).orElseThrow();
        if (task.isReview()) {
            throw new IllegalArgumentException("A tarefa de revisão não entra na própria revisão.");
        }
        task.setReviewEnabled(enabled);
        if (!enabled) {
            task.setNextReviewDate(null);
            task.setCurrentStep(0);
        } else if (task.isDone()) {
            scheduleFirst(task, today);
        }
        return taskRepository.save(task);
    }

    /** Revisa em {@code intervalos[0]}; o proximo "lembrei" ja usa o intervalo seguinte. */
    private void restart(StudyTask task, LocalDate today) {
        task.setCurrentStep(Math.min(1, intervals.size() - 1));
        task.setNextReviewDate(today.plusDays(intervals.get(0)));
    }
}
