package com.pdf2questao.aula;

import com.pdf2questao.aula.QuizQuestion.Alternative;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Aulas e quizzes: criar (pelo MCP), responder na tela e resumir o resultado para a proxima sessao. */
@Service
public class LessonService {

    private final LessonRepository lessonRepository;
    private final QuizQuestionRepository questionRepository;
    private final QuizAttemptRepository attemptRepository;

    public LessonService(LessonRepository lessonRepository, QuizQuestionRepository questionRepository,
                         QuizAttemptRepository attemptRepository) {
        this.lessonRepository = lessonRepository;
        this.questionRepository = questionRepository;
        this.attemptRepository = attemptRepository;
    }

    public List<Lesson> all() {
        return lessonRepository.findAllByOrderByCreatedAtDesc();
    }

    public Lesson find(Long id) {
        return lessonRepository.findById(id).orElseThrow();
    }

    public List<QuizQuestion> questions(Long lessonId) {
        return questionRepository.findByLessonIdOrderByPosition(lessonId);
    }

    @Transactional
    public Lesson create(String title, String subject, String editalTopic, String content, Long taskId) {
        if (title == null || title.isBlank() || content == null || content.isBlank()) {
            throw new IllegalArgumentException("A aula precisa de título e conteúdo.");
        }
        Lesson lesson = new Lesson();
        lesson.setTitle(title.strip());
        lesson.setSubject(blank(subject));
        lesson.setEditalTopic(blank(editalTopic));
        lesson.setContent(content.strip());
        lesson.setTaskId(taskId);
        return lessonRepository.save(lesson);
    }

    public record NewQuestion(String statement, String topic, List<String> texts, List<String> explanations,
                              String correct) {
    }

    /** Acrescenta perguntas ao quiz da aula. Toda alternativa precisa de explicacao (por que certa/errada). */
    @Transactional
    public List<QuizQuestion> addQuestions(Long lessonId, List<NewQuestion> questions) {
        Lesson lesson = find(lessonId);
        int position = questions(lessonId).size();
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
            q.setLessonId(lessonId);
            q.setPosition(++position);
            q.setSubject(lesson.getSubject());
            q.setTopic(blank(n.topic()) != null ? n.topic().strip() : lesson.getEditalTopic());
            q.setStatement(n.statement().strip());
            q.setAlternativeList(alternatives);
            q.setCorrectAnswer(correct);
            saved.add(questionRepository.save(q));
        }
        return saved;
    }

    public record AnswerResult(boolean correct, String correctAnswer, boolean finished, int correctCount, int total) {
    }

    /** Responde uma pergunta na tela. Quando todas tem resposta, a aula fica concluida. */
    @Transactional
    public AnswerResult answer(Long questionId, String selected) {
        QuizQuestion q = questionRepository.findById(questionId).orElseThrow();
        QuizAttempt attempt = new QuizAttempt();
        attempt.setQuizQuestionId(questionId);
        attempt.setSelectedAnswer(selected == null ? null : selected.strip().toUpperCase(Locale.ROOT));
        attempt.setCorrect(q.getCorrectAnswer().equals(attempt.getSelectedAnswer()));
        attemptRepository.save(attempt);
        Map<Long, QuizAttempt> latest = latest(q.getLessonId());
        List<QuizQuestion> all = questions(q.getLessonId());
        boolean finished = all.stream().allMatch(x -> latest.containsKey(x.getId()));
        if (finished) {
            Lesson lesson = find(q.getLessonId());
            lesson.setFinishedAt(LocalDateTime.now());
            lessonRepository.save(lesson);
        }
        int right = (int) latest.values().stream().filter(a -> Boolean.TRUE.equals(a.getCorrect())).count();
        return new AnswerResult(attempt.getCorrect(), q.getCorrectAnswer(), finished, right, all.size());
    }

    /** Ultima resposta de cada pergunta da aula (desde a ultima vez que o quiz foi recomecado). */
    public Map<Long, QuizAttempt> latest(Long lessonId) {
        List<QuizQuestion> qs = questions(lessonId);
        Lesson lesson = find(lessonId);
        LocalDateTime since = lesson.getRestartedAt();
        Map<Long, QuizAttempt> out = new LinkedHashMap<>();
        for (QuizAttempt a : attemptRepository.findByQuizQuestionIdInOrderByAnsweredAt(
                qs.stream().map(QuizQuestion::getId).toList())) {
            if (since == null || !a.getAnsweredAt().isBefore(since)) {
                out.put(a.getQuizQuestionId(), a);
            }
        }
        return out;
    }

    /**
     * Refazer o quiz: as respostas antigas ficam no historico (e no desempenho, ate serem substituidas), mas a tela
     * volta a ficar em branco.
     */
    @Transactional
    public void restart(Long lessonId) {
        Lesson lesson = find(lessonId);
        lesson.setFinishedAt(null);
        lesson.setRestartedAt(LocalDateTime.now());
        lessonRepository.save(lesson);
    }

    public record QuestionResult(int numero, String topico, String enunciado, String suaResposta, String correta,
                                 Boolean acertou, String explicacaoDaSuaResposta) {
    }

    public record LessonResult(Long aulaId, String titulo, String disciplina, String topicoEdital, int perguntas,
                               int respondidas, int acertos, boolean concluida, List<QuestionResult> questoes) {
    }

    public LessonResult result(Long lessonId) {
        Lesson lesson = find(lessonId);
        List<QuizQuestion> qs = questions(lessonId);
        Map<Long, QuizAttempt> latest = latest(lessonId);
        List<QuestionResult> items = new ArrayList<>();
        for (QuizQuestion q : qs) {
            QuizAttempt a = latest.get(q.getId());
            Map<String, Alternative> byLetter = q.alternativeList().stream()
                    .collect(Collectors.toMap(Alternative::letra, Function.identity()));
            items.add(new QuestionResult(q.getPosition(), q.getTopic(), q.getStatement(),
                    a == null ? null : a.getSelectedAnswer(), q.getCorrectAnswer(), a == null ? null : a.getCorrect(),
                    a == null || byLetter.get(a.getSelectedAnswer()) == null ? null
                            : byLetter.get(a.getSelectedAnswer()).explicacao()));
        }
        int answered = (int) items.stream().filter(i -> i.acertou() != null).count();
        int right = (int) items.stream().filter(i -> Boolean.TRUE.equals(i.acertou())).count();
        return new LessonResult(lesson.getId(), lesson.getTitle(), lesson.getSubject(), lesson.getEditalTopic(),
                qs.size(), answered, right, lesson.getFinishedAt() != null, items);
    }

    public record TopicStat(String disciplina, String topico, long acertos, long respondidas, double percentual,
                            String ultimaResposta) {
    }

    /** Desempenho nos quizzes por topico (ultima resposta de cada pergunta), piores primeiro. Separado do simulado. */
    public List<TopicStat> stats() {
        return attemptRepository.statsByTopic().stream().map(r -> {
            long right = ((Number) r[2]).longValue();
            long total = ((Number) r[3]).longValue();
            return new TopicStat((String) r[0], (String) r[1], right, total,
                    total == 0 ? 0 : Math.round(right * 1000.0 / total) / 10.0, r[4] == null ? null : r[4].toString());
        }).toList();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
