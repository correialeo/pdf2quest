package com.pdf2questao.aula;

import com.pdf2questao.study.StudyTask;
import com.pdf2questao.study.StudyTaskService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fila do dia: para cada tarefa, a sequencia Aula -> Quiz -> Concluir e o proximo passo (o botao principal da tela
 * Hoje e da barra de sessao). So le o que ja existe: as aulas e quizzes ligados a tarefa e as respostas dadas.
 */
@Service
public class TodayFlowService {

    /** Um passo da tarefa. kind = aula | quiz | concluir | revisao. link nulo = acao no proprio cartao. */
    public record Step(String kind, Long id, String title, String label, String detail, String link, boolean done) {
    }

    /** A tarefa com seus passos e o proximo a fazer ({@code action}). */
    public record Flow(StudyTask task, List<Step> steps, Step action) {
        public boolean hasLinked() {
            return steps.stream().anyMatch(s -> s.kind().equals("aula") || s.kind().equals("quiz"));
        }

        /** Link do proximo passo; sem aula/quiz, o cartao da tarefa na tela Hoje. */
        public String link() {
            return action.link() != null ? action.link() : "/hoje#t-" + task.getId();
        }

        /** Primeira aula da tarefa (para o "voltar" e o "Ir para o quiz" do fim da aula). */
        public Step lesson() {
            return steps.stream().filter(s -> s.kind().equals("aula")).findFirst().orElse(null);
        }

        /** Primeiro quiz ainda por fazer. */
        public Step pendingQuiz() {
            return steps.stream().filter(s -> s.kind().equals("quiz") && !s.done()).findFirst().orElse(null);
        }
    }

    /** O dia: a fila, o que ja foi feito e o passo para o botao "Continuar". */
    public record Day(List<Flow> open, List<Flow> done, Flow current) {
        public int total() {
            return open.size() + done.size();
        }

        /** Posicao (1..total) da tarefa na fila do dia, para "2 de 5"; 0 = fora da fila. */
        public int position(Long taskId) {
            List<Flow> all = new ArrayList<>(done);
            all.addAll(open);
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).task().getId().equals(taskId)) {
                    return i + 1;
                }
            }
            return 0;
        }

        public boolean contains(Long taskId) {
            return position(taskId) > 0;
        }
    }

    private final LessonService lessons;
    private final LessonRepository lessonRepository;
    private final QuizRepository quizRepository;
    private final StudyTaskService taskService;

    public TodayFlowService(LessonService lessons, LessonRepository lessonRepository, QuizRepository quizRepository,
                            StudyTaskService taskService) {
        this.lessons = lessons;
        this.lessonRepository = lessonRepository;
        this.quizRepository = quizRepository;
        this.taskService = taskService;
    }

    public Day day(LocalDate today) {
        List<StudyTask> tasks = taskService.today(today);
        Map<Long, Flow> flows = flows(tasks);
        List<Flow> open = new ArrayList<>();
        List<Flow> done = new ArrayList<>();
        for (StudyTask t : tasks) {
            (t.isDone() ? done : open).add(flows.get(t.getId()));
        }
        // "Continuar": o que esta pela metade primeiro; senao a primeira da fila (a revisao so se for a unica).
        Flow current = open.stream().filter(f -> f.action().detail() != null && f.action().detail().startsWith("em andamento"))
                .findFirst()
                .or(() -> open.stream().filter(f -> !f.task().isReview()).findFirst())
                .or(() -> open.stream().findFirst()).orElse(null);
        return new Day(open, done, current);
    }

    /** A proxima tarefa aberta da fila depois de {@code exceptTaskId} (as de aula/quiz primeiro). */
    public Flow next(LocalDate today, Long exceptTaskId) {
        Day day = day(today);
        List<Flow> others = day.open().stream()
                .filter(f -> !f.task().isReview() && !f.task().getId().equals(exceptTaskId)).toList();
        // Segue a ordem da fila a partir da tarefa atual; sem ela na fila, a primeira.
        int at = -1;
        for (int i = 0; i < day.open().size(); i++) {
            if (day.open().get(i).task().getId().equals(exceptTaskId)) {
                at = i;
            }
        }
        if (at >= 0) {
            for (int i = at + 1; i < day.open().size(); i++) {
                Flow f = day.open().get(i);
                if (!f.task().isReview()) {
                    return f;
                }
            }
        }
        return others.isEmpty() ? null : others.get(0);
    }

    public Flow flow(StudyTask task) {
        return task == null ? null : flows(List.of(task)).get(task.getId());
    }

    private Map<Long, Flow> flows(List<StudyTask> tasks) {
        Map<Long, Flow> out = new LinkedHashMap<>();
        if (tasks.isEmpty()) {
            return out;
        }
        List<Long> ids = tasks.stream().map(StudyTask::getId).toList();
        Map<Long, List<Lesson>> lessonsBy = new LinkedHashMap<>();
        for (Lesson l : lessonRepository.findByTaskIdInOrderByCreatedAt(ids)) {
            lessonsBy.computeIfAbsent(l.getTaskId(), k -> new ArrayList<>()).add(l);
        }
        Map<Long, List<Quiz>> quizzesBy = new LinkedHashMap<>();
        for (Quiz q : quizRepository.findByTaskIdInOrderByCreatedAt(ids)) {
            quizzesBy.computeIfAbsent(q.getTaskId(), k -> new ArrayList<>()).add(q);
        }
        for (StudyTask t : tasks) {
            out.put(t.getId(), build(t, lessonsBy.getOrDefault(t.getId(), List.of()),
                    quizzesBy.getOrDefault(t.getId(), List.of())));
        }
        return out;
    }

    private Flow build(StudyTask task, List<Lesson> taskLessons, List<Quiz> taskQuizzes) {
        List<Step> steps = new ArrayList<>();
        List<Long> seenQuizzes = new ArrayList<>();
        for (Lesson lesson : taskLessons) {
            List<Quiz> qs = lessons.quizzes(lesson.getId());
            Quiz fixation = qs.stream().filter(Quiz::isEmbedded).reduce((a, b) -> b).orElse(null);
            List<Quiz> real = qs.stream().filter(q -> !q.isEmbedded()).toList();
            LessonService.QuizResult fx = fixation == null ? null : lessons.result(fixation.getId());
            boolean quizStarted = real.stream().anyMatch(q -> lessons.result(q.getId()).respondidas() > 0);
            // Aula lida: respondeu toda a fixacao, ou ja partiu para um quiz dela.
            boolean read = (fx != null && fx.perguntas() > 0 && fx.respondidas() >= fx.perguntas()) || quizStarted;
            boolean started = fx != null && fx.respondidas() > 0;
            String detail = fx == null || fx.perguntas() == 0 ? null
                    : "fixação " + fx.respondidas() + "/" + fx.perguntas();
            steps.add(new Step("aula", lesson.getId(), lesson.getTitle(),
                    read ? "Rever aula" : started ? "Continuar aula" : "Começar aula",
                    started && !read ? "em andamento · " + detail : detail, "/aulas/" + lesson.getId(), read));
            for (Quiz q : real) {
                steps.add(quizStep(q));
                seenQuizzes.add(q.getId());
            }
        }
        for (Quiz q : taskQuizzes) {
            if (!q.isEmbedded() && !seenQuizzes.contains(q.getId())) {
                steps.add(quizStep(q));
            }
        }
        Step action;
        if (task.isReview()) {
            // Revisao com quizzes ligados: o botao abre o proximo quiz pendente. Sem quiz (ou todos feitos), o
            // cartao da tarefa, onde se marca cada assunto.
            List<Step> quizzes = steps.stream().filter(s -> s.kind().equals("quiz")).toList();
            Step pending = quizzes.stream().filter(s -> !s.done()).findFirst().orElse(null);
            boolean started = quizzes.stream().anyMatch(s -> s.done()
                    || (s.detail() != null && s.detail().startsWith("em andamento")));
            action = pending == null
                    ? new Step("revisao", null, task.getTitle(), quizzes.isEmpty() ? "Revisar" : "Marcar revisão",
                    quizzes.isEmpty() ? null : "quizzes feitos", null, task.isDone())
                    : new Step("quiz", pending.id(), pending.title(), started ? "Continuar revisão" : "Começar revisão",
                    pending.detail(), pending.link(), false);
        } else {
            action = steps.stream().filter(s -> !s.done()).findFirst()
                    .orElse(new Step("concluir", null, task.getTitle(), "Concluir", null, null, task.isDone()));
        }
        return new Flow(task, steps, action);
    }

    private Step quizStep(Quiz q) {
        LessonService.QuizResult r = lessons.result(q.getId());
        String detail = r.concluido() ? r.acertos() + "/" + r.perguntas() + " certas"
                : r.respondidas() > 0 ? "em andamento · " + r.respondidas() + "/" + r.perguntas()
                : r.perguntas() + " perguntas";
        return new Step("quiz", q.getId(), q.getTitle(),
                r.concluido() ? "Rever quiz" : r.respondidas() > 0 ? "Continuar quiz" : "Fazer quiz",
                detail, "/quizzes/" + q.getId(), r.concluido());
    }
}
