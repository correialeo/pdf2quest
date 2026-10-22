package com.pdf2questao.aula;

import com.pdf2questao.aula.QuizQuestion.Alternative;
import com.pdf2questao.scoring.ScoringCalculator;
import com.pdf2questao.study.StudyTask;
import com.pdf2questao.study.StudyTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Aulas e quizzes. Uma aula pode ter varios quizzes; um quiz pode ser avulso (so disciplina/topico). Criar e pelo
 * MCP; responder e na tela. Todo quiz novo retoma os erros dos quizzes anteriores do mesmo assunto.
 */
@Service
public class LessonService {

    public static final Set<String> LEVELS = Set.of("basico", "intermediario", "avancado", "prova");

    private final LessonRepository lessonRepository;
    private final QuizRepository quizRepository;
    private final QuizQuestionRepository questionRepository;
    private final QuizAttemptRepository attemptRepository;
    private final StudyTaskRepository taskRepository;

    public LessonService(LessonRepository lessonRepository, QuizRepository quizRepository,
                         QuizQuestionRepository questionRepository, QuizAttemptRepository attemptRepository,
                         StudyTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
        this.lessonRepository = lessonRepository;
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.attemptRepository = attemptRepository;
    }

    // ---- aulas ----

    public List<Lesson> all() {
        return lessonRepository.findAllByOrderByCreatedAtDesc();
    }

    public Lesson find(Long id) {
        return lessonRepository.findById(id).orElseThrow();
    }

    @Transactional
    public Lesson create(String title, String subject, String editalTopic, String content, Long taskId) {
        if (title == null || title.isBlank() || content == null || content.isBlank()) {
            throw new IllegalArgumentException("A aula precisa de título e conteúdo.");
        }
        Lesson lesson = new Lesson();
        lesson.setTitle(title.strip());
        lesson.setSubject(canonicalSubject(subject));
        lesson.setEditalTopic(blank(editalTopic));
        lesson.setContent(content.strip());
        lesson.setTaskId(checkTask(taskId));
        return lessonRepository.save(lesson);
    }

    // ---- ligacao com o cronograma ----

    /** Aula ou quiz ligado a uma tarefa, com o andamento para mostrar na tarefa. */
    public record Linked(String tipo, Long id, String titulo, String andamento, boolean concluido, String link) {
    }

    /** Aulas e quizzes de cada tarefa. O quiz sem tarefa propria herda a da aula (aparece dentro dela). */
    public Map<Long, List<Linked>> forTasks(Collection<Long> taskIds) {
        Map<Long, List<Linked>> out = new LinkedHashMap<>();
        if (taskIds == null || taskIds.isEmpty()) {
            return out;
        }
        for (Lesson lesson : lessonRepository.findByTaskIdInOrderByCreatedAt(taskIds)) {
            List<Quiz> qs = quizzes(lesson.getId());
            long answered = qs.stream().filter(q -> q.getFinishedAt() != null).count();
            boolean done = !qs.isEmpty() && answered == qs.size();
            String progress = qs.isEmpty() ? "sem quiz" : answered + " de " + qs.size() + " quizzes respondidos";
            out.computeIfAbsent(lesson.getTaskId(), k -> new ArrayList<>())
                    .add(new Linked("Aula", lesson.getId(), lesson.getTitle(), progress, done, "/aulas/" + lesson.getId()));
        }
        for (Quiz quiz : quizRepository.findByTaskIdInOrderByCreatedAt(taskIds)) {
            Long lessonTask = quiz.getLessonId() == null ? null : find(quiz.getLessonId()).getTaskId();
            if (quiz.getTaskId().equals(lessonTask)) {
                continue;   // ja aparece pela aula
            }
            QuizResult r = result(quiz.getId());
            String progress = r.respondidas() == 0 ? "a responder"
                    : r.acertos() + "/" + r.respondidas() + " acertos" + (r.concluido() ? "" : " (em andamento)");
            out.computeIfAbsent(quiz.getTaskId(), k -> new ArrayList<>())
                    .add(new Linked("Quiz", quiz.getId(), quiz.getTitle(), progress, r.concluido(), "/quizzes/" + quiz.getId()));
        }
        return out;
    }

    public List<Linked> forTask(Long taskId) {
        return forTasks(List.of(taskId)).getOrDefault(taskId, List.of());
    }

    /** Tarefa de uma aula ou quiz (a do quiz ou, sem ela, a da aula), para o link de volta. */
    public StudyTask task(Long taskId) {
        return taskId == null ? null : taskRepository.findById(taskId).orElse(null);
    }

    public Long effectiveTaskId(Quiz quiz) {
        return quiz.getTaskId() != null || quiz.getLessonId() == null ? quiz.getTaskId()
                : find(quiz.getLessonId()).getTaskId();
    }

    /** Liga (ou desliga, com tarefa null) uma aula e/ou um quiz a uma tarefa do cronograma. */
    @Transactional
    public void linkTask(Long taskId, Long lessonId, Long quizId) {
        if (lessonId == null && quizId == null) {
            throw new IllegalArgumentException("Informe a aula e/ou o quiz.");
        }
        Long task = checkTask(taskId);
        if (lessonId != null) {
            Lesson lesson = lessonRepository.findById(lessonId)
                    .orElseThrow(() -> new IllegalArgumentException("Aula " + lessonId + " não existe."));
            lesson.setTaskId(task);
            lessonRepository.save(lesson);
        }
        if (quizId != null) {
            Quiz quiz = quiz(quizId);
            quiz.setTaskId(task);
            quizRepository.save(quiz);
        }
    }

    private Long checkTask(Long taskId) {
        if (taskId != null && !taskRepository.existsById(taskId)) {
            throw new IllegalArgumentException("Tarefa " + taskId + " não existe.");
        }
        return taskId;
    }

    public List<Quiz> quizzes(Long lessonId) {
        return quizRepository.findByLessonIdOrderByCreatedAt(lessonId);
    }

    /** Aula concluida = tem quiz e todos os quizzes foram respondidos. */
    public boolean lessonDone(Long lessonId) {
        List<Quiz> qs = quizzes(lessonId);
        return !qs.isEmpty() && qs.stream().allMatch(q -> q.getFinishedAt() != null);
    }

    // ---- quizzes ----

    public List<Quiz> allQuizzes() {
        return quizRepository.findAllByOrderByCreatedAtDesc();
    }

    public Quiz quiz(Long id) {
        return quizRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Quiz " + id + " não existe."));
    }

    public List<QuizQuestion> questions(Long quizId) {
        return questionRepository.findByQuizIdOrderByPosition(quizId);
    }

    public record PreviousError(Long perguntaId, Long quizId, String quiz, String topico, String enunciado,
                                String suaResposta, String explicacaoDaSuaResposta, String correta,
                                String explicacaoDaCorreta, LocalDateTime respondidaEm) {
    }

    /**
     * Erros (ultima resposta errada) dos quizzes do mesmo assunto: da aula, se informada; senao da disciplina e/ou
     * topico (parte do nome, sem acento). Os mais recentes primeiro. Um erro sai da lista quando um quiz posterior
     * que o retomou foi respondido e o usuario acertou todas as perguntas daquele topico.
     */
    public List<PreviousError> previousErrors(Long lessonId, String subject, String topic, Long exceptQuiz) {
        String s = subject == null || subject.isBlank() ? null : ScoringCalculator.normalize(subject);
        String t = topic == null || topic.isBlank() ? null : ScoringCalculator.normalize(topic);
        if (lessonId == null && s == null && t == null) {
            throw new IllegalArgumentException("Informe a aula, a disciplina ou o tópico.");
        }
        Map<Long, Quiz> quizzes = allQuizzes().stream().collect(Collectors.toMap(Quiz::getId, Function.identity()));
        List<QuizQuestion> candidates = questionRepository.findAll().stream()
                .filter(q -> exceptQuiz == null || !exceptQuiz.equals(q.getQuizId()))
                .filter(q -> lessonId != null ? lessonId.equals(q.getLessonId())
                        : (s == null || q.getSubject() != null && contains(q.getSubject(), s))
                        && (t == null || q.getTopic() != null && contains(q.getTopic(), t)))
                .toList();
        Map<Long, QuizAttempt> last = new LinkedHashMap<>();
        attemptRepository.findByQuizQuestionIdInOrderByAnsweredAt(candidates.stream().map(QuizQuestion::getId).toList())
                .forEach(a -> last.put(a.getQuizQuestionId(), a));
        List<PreviousError> out = new ArrayList<>();
        for (QuizQuestion q : candidates) {
            QuizAttempt a = last.get(q.getId());
            if (a == null || Boolean.TRUE.equals(a.getCorrect()) || resolved(q, quizzes.values())) {
                continue;
            }
            Map<String, Alternative> alt = q.alternativeList().stream()
                    .collect(Collectors.toMap(Alternative::letra, Function.identity()));
            Quiz quiz = quizzes.get(q.getQuizId());
            out.add(new PreviousError(q.getId(), q.getQuizId(), quiz == null ? null : quiz.getTitle(), q.getTopic(),
                    q.getStatement(), a.getSelectedAnswer(),
                    alt.containsKey(a.getSelectedAnswer()) ? alt.get(a.getSelectedAnswer()).explicacao() : null,
                    q.getCorrectAnswer(), alt.containsKey(q.getCorrectAnswer()) ? alt.get(q.getCorrectAnswer()).explicacao() : null,
                    a.getAnsweredAt()));
        }
        out.sort(Comparator.comparing(PreviousError::respondidaEm).reversed());
        return out;
    }

    /** Erro em aberto para a caixa de erros: a pergunta inteira, para responder de novo na tela. */
    public record OpenError(QuizQuestion pergunta, String quiz, String disciplina, String suaResposta,
                            LocalDateTime respondidaEm) {
    }

    /**
     * Caixa de erros: perguntas de quiz cuja ultima resposta foi errada (e que nenhum quiz posterior resolveu), de
     * todas as disciplinas ou de uma. Responder certo de novo tira a pergunta da caixa.
     */
    public List<OpenError> openErrors(String subject) {
        String s = blank(subject) == null ? null : ScoringCalculator.normalize(subject);
        Map<Long, Quiz> quizzes = allQuizzes().stream().collect(Collectors.toMap(Quiz::getId, Function.identity()));
        List<QuizQuestion> candidates = questionRepository.findAll().stream()
                .filter(q -> s == null || ScoringCalculator.normalize(subjectName(q.getSubject())).equals(s))
                .toList();
        Map<Long, QuizAttempt> last = new LinkedHashMap<>();
        attemptRepository.findByQuizQuestionIdInOrderByAnsweredAt(candidates.stream().map(QuizQuestion::getId).toList())
                .forEach(a -> last.put(a.getQuizQuestionId(), a));
        List<OpenError> out = new ArrayList<>();
        for (QuizQuestion q : candidates) {
            QuizAttempt a = last.get(q.getId());
            if (a == null || Boolean.TRUE.equals(a.getCorrect()) || resolved(q, quizzes.values())) {
                continue;
            }
            Quiz quiz = quizzes.get(q.getQuizId());
            out.add(new OpenError(q, quiz == null ? null : quiz.getTitle(), subjectName(q.getSubject()),
                    a.getSelectedAnswer(), a.getAnsweredAt()));
        }
        out.sort(Comparator.comparing(OpenError::respondidaEm).reversed());
        return out;
    }

    /** Erros em aberto por disciplina (mais erros primeiro), para os atalhos da caixa de erros. */
    public Map<String, Long> openErrorsBySubject() {
        Map<String, Long> out = openErrors(null).stream()
                .collect(Collectors.groupingBy(OpenError::disciplina, LinkedHashMap::new, Collectors.counting()));
        return out.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    private static String subjectName(String subject) {
        return blank(subject) == null ? "Sem disciplina" : subject.strip();
    }

    /** Retomado por um quiz posterior, ja respondido, em que todas as perguntas do mesmo topico foram acertadas. */
    private boolean resolved(QuizQuestion error, java.util.Collection<Quiz> quizzes) {
        for (Quiz later : quizzes) {
            if (later.getId().equals(error.getQuizId()) || later.getPreviousErrors() == null
                    || later.getPreviousErrors().lines().noneMatch(l -> l.startsWith("#" + error.getId() + " "))) {
                continue;
            }
            Map<Long, QuizAttempt> answers = latest(later.getId());
            List<QuizQuestion> sameTopic = questions(later.getId()).stream()
                    .filter(q -> error.getTopic() == null || error.getTopic().equals(q.getTopic())).toList();
            List<QuizQuestion> checked = sameTopic.isEmpty() ? questions(later.getId()) : sameTopic;
            if (!checked.isEmpty() && checked.stream().allMatch(q -> answers.containsKey(q.getId())
                    && Boolean.TRUE.equals(answers.get(q.getId()).getCorrect()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String value, String normalizedWanted) {
        String v = ScoringCalculator.normalize(value);
        return v.contains(normalizedWanted) || normalizedWanted.contains(v);
    }

    public record NewQuestion(String statement, String topic, List<String> texts, List<String> explanations,
                              String correct) {
    }

    /**
     * Cria um quiz (da aula ou avulso). Se ha erros em quizzes anteriores do mesmo assunto, exige que o quiz diga
     * quais retoma ({@code reviewedErrors}, ids das perguntas erradas): nenhum quiz novo ignora os erros anteriores.
     */
    @Transactional
    public Quiz createQuiz(Long lessonId, String title, String subject, String topic, String level, String doubts,
                           List<Long> reviewedErrors, List<NewQuestion> questions, Long taskId) {
        Lesson lesson = lessonId == null ? null : find(lessonId);
        String subj = lesson != null && blank(subject) == null ? lesson.getSubject() : canonicalSubject(subject);
        String top = lesson != null && blank(topic) == null ? lesson.getEditalTopic() : blank(topic);
        if (lesson == null && subj == null && top == null) {
            throw new IllegalArgumentException("Quiz avulso precisa de disciplina ou tópico.");
        }
        String lvl = level == null || level.isBlank() ? null : ScoringCalculator.normalize(level);
        if (lvl != null && !LEVELS.contains(lvl)) {
            throw new IllegalArgumentException("Nível: basico, intermediario, avancado ou prova.");
        }
        List<PreviousError> errors = previousErrors(lessonId, lessonId == null ? subj : null,
                lessonId == null ? top : null, null);
        List<Long> reviewed = reviewedErrors == null ? List.of() : reviewedErrors;
        if (!errors.isEmpty() && errors.stream().noneMatch(e -> reviewed.contains(e.perguntaId()))) {
            throw new IllegalArgumentException("Há " + errors.size() + " erros em quizzes anteriores deste assunto "
                    + "(perguntas " + errors.stream().limit(10).map(e -> "#" + e.perguntaId())
                    .collect(Collectors.joining(", ")) + "). Leia com erros_quiz_anteriores, crie perguntas que "
                    + "retomem esses pontos e informe os ids em errosRetomados.");
        }
        Quiz quiz = new Quiz();
        quiz.setLessonId(lessonId);
        quiz.setTaskId(checkTask(taskId));
        quiz.setSubject(subj);
        quiz.setTopic(top);
        quiz.setLevel(lvl);
        quiz.setDoubts(blank(doubts));
        quiz.setPreviousErrors(errors.stream().filter(e -> reviewed.contains(e.perguntaId()))
                .map(e -> "#" + e.perguntaId() + " " + (e.topico() == null ? "" : e.topico() + ": ")
                        + clip(e.enunciado(), 120))
                .collect(Collectors.joining("\n")));
        int number = lessonId == null ? 0 : quizzes(lessonId).size();
        quiz.setTitle(blank(title) != null ? title.strip()
                : (lesson != null ? "Quiz " + (number + 1) : "Quiz – " + (top != null ? top : subj)));
        quiz = quizRepository.save(quiz);
        addQuestions(quiz.getId(), questions);
        return quiz;
    }

    /** Acrescenta perguntas a um quiz. Toda alternativa precisa de explicacao (por que certa/errada). */
    @Transactional
    public List<QuizQuestion> addQuestions(Long quizId, List<NewQuestion> questions) {
        Quiz quiz = quiz(quizId);
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("Envie ao menos uma pergunta.");
        }
        int position = questions(quizId).size();
        List<QuizQuestion> saved = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            NewQuestion n = questions.get(i);
            String label = "Pergunta " + (i + 1) + ": ";
            if (n.statement() == null || n.statement().isBlank()) {
                throw new IllegalArgumentException(label + "falta o enunciado.");
            }
            if (n.texts() == null || n.texts().size() < 2 || n.texts().size() > 5) {
                throw new IllegalArgumentException(label + "de 2 a 5 alternativas.");
            }
            if (n.explanations() == null || n.explanations().size() != n.texts().size()
                    || n.explanations().stream().anyMatch(e -> e == null || e.isBlank())) {
                throw new IllegalArgumentException(label + "cada alternativa precisa da explicação de por que está "
                        + "certa ou errada.");
            }
            List<Alternative> alternatives = new ArrayList<>();
            for (int a = 0; a < n.texts().size(); a++) {
                alternatives.add(new Alternative(String.valueOf((char) ('A' + a)), n.texts().get(a).strip(),
                        n.explanations().get(a).strip()));
            }
            String correct = n.correct() == null ? "" : n.correct().strip().toUpperCase(Locale.ROOT);
            if (alternatives.stream().noneMatch(a -> a.letra().equals(correct))) {
                throw new IllegalArgumentException(label + "a correta (\"" + n.correct() + "\") não é uma das alternativas.");
            }
            QuizQuestion q = new QuizQuestion();
            q.setQuizId(quizId);
            q.setLessonId(quiz.getLessonId());
            q.setPosition(++position);
            q.setSubject(quiz.getSubject());
            q.setTopic(blank(n.topic()) != null ? n.topic().strip() : quiz.getTopic());
            q.setStatement(n.statement().strip());
            q.setAlternativeList(alternatives);
            q.setCorrectAnswer(correct);
            saved.add(questionRepository.save(q));
        }
        if (quiz.getFinishedAt() != null) {
            quiz.setFinishedAt(null);   // pergunta nova: o quiz volta a ter o que responder
            quizRepository.save(quiz);
        }
        return saved;
    }

    /**
     * Corrige uma pergunta (campos nulos ficam como estao). Mudando a correta ou as alternativas, as respostas ja
     * dadas sao recorrigidas pela nova correta (o placar e os erros anteriores acompanham).
     */
    @Transactional
    public QuizQuestion fixQuestion(Long quizId, int number, String statement, String topic, List<String> texts,
                                    List<String> explanations, String correct) {
        QuizQuestion q = questions(quizId).stream().filter(x -> Integer.valueOf(number).equals(x.getPosition()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("O quiz " + quizId + " não tem a pergunta "
                        + number + "."));
        if (statement != null && !statement.isBlank()) {
            q.setStatement(statement.strip());
        }
        if (topic != null && !topic.isBlank()) {
            q.setTopic(topic.strip());
        }
        List<Alternative> alternatives = new ArrayList<>(q.alternativeList());
        if (texts != null && !texts.isEmpty()) {
            if (texts.size() < 2 || texts.size() > 5 || explanations == null || explanations.size() != texts.size()
                    || texts.stream().anyMatch(t -> t == null || t.isBlank())
                    || explanations.stream().anyMatch(e -> e == null || e.isBlank())) {
                throw new IllegalArgumentException("Envie de 2 a 5 alternativas, cada uma com texto e explicação.");
            }
            alternatives.clear();
            for (int a = 0; a < texts.size(); a++) {
                alternatives.add(new Alternative(String.valueOf((char) ('A' + a)), texts.get(a).strip(),
                        explanations.get(a).strip()));
            }
        } else if (explanations != null && !explanations.isEmpty()) {
            if (explanations.size() != alternatives.size() || explanations.stream().anyMatch(e -> e == null || e.isBlank())) {
                throw new IllegalArgumentException("Envie uma explicação para cada uma das " + alternatives.size()
                        + " alternativas.");
            }
            for (int a = 0; a < alternatives.size(); a++) {
                Alternative old = alternatives.get(a);
                alternatives.set(a, new Alternative(old.letra(), old.texto(), explanations.get(a).strip()));
            }
        }
        String letter = correct == null || correct.isBlank() ? q.getCorrectAnswer()
                : correct.strip().toUpperCase(Locale.ROOT);
        if (alternatives.stream().noneMatch(a -> a.letra().equals(letter))) {
            throw new IllegalArgumentException("A correta (\"" + letter + "\") não é uma das alternativas.");
        }
        q.setAlternativeList(alternatives);
        q.setCorrectAnswer(letter);
        questionRepository.save(q);
        List<QuizAttempt> attempts = attemptRepository.findByQuizQuestionIdInOrderByAnsweredAt(List.of(q.getId()));
        attempts.forEach(a -> a.setCorrect(letter.equals(a.getSelectedAnswer())));
        attemptRepository.saveAll(attempts);
        return q;
    }

    public record AnswerResult(boolean correct, String correctAnswer, boolean finished, int correctCount, int total) {
    }

    /** Responde uma pergunta na tela. Quando todas tem resposta, o quiz fica concluido. */
    @Transactional
    public AnswerResult answer(Long questionId, String selected) {
        QuizQuestion q = questionRepository.findById(questionId).orElseThrow();
        QuizAttempt attempt = new QuizAttempt();
        attempt.setQuizQuestionId(questionId);
        attempt.setSelectedAnswer(selected == null ? null : selected.strip().toUpperCase(Locale.ROOT));
        attempt.setCorrect(q.getCorrectAnswer().equals(attempt.getSelectedAnswer()));
        attemptRepository.save(attempt);
        Map<Long, QuizAttempt> latest = latest(q.getQuizId());
        List<QuizQuestion> all = questions(q.getQuizId());
        boolean finished = all.stream().allMatch(x -> latest.containsKey(x.getId()));
        if (finished) {
            Quiz quiz = quiz(q.getQuizId());
            quiz.setFinishedAt(LocalDateTime.now());
            quizRepository.save(quiz);
        }
        int right = (int) latest.values().stream().filter(a -> Boolean.TRUE.equals(a.getCorrect())).count();
        return new AnswerResult(attempt.getCorrect(), q.getCorrectAnswer(), finished, right, all.size());
    }

    /** Refazer: as respostas antigas ficam no historico, mas a tela volta a ficar em branco. */
    @Transactional
    public void restart(Long quizId) {
        Quiz quiz = quiz(quizId);
        quiz.setFinishedAt(null);
        quiz.setRestartedAt(LocalDateTime.now());
        quizRepository.save(quiz);
    }

    /** Ultima resposta de cada pergunta do quiz (desde a ultima vez que foi recomecado). */
    public Map<Long, QuizAttempt> latest(Long quizId) {
        List<QuizQuestion> qs = questions(quizId);
        LocalDateTime since = quiz(quizId).getRestartedAt();
        Map<Long, QuizAttempt> out = new LinkedHashMap<>();
        for (QuizAttempt a : attemptRepository.findByQuizQuestionIdInOrderByAnsweredAt(
                qs.stream().map(QuizQuestion::getId).toList())) {
            if (since == null || !a.getAnsweredAt().isBefore(since)) {
                out.put(a.getQuizQuestionId(), a);
            }
        }
        return out;
    }

    public record QuestionResult(Long perguntaId, int numero, String topico, String enunciado, String suaResposta,
                                 String correta, Boolean acertou, String explicacaoDaSuaResposta) {
    }

    public record QuizResult(Long quizId, String titulo, Long aulaId, String disciplina, String topico, String nivel,
                             int perguntas, int respondidas, int acertos, boolean concluido,
                             List<QuestionResult> questoes) {
    }

    public QuizResult result(Long quizId) {
        Quiz quiz = quiz(quizId);
        List<QuizQuestion> qs = questions(quizId);
        Map<Long, QuizAttempt> latest = latest(quizId);
        List<QuestionResult> items = new ArrayList<>();
        for (QuizQuestion q : qs) {
            QuizAttempt a = latest.get(q.getId());
            Map<String, Alternative> byLetter = q.alternativeList().stream()
                    .collect(Collectors.toMap(Alternative::letra, Function.identity()));
            items.add(new QuestionResult(q.getId(), q.getPosition(), q.getTopic(), q.getStatement(),
                    a == null ? null : a.getSelectedAnswer(), q.getCorrectAnswer(), a == null ? null : a.getCorrect(),
                    a == null || byLetter.get(a.getSelectedAnswer()) == null ? null
                            : byLetter.get(a.getSelectedAnswer()).explicacao()));
        }
        int answered = (int) items.stream().filter(i -> i.acertou() != null).count();
        int right = (int) items.stream().filter(i -> Boolean.TRUE.equals(i.acertou())).count();
        return new QuizResult(quiz.getId(), quiz.getTitle(), quiz.getLessonId(), quiz.getSubject(), quiz.getTopic(),
                quiz.getLevel(), qs.size(), answered, right, quiz.getFinishedAt() != null, items);
    }

    // ---- tela /aulas: tudo agrupado por disciplina ----

    /** Resumo de um quiz para as listas. */
    public record QuizCard(Long id, String titulo, String nivel, int perguntas, int respondidas, int acertos,
                           boolean concluido, LocalDateTime criadoEm, Long aulaId, String aula) {
        public String status() {
            return concluido ? "feito" : respondidas > 0 ? "andamento" : "novo";
        }
    }

    public record LessonCard(Lesson aula, List<QuizCard> quizzes, int feitos, int acertos, int respondidas) {
        public boolean concluida() {
            return !quizzes.isEmpty() && feitos == quizzes.size();
        }
    }

    public record SubjectGroup(String disciplina, List<LessonCard> aulas, List<QuizCard> avulsos, int pendentes,
                               int acertos, int respondidas, LocalDateTime ultimaAtividade) {
        public double percentual() {
            return respondidas == 0 ? 0 : Math.round(acertos * 1000.0 / respondidas) / 10.0;
        }
    }

    public record Overview(List<QuizCard> pendentes, List<SubjectGroup> disciplinas) {
    }

    /** Quizzes a responder (mais recentes primeiro) e aulas/quizzes avulsos por disciplina (mais mexida primeiro). */
    public Overview overview() {
        Map<Long, Lesson> lessons = all().stream().collect(Collectors.toMap(Lesson::getId, Function.identity()));
        List<QuizCard> cards = allQuizzes().stream().map(q -> {
            QuizResult r = result(q.getId());
            Lesson l = q.getLessonId() == null ? null : lessons.get(q.getLessonId());
            return new QuizCard(q.getId(), q.getTitle(), q.getLevel(), r.perguntas(), r.respondidas(), r.acertos(),
                    r.concluido(), q.getCreatedAt(), l == null ? null : l.getId(), l == null ? null : l.getTitle());
        }).toList();
        Map<Long, List<QuizCard>> byLesson = cards.stream().filter(c -> c.aulaId() != null)
                .collect(Collectors.groupingBy(QuizCard::aulaId));
        Map<Long, Quiz> quizzes = allQuizzes().stream().collect(Collectors.toMap(Quiz::getId, Function.identity()));

        Map<String, String> names = new LinkedHashMap<>();
        Map<String, List<LessonCard>> lessonsBy = new LinkedHashMap<>();
        Map<String, List<QuizCard>> looseBy = new LinkedHashMap<>();
        for (Lesson l : lessons.values()) {
            List<QuizCard> qs = new ArrayList<>(byLesson.getOrDefault(l.getId(), List.of()));
            qs.sort(Comparator.comparing(QuizCard::criadoEm));
            LessonCard card = new LessonCard(l, qs, (int) qs.stream().filter(QuizCard::concluido).count(),
                    qs.stream().mapToInt(QuizCard::acertos).sum(), qs.stream().mapToInt(QuizCard::respondidas).sum());
            lessonsBy.computeIfAbsent(subjectKey(l.getSubject(), names), k -> new ArrayList<>()).add(card);
        }
        for (QuizCard c : cards) {
            if (c.aulaId() == null) {
                looseBy.computeIfAbsent(subjectKey(quizzes.get(c.id()).getSubject(), names), k -> new ArrayList<>()).add(c);
            }
        }
        List<SubjectGroup> groups = new ArrayList<>();
        for (Map.Entry<String, String> e : names.entrySet()) {
            List<LessonCard> ls = lessonsBy.getOrDefault(e.getKey(), List.of());
            List<QuizCard> loose = looseBy.getOrDefault(e.getKey(), List.of());
            List<QuizCard> every = new ArrayList<>(loose);
            ls.forEach(l -> every.addAll(l.quizzes()));
            LocalDateTime last = every.stream().map(QuizCard::criadoEm).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(ls.stream().map(l -> l.aula().getCreatedAt())
                            .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null));
            groups.add(new SubjectGroup(e.getValue(), ls, loose, (int) every.stream().filter(c -> !c.concluido()).count(),
                    every.stream().mapToInt(QuizCard::acertos).sum(), every.stream().mapToInt(QuizCard::respondidas).sum(),
                    last));
        }
        groups.sort(Comparator.comparing(SubjectGroup::ultimaAtividade,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return new Overview(cards.stream().filter(c -> !c.concluido()).toList(), groups);
    }

    /** Chave da disciplina sem acento/caixa; guarda o primeiro nome visto para exibir. */
    private static String subjectKey(String subject, Map<String, String> names) {
        String name = blank(subject) == null ? "Sem disciplina" : subject.strip();
        String key = ScoringCalculator.normalize(name);
        names.putIfAbsent(key, name);
        return key;
    }

    // ---- desempenho (separado do das provas e simulados) ----

    public record TopicStat(String disciplina, String topico, long acertos, long respondidas, double percentual,
                            String ultimaResposta) {
    }

    /** Desempenho nos quizzes por topico (ultima resposta de cada pergunta), piores primeiro. */
    public List<TopicStat> stats() {
        return attemptRepository.statsByTopic().stream().map(r -> {
            long right = ((Number) r[2]).longValue();
            long total = ((Number) r[3]).longValue();
            return new TopicStat((String) r[0], (String) r[1], right, total,
                    total == 0 ? 0 : Math.round(right * 1000.0 / total) / 10.0, r[4] == null ? null : r[4].toString());
        }).toList();
    }

    public record Totals(long acertos, long erros, long respondidas, double percentual, long quizzes) {
    }

    /** Acertos x erros em todos os quizzes (ultima resposta de cada pergunta). */
    public Totals totals() {
        Object[] r = attemptRepository.totals().get(0);
        long right = ((Number) r[0]).longValue();
        long total = ((Number) r[1]).longValue();
        return new Totals(right, total - right, total, total == 0 ? 0 : Math.round(right * 1000.0 / total) / 10.0,
                quizRepository.count());
    }

    /** Reaproveita o nome de disciplina ja usado (sem acento/caixa), para nao dividir a mesma materia em dois grupos. */
    private String canonicalSubject(String subject) {
        String name = blank(subject);
        if (name == null) {
            return null;
        }
        String key = ScoringCalculator.normalize(name);
        return Stream.concat(all().stream().map(Lesson::getSubject), allQuizzes().stream().map(Quiz::getSubject))
                .filter(Objects::nonNull).filter(x -> ScoringCalculator.normalize(x).equals(key))
                .findFirst().orElse(name);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
