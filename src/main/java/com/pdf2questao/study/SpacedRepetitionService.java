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
 * Os assuntos a revisar no dia viram uma tarefa por concurso (workspace), no cronograma padrao dele. A tarefa nao junta
 * tudo o que venceu: {@link ReviewFocusService} escolhe poucas disciplinas (as mais fracas nos quizzes) e o resto
 * espera os proximos dias. Os escolhidos ficam gravados na tarefa (o foco nao muda no meio do dia) e concluir a tarefa
 * conta como "lembrei" so para eles. Assunto de tarefa avulsa (sem cronograma) tem a sua propria tarefa, vista so em
 * "Todos".
 */
@Service
public class SpacedRepetitionService {

    private final StudyTaskRepository taskRepository;
    private final ConcursoService concursoService;
    private final Workspace workspace;
    private final ReviewFocusService focusService;
    private final List<Integer> intervals;
    private final int reviewMinutes;
    private final int reviewMinutesSunday;

    public SpacedRepetitionService(StudyTaskRepository taskRepository, ConcursoService concursoService,
                                   Workspace workspace, ReviewFocusService focusService,
                                   @Value("${pdf2questao.revisao.intervalos:1,3,7,14,30}") List<Integer> intervals,
                                   @Value("${pdf2questao.revisao.minutos:30}") int reviewMinutes,
                                   @Value("${pdf2questao.revisao.minutos-domingo:60}") int reviewMinutesSunday) {
        if (intervals.isEmpty() || intervals.stream().anyMatch(d -> d == null || d < 1)) {
            throw new IllegalStateException("pdf2questao.revisao.intervalos precisa de dias positivos, ex.: 1,3,7,14,30");
        }
        this.taskRepository = taskRepository;
        this.concursoService = concursoService;
        this.workspace = workspace;
        this.focusService = focusService;
        this.intervals = List.copyOf(intervals);
        this.reviewMinutes = reviewMinutes;
        this.reviewMinutesSunday = reviewMinutesSunday;
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

    /** Lembrou com dificuldade: repete o ultimo intervalo, sem subir de etapa. */
    @Transactional
    public StudyTask hard(Long id, LocalDate today) {
        StudyTask task = taskRepository.findById(id).orElseThrow();
        int step = Math.min(Math.max(task.getCurrentStep(), 1), intervals.size() - 1);
        task.setNextReviewDate(today.plusDays(intervals.get(step - 1)));
        task.setCurrentStep(step);
        return taskRepository.save(task);
    }

    /** Resultado da revisao de um assunto: "bom" (avanca), "dificil" (repete o intervalo) ou "errei" (volta a amanha). */
    @Transactional
    public StudyTask mark(Long id, String result, LocalDate today) {
        String r = result == null ? "" : com.pdf2questao.scoring.ScoringCalculator.normalize(result).strip();
        return switch (r) {
            case "bom", "lembrei", "facil" -> remembered(id, today);
            case "dificil" -> hard(id, today);
            case "errei", "esqueci" -> forgot(id, today);
            default -> throw new IllegalArgumentException("Resultado da revisão: bom, dificil ou errei.");
        };
    }

    /** Assuntos escolhidos para hoje (tarefas de revisao abertas de hoje) que ainda nao foram marcados, do concurso em vigor. */
    public List<StudyTask> todayItems(LocalDate today) {
        Set<Long> chosen = chosenToday(today);
        return due(today).stream().filter(t -> chosen.contains(t.getId())).toList();
    }

    /** Como {@link #todayItems}, de um concurso especifico (nulo = avulsos). */
    public List<StudyTask> todayItemsFor(Long concursoId, LocalDate today) {
        Set<Long> chosen = chosenToday(today);
        return dueFor(concursoId, today).stream().filter(t -> chosen.contains(t.getId())).toList();
    }

    /** Tarefa de revisao aberta de hoje que tem o assunto {@code subjectTaskId} no foco (vazio = nao esta no foco). */
    @Transactional
    public java.util.Optional<StudyTask> openReviewFor(Long subjectTaskId, LocalDate today) {
        if (subjectTaskId == null) {
            return java.util.Optional.empty();
        }
        syncReviewTask(today);
        return taskRepository.findAllByReviewDate(today).stream()
                .filter(t -> !t.isDone() && t.reviewItemIds().contains(subjectTaskId)).findFirst();
    }

    /** Tarefas de revisao abertas de hoje do concurso em vigor (em "Todos", de todos). */
    public List<StudyTask> openReviewTasks(LocalDate today) {
        Map<Long, Long> concursoBy = concursoByCronograma();
        return taskRepository.findAllByReviewDate(today).stream()
                .filter(t -> !t.isDone() && workspace.matches(concursoOf(t, concursoBy))).toList();
    }

    private Set<Long> chosenToday(LocalDate today) {
        Set<Long> chosen = new LinkedHashSet<>();
        taskRepository.findAllByReviewDate(today).stream().filter(t -> !t.isDone())
                .forEach(t -> chosen.addAll(t.reviewItemIds()));
        return chosen;
    }

    /**
     * Foco de hoje do concurso em vigor (em "Todos", de todos): os assuntos das tarefas do dia que ainda faltam, com a
     * fraqueza de cada disciplina, e o que ficou para os proximos dias.
     */
    public ReviewFocusService.Focus focus(LocalDate today) {
        syncReviewTask(today);
        List<StudyTask> due = due(today);
        Set<Long> chosen = chosenToday(today);
        return focusService.describe(due.stream().filter(t -> chosen.contains(t.getId())).toList(),
                due.stream().filter(t -> !chosen.contains(t.getId())).toList(), today);
    }

    /**
     * Deixa as tarefas de revisao do dia em dia com os assuntos pendentes, uma por concurso (cada assunto so entra
     * na do seu concurso): cria na primeira vez, traz para hoje uma que ficou aberta de outro dia (ou apaga, se nao
     * sobrou assunto). Independe do concurso em vigor: monta a de todos.
     */
    @Transactional
    public void syncReviewTask(LocalDate today) {
        Map<Long, Long> concursoBy = concursoByCronograma();
        Set<Long> archived = new java.util.HashSet<>();
        concursoService.byId().forEach((id, c) -> {
            if (c.isArquivado()) {
                archived.add(id);
            }
        });
        // Concurso arquivado nao ganha revisao nova (a aberta de outro dia e apagada abaixo, por ficar sem assunto).
        Map<Long, List<StudyTask>> due = byConcurso(taskRepository.findDueReviews(today).stream()
                .filter(t -> !archived.contains(concursoOf(t, concursoBy))).toList(), concursoBy);
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
        if (task == null && reviewedToday) {
            return; // a de hoje ja foi concluida; o que vencer agora entra na de amanha
        }
        ReviewFocusService.Focus focus;
        if (task != null && !stale && !task.reviewItemIds().isEmpty()) {
            // O foco de hoje ja foi escolhido: so tira o que ja foi marcado (o que venceu depois espera amanha).
            List<Long> ids = task.reviewItemIds();
            List<StudyTask> chosen = items.stream().filter(t -> ids.contains(t.getId()))
                    .sorted(java.util.Comparator.comparingInt(t -> ids.indexOf(t.getId()))).toList();
            if (chosen.isEmpty()) {
                return;
            }
            focus = focusService.describe(chosen, items.stream().filter(t -> !ids.contains(t.getId())).toList(), today);
        } else {
            if (task == null) {
                task = new StudyTask();
                task.setCronogramaId(concursoId == null ? null : concursoService.defaultCronograma(concursoId).getId());
            }
            focus = focusService.choose(items, today);
            task.setReviewItems(String.join(",", focus.items().stream().map(t -> String.valueOf(t.getId())).toList()));
        }
        task.setReviewDate(today);
        task.setPlannedDate(today);
        task.setEstimatedMinutes(today.getDayOfWeek() == java.time.DayOfWeek.SUNDAY ? reviewMinutesSunday : reviewMinutes);
        int n = focus.items().size();
        task.setTitle("Revisão · Foco: " + focus.label() + " · " + n + (n == 1 ? " assunto" : " assuntos"));
        task.setDescription(describe(focus));
        taskRepository.save(task);
    }

    /** Texto da tarefa: as disciplinas em foco com o acerto, os conceitos mais errados, os assuntos e o que espera. */
    static String describe(ReviewFocusService.Focus focus) {
        List<String> lines = new ArrayList<>();
        for (ReviewFocusService.FocusSubject f : focus.foco()) {
            String acerto = f.acertoQuiz() == null ? "sem quiz ainda" : f.acertoQuiz() + "% de acerto nos quizzes";
            String weak = f.conceitosFracos().isEmpty() ? ""
                    : " · mais erra: " + String.join(", ", f.conceitosFracos().stream()
                    .map(c -> c.conceito() + " (" + c.erros() + "/" + c.vezes() + ")").toList());
            lines.add("Foco " + f.disciplina() + " — " + acerto + weak);
            f.assuntos().forEach(t -> lines.add("- " + t.getTitle()
                    + (t.getSubject() == null ? "" : " (" + t.getSubject() + ")")));
        }
        if (!focus.aguardando().isEmpty()) {
            int waiting = focus.aguardando().stream().mapToInt(ReviewFocusService.Waiting::assuntos).sum();
            lines.add("Aguardando: " + waiting + (waiting == 1 ? " assunto" : " assuntos") + " de "
                    + focus.aguardando().size() + (focus.aguardando().size() == 1 ? " disciplina" : " disciplinas")
                    + " (entram nos próximos dias, as mais fracas primeiro).");
        }
        return String.join("\n", lines);
    }

    private static Map<Long, List<StudyTask>> byConcurso(List<StudyTask> tasks, Map<Long, Long> concursoBy) {
        Map<Long, List<StudyTask>> out = new LinkedHashMap<>();
        tasks.forEach(t -> out.computeIfAbsent(concursoOf(t, concursoBy), k -> new ArrayList<>()).add(t));
        return out;
    }

    /**
     * Concluir a tarefa de revisao: os assuntos escolhidos para ela que ainda nao foram marcados contam como lembrados
     * (os que ficaram aguardando nao andam). Tarefa antiga, sem a lista, avanca os do concurso, como antes.
     */
    void completeReview(StudyTask review, LocalDate today) {
        List<Long> ids = review.reviewItemIds();
        dueFor(concursoService.concursoOfTask(review), today).stream()
                .filter(t -> ids.isEmpty() || ids.contains(t.getId()))
                .forEach(t -> remembered(t.getId(), today));
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
