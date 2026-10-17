package com.pdf2questao.study;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Revisao espacada das tarefas estudadas. Os intervalos (em dias) vem de {@code pdf2questao.revisao.intervalos}:
 * a etapa e o indice do intervalo usado no proximo "lembrei". Concluir (ou esquecer) agenda em {@code intervalos[0]}
 * e deixa a etapa em 1; lembrou = proxima revisao em {@code intervalos[etapa]} dias e sobe uma etapa (ate a ultima).
 * <p>
 * Os assuntos a revisar no dia viram uma tarefa so no cronograma ({@code pdf2questao.revisao.minutos}); concluir a
 * tarefa conta como "lembrei" para todos os assuntos que ainda estao nela.
 */
@Service
public class SpacedRepetitionService {

    private final StudyTaskRepository taskRepository;
    private final List<Integer> intervals;
    private final int reviewMinutes;

    public SpacedRepetitionService(StudyTaskRepository taskRepository,
                                   @Value("${pdf2questao.revisao.intervalos:1,3,7,14,30}") List<Integer> intervals,
                                   @Value("${pdf2questao.revisao.minutos:25}") int reviewMinutes) {
        if (intervals.isEmpty() || intervals.stream().anyMatch(d -> d == null || d < 1)) {
            throw new IllegalStateException("pdf2questao.revisao.intervalos precisa de dias positivos, ex.: 1,3,7,14,30");
        }
        this.taskRepository = taskRepository;
        this.intervals = List.copyOf(intervals);
        this.reviewMinutes = reviewMinutes;
    }

    public List<Integer> intervals() {
        return intervals;
    }

    /** Revisoes pendentes: tarefas concluidas com revisao marcada para hoje ou antes. */
    public List<StudyTask> due(LocalDate today) {
        return taskRepository.findDueReviews(today);
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
     * Deixa a tarefa de revisao do dia em dia com os assuntos pendentes: cria na primeira vez, traz para hoje uma
     * que ficou aberta de outro dia (ou apaga, se nao sobrou assunto).
     */
    @Transactional
    public void syncReviewTask(LocalDate today) {
        List<StudyTask> items = due(today);
        List<StudyTask> open = taskRepository.findByReviewDateNotNullAndDoneFalseOrderByReviewDateDesc();
        StudyTask task = open.isEmpty() ? null : open.get(0);
        open.stream().skip(1).forEach(taskRepository::delete);
        boolean stale = task != null && task.getReviewDate().isBefore(today);
        if (stale && (items.isEmpty() || taskRepository.findByReviewDate(today).isPresent())) {
            taskRepository.delete(task);
            return;
        }
        if (items.isEmpty()) {
            return; // a de hoje (mesmo reaberta) fica como esta
        }
        if (task == null) {
            if (taskRepository.findByReviewDate(today).isPresent()) {
                return; // a de hoje ja foi concluida; o que vencer agora entra na de amanha
            }
            task = new StudyTask();
            task.setEstimatedMinutes(reviewMinutes);
        }
        task.setReviewDate(today);
        task.setPlannedDate(today);
        task.setTitle("Revisão · " + items.size() + (items.size() == 1 ? " assunto" : " assuntos"));
        task.setDescription(String.join("\n", items.stream()
                .map(t -> "- " + t.getTitle() + (t.getSubject() == null ? "" : " (" + t.getSubject() + ")")).toList()));
        taskRepository.save(task);
    }

    /** Concluir a tarefa de revisao: todos os assuntos que ainda estao nela contam como lembrados. */
    void completeReview(LocalDate today) {
        due(today).forEach(t -> remembered(t.getId(), today));
    }

    /** Primeira revisao de uma tarefa recem-concluida (se ainda nao tiver uma). */
    void scheduleFirst(StudyTask task, LocalDate today) {
        if (!task.isReview() && task.getNextReviewDate() == null) {
            restart(task, today);
        }
    }

    /** Revisa em {@code intervalos[0]}; o proximo "lembrei" ja usa o intervalo seguinte. */
    private void restart(StudyTask task, LocalDate today) {
        task.setCurrentStep(Math.min(1, intervals.size() - 1));
        task.setNextReviewDate(today.plusDays(intervals.get(0)));
    }
}
