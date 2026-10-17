package com.pdf2questao.study;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Cronograma: tarefas por data, conclusao e tempo dedicado. */
@Service
public class StudyTaskService {

    private static final Locale PT_BR = Locale.of("pt", "BR");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, dd/MM", PT_BR);

    private final StudyTaskRepository taskRepository;
    private final StudyPageRepository pageRepository;
    private final SpacedRepetitionService spacedRepetition;

    public StudyTaskService(StudyTaskRepository taskRepository, StudyPageRepository pageRepository,
                            SpacedRepetitionService spacedRepetition) {
        this.taskRepository = taskRepository;
        this.pageRepository = pageRepository;
        this.spacedRepetition = spacedRepetition;
    }

    /** Grupo da lista do cronograma ("Atrasada · ter, 13/10", "Hoje · qua, 15/10", "Sem data"). */
    /** {@code date}: o dia do grupo (nulo em "Sem data"). Pendentes e concluidas do dia ficam juntas. */
    public record TaskGroup(String label, String kind, LocalDate date, List<StudyTask> tasks) {

        public int estimatedMinutes() {
            return tasks.stream().mapToInt(t -> t.getEstimatedMinutes() == null ? 0 : t.getEstimatedMinutes()).sum();
        }

        public int spentMinutes() {
            return tasks.stream().mapToInt(t -> t.getSpentMinutes() == null ? 0 : t.getSpentMinutes()).sum();
        }

        public int doneCount() {
            return (int) tasks.stream().filter(StudyTask::isDone).count();
        }
    }

    /** O cronograma esconde dias passados ja concluidos ha mais que isso (a nao ser que peca "todas"). */
    public static final int RECENT_DAYS = 7;

    /** Lista do cronograma e quantos dias concluidos antigos ficaram de fora. */
    public record Schedule(List<TaskGroup> groups, int hiddenDays) {
    }

    /** Dados para o formulario de tarefa (criar e editar usam o mesmo). */
    public record TaskForm(String title, String description, String subject, LocalDate plannedDate,
                           Integer estimatedMinutes, Integer spentMinutes, Long pageId) {
    }

    public record WeekSummary(int planned, int done, int estimatedMinutes, int spentMinutes) {
    }

    /** Atalho: so os grupos da lista padrao. */
    public List<TaskGroup> groups(LocalDate today) {
        return schedule(today, false).groups();
    }

    /**
     * Um grupo por dia, com as tarefas pendentes e as concluidas. Dias passados ja todos concluidos aparecem
     * so nos ultimos {@link #RECENT_DAYS} dias, ou sempre com {@code showAll}.
     */
    public Schedule schedule(LocalDate today, boolean showAll) {
        List<StudyTask> all = taskRepository.findAll().stream()
                .sorted(Comparator.comparing(StudyTask::getPlannedDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(StudyTask::getId)).toList();
        List<TaskGroup> groups = new ArrayList<>();
        Map<LocalDate, List<StudyTask>> byDate = new LinkedHashMap<>();
        all.stream().filter(t -> t.getPlannedDate() != null)
                .forEach(t -> byDate.computeIfAbsent(t.getPlannedDate(), d -> new ArrayList<>()).add(t));
        int hidden = 0;
        for (var entry : byDate.entrySet()) {
            LocalDate date = entry.getKey();
            List<StudyTask> tasks = entry.getValue();
            boolean pending = tasks.stream().anyMatch(t -> !t.isDone());
            boolean past = date.isBefore(today);
            if (past && !pending && !showAll && date.isBefore(today.minusDays(RECENT_DAYS))) {
                hidden++;
                continue;
            }
            String kind = past ? (pending ? "overdue" : "past") : date.equals(today) ? "today" : "day";
            String label = kind.equals("overdue") ? "Atrasada · " + dayLabel(date, today) : dayLabel(date, today);
            groups.add(new TaskGroup(label, kind, date, tasks));
        }
        List<StudyTask> undated = all.stream().filter(t -> t.getPlannedDate() == null).toList();
        if (!undated.isEmpty()) {
            groups.add(new TaskGroup("Sem data", "undated", null, undated));
        }
        return new Schedule(groups, hidden);
    }

    /** Tarefas de hoje e atrasadas, para a visao geral. */
    public List<StudyTask> dueBy(LocalDate today) {
        return taskRepository.findAll().stream()
                .filter(t -> !t.isDone() && t.getPlannedDate() != null && !t.getPlannedDate().isAfter(today))
                .sorted(Comparator.comparing(StudyTask::getPlannedDate).thenComparing(StudyTask::getId)).toList();
    }

    public WeekSummary week(LocalDate today) {
        LocalDate start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate end = start.plusDays(6);
        List<StudyTask> inWeek = taskRepository.findAll().stream().filter(t -> t.getPlannedDate() != null
                && !t.getPlannedDate().isBefore(start) && !t.getPlannedDate().isAfter(end)).toList();
        int done = (int) inWeek.stream().filter(StudyTask::isDone).count();
        int estimated = inWeek.stream().mapToInt(t -> t.getEstimatedMinutes() == null ? 0 : t.getEstimatedMinutes()).sum();
        int spent = inWeek.stream().mapToInt(t -> t.getSpentMinutes() == null ? 0 : t.getSpentMinutes()).sum();
        return new WeekSummary(inWeek.size(), done, estimated, spent);
    }

    public static String dayLabel(LocalDate date, LocalDate today) {
        // "qui., 15/10" no JDK em portugues: sem o ponto da abreviacao.
        String day = DAY.format(date).replace(".,", ",");
        if (date.equals(today)) {
            return "Hoje · " + day;
        }
        if (date.equals(today.plusDays(1))) {
            return "Amanhã · " + day;
        }
        return Character.toUpperCase(day.charAt(0)) + day.substring(1);
    }

    @Transactional
    public StudyTask create(TaskForm form) {
        StudyTask task = new StudyTask();
        apply(task, form);
        return taskRepository.save(task);
    }

    @Transactional
    public StudyTask update(Long id, TaskForm form) {
        StudyTask task = taskRepository.findById(id).orElseThrow();
        apply(task, form);
        return taskRepository.save(task);
    }

    private void apply(StudyTask task, TaskForm form) {
        String title = StudyPageService.blankToNull(form.title());
        if (title == null) {
            throw new IllegalArgumentException("Dê um título para a tarefa.");
        }
        if (title.length() > StudyPageService.MAX_TITLE) {
            throw new IllegalArgumentException("O título pode ter até " + StudyPageService.MAX_TITLE + " caracteres.");
        }
        if (negative(form.estimatedMinutes()) || negative(form.spentMinutes())) {
            throw new IllegalArgumentException("O tempo não pode ser negativo.");
        }
        if (form.pageId() != null && !pageRepository.existsById(form.pageId())) {
            throw new IllegalArgumentException("A página escolhida não existe mais.");
        }
        task.setTitle(title);
        task.setDescription(StudyPageService.blankToNull(form.description()));
        task.setSubject(StudyPageService.blankToNull(form.subject()));
        task.setPlannedDate(form.plannedDate());
        task.setEstimatedMinutes(form.estimatedMinutes());
        task.setSpentMinutes(form.spentMinutes());
        task.setPageId(form.pageId());
    }

    private static boolean negative(Integer minutes) {
        return minutes != null && minutes < 0;
    }

    /** Conclui ou reabre. Ao concluir, pode registrar o tempo dedicado e agenda a primeira revisao; reabrir tira do ciclo de revisao. */
    @Transactional
    public StudyTask setDone(Long id, boolean done, Integer spentMinutes) {
        StudyTask task = taskRepository.findById(id).orElseThrow();
        task.setDone(done);
        task.setDoneAt(done ? LocalDateTime.now() : null);
        if (done && task.isReview()) {
            spacedRepetition.completeReview(LocalDate.now());
        } else if (done) {
            spacedRepetition.scheduleFirst(task, LocalDate.now());
        } else {
            task.setCurrentStep(0);
            task.setNextReviewDate(null);
        }
        if (spentMinutes != null && spentMinutes >= 0) {
            task.setSpentMinutes(spentMinutes);
        }
        return taskRepository.save(task);
    }

    /** Corrige o tempo dedicado (nulo = nao registrado), sem mudar se esta concluida. */
    @Transactional
    public StudyTask setSpent(Long id, Integer spentMinutes) {
        if (negative(spentMinutes)) {
            throw new IllegalArgumentException("O tempo não pode ser negativo.");
        }
        StudyTask task = taskRepository.findById(id).orElseThrow();
        task.setSpentMinutes(spentMinutes);
        return taskRepository.save(task);
    }

    @Transactional
    public void delete(Long id) {
        taskRepository.deleteById(id);
    }

    public java.util.Optional<StudyTask> forEdit(Long id) {
        return taskRepository.findById(id);
    }

    public List<StudyTask> forPage(Long pageId) {
        return taskRepository.findByPageId(pageId);
    }
}
