package com.pdf2questao.concurso;

import com.pdf2questao.aula.LessonService;
import com.pdf2questao.aula.Quiz;
import com.pdf2questao.aula.QuizAttempt;
import com.pdf2questao.aula.QuizQuestion;
import com.pdf2questao.scoring.ScoringCalculator;
import com.pdf2questao.scoring.ScoringCalculator.RuleSpec;
import com.pdf2questao.scoring.ScoringProfileService;
import com.pdf2questao.session.AttemptRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * O que estudar primeiro: por disciplina do edital, os pontos que o usuario ainda perde (peso x questoes x o que
 * falta de acerto). O acerto junta provas/simulados e quizzes; quiz abaixo do nivel prova vale metade, porque quiz
 * facil infla o acerto. Topico do edital sem aula nem quiz conta como 50% de acerto (ainda nao estudado).
 */
@Service
public class PriorityService {

    /** Peso de uma resposta de quiz abaixo do nivel prova (basico, intermediario, avancado ou sem nivel). */
    static final double EASY_QUIZ_WEIGHT = 0.5;
    static final double UNSTUDIED_ACCURACY = 0.5;
    private static final Set<String> STOP = Set.of("e", "de", "da", "do", "das", "dos", "a", "o");

    private final ConcursoService concursoService;
    private final ConcursoHomeService homeService;
    private final ScoringProfileService profileService;
    private final AttemptRepository attemptRepository;
    private final LessonService lessonService;
    private final Workspace workspace;

    public PriorityService(ConcursoService concursoService, ConcursoHomeService homeService,
                           ScoringProfileService profileService, AttemptRepository attemptRepository,
                           LessonService lessonService, Workspace workspace) {
        this.concursoService = concursoService;
        this.homeService = homeService;
        this.profileService = profileService;
        this.attemptRepository = attemptRepository;
        this.lessonService = lessonService;
        this.workspace = workspace;
    }

    /** Topico com acerto baixo nos quizzes (so com 2 ou mais respostas). */
    public record WeakTopic(String topico, int acertos, int respondidas) {
    }

    /**
     * @param acertoEstimado acerto usado na conta (com os topicos nao estudados a 50%); nulo sem nenhum dado.
     * @param pontosPerdidos pontos que ainda escapam na prova: o criterio da ordem.
     */
    public record SubjectPriority(String disciplina, double peso, int questoesNoEdital, double pontosEmJogo,
                                  Double acertoProvas, int respostasProvas, Double acertoQuizzes,
                                  int respostasQuizzes, Double acertoEstimado, int topicosDoEdital,
                                  int topicosSemEstudo, double pontosPerdidos, List<WeakTopic> topicosFracos,
                                  List<String> topicosNaoEstudados, String motivo) {
    }

    public record Priorities(String concurso, Long diasParaProva, List<SubjectPriority> disciplinas,
                             List<String> semDisciplinaDoEdital, String comoLer) {
    }

    private static final class Tally {
        double right;
        double total;
        int answers;

        void add(boolean correct, double weight) {
            right += correct ? weight : 0;
            total += weight;
            answers++;
        }

        Double rate() {
            return total == 0 ? null : right / total;
        }
    }

    public Priorities priorities(Long concursoId) {
        Concurso concurso = concursoService.get(concursoId);
        if (concurso.getScoringProfileId() == null) {
            throw new IllegalArgumentException("O concurso \"" + concurso.getNome() + "\" não tem perfil do edital: "
                    + "grave as disciplinas, pesos e nº de questões com salvar_perfil_edital.");
        }
        var spec = profileService.spec(concurso.getScoringProfileId()).orElseThrow();
        List<RuleSpec> rules = spec.groups().stream().flatMap(g -> g.rules().stream()).toList();
        return workspace.as(concursoId, () -> {
            Map<String, Tally> exams = new LinkedHashMap<>();
            Map<String, Tally> quizzes = new LinkedHashMap<>();
            Map<String, Map<String, int[]>> topics = new LinkedHashMap<>();
            List<String> unmatched = new ArrayList<>();

            for (Object[] r : attemptRepository.statsBySubject(concursoId)) {
                RuleSpec rule = match((String) r[0], rules);
                if (rule == null) {
                    unmatched.add((String) r[0] + " (provas)");
                    continue;
                }
                Tally t = exams.computeIfAbsent(rule.subject(), k -> new Tally());
                long right = ((Number) r[1]).longValue();
                long total = ((Number) r[2]).longValue();
                t.right += right;
                t.total += total;
                t.answers += (int) total;
            }
            for (Quiz quiz : lessonService.visibleQuizzes()) {
                double weight = "prova".equals(quiz.getLevel()) || quiz.isEmbedded() ? 1 : EASY_QUIZ_WEIGHT;
                Map<Long, QuizAttempt> answers = lessonService.latest(quiz.getId());
                for (QuizQuestion q : lessonService.questions(quiz.getId())) {
                    QuizAttempt a = answers.get(q.getId());
                    if (a == null) {
                        continue;
                    }
                    String subject = q.getSubject() != null ? q.getSubject() : quiz.getSubject();
                    RuleSpec rule = match(subject, rules);
                    if (rule == null) {
                        if (subject != null && !unmatched.contains(subject + " (quizzes)")) {
                            unmatched.add(subject + " (quizzes)");
                        }
                        continue;
                    }
                    boolean right = Boolean.TRUE.equals(a.getCorrect());
                    quizzes.computeIfAbsent(rule.subject(), k -> new Tally()).add(right, weight);
                    if (q.getTopic() != null) {
                        int[] c = topics.computeIfAbsent(rule.subject(), k -> new LinkedHashMap<>())
                                .computeIfAbsent(q.getTopic(), k -> new int[2]);
                        c[0] += right ? 1 : 0;
                        c[1]++;
                    }
                }
            }
            Map<String, ConcursoHomeService.Coverage> coverage = new LinkedHashMap<>();
            homeService.coverage(concurso).forEach(c -> {
                RuleSpec rule = match(c.disciplina(), rules);
                coverage.put(rule == null ? c.disciplina() : rule.subject(), c);
            });

            double penalty = spec.wrongPenalty() == null ? 0 : spec.wrongPenalty();
            List<SubjectPriority> out = new ArrayList<>();
            for (RuleSpec rule : rules) {
                int count = rule.questionCount() == null ? 0 : rule.questionCount();
                double max = rule.weight() * count;
                Tally e = exams.getOrDefault(rule.subject(), new Tally());
                Tally q = quizzes.getOrDefault(rule.subject(), new Tally());
                double total = e.total + q.total;
                Double studied = total == 0 ? null : (e.right + q.right) / total;
                var cov = coverage.get(rule.subject());
                int edital = cov == null ? 0 : cov.topicos();
                int missing = cov == null ? 0 : cov.faltam().size();
                double m = edital == 0 ? 0 : (double) missing / edital;
                Double estimated = studied == null ? (edital == 0 ? null : UNSTUDIED_ACCURACY)
                        : studied * (1 - m) + UNSTUDIED_ACCURACY * m;
                double acc = estimated == null ? UNSTUDIED_ACCURACY : estimated;
                // Cebraspe: cada erro desconta (acerto liquido); sem desconto, o que falta de acerto.
                double kept = Math.max(0, acc - (1 - acc) * penalty);
                double lost = max * (1 - kept);
                List<WeakTopic> weak = topics.getOrDefault(rule.subject(), Map.of()).entrySet().stream()
                        .filter(t -> t.getValue()[1] >= 2 && t.getValue()[0] * 10 < t.getValue()[1] * 7)
                        .sorted(Comparator.comparingDouble(t -> (double) t.getValue()[0] / t.getValue()[1]))
                        .limit(3).map(t -> new WeakTopic(t.getKey(), t.getValue()[0], t.getValue()[1])).toList();
                out.add(new SubjectPriority(rule.subject(), rule.weight(), count, round(max), pct(e.rate()), e.answers,
                        pct(q.rate()), q.answers, pct(estimated), edital, missing, round(lost), weak,
                        cov == null ? List.of() : cov.faltam().stream().limit(5).toList(),
                        reason(max, estimated, e, q, edital, missing, weak)));
            }
            out.sort(Comparator.comparingDouble(SubjectPriority::pontosPerdidos).reversed());
            return new Priorities(concurso.getNome(), concurso.diasParaProva(LocalDate.now()), out, unmatched,
                    "Ordem = pontos que ainda escapam na prova (peso x questões x o que falta de acerto"
                            + (penalty > 0 ? ", com o desconto do erro" : "") + "). Quiz abaixo do nível prova vale "
                            + "metade no acerto; tópico do edital sem aula nem quiz conta como 50%. É sugestão: o "
                            + "cronograma não muda sozinho.");
        });
    }

    private static String reason(double max, Double estimated, Tally exams, Tally quizzes, int edital, int missing,
                                 List<WeakTopic> weak) {
        List<String> parts = new ArrayList<>();
        parts.add(fmt(max) + " pts em jogo");
        if (estimated == null) {
            parts.add("sem nenhum dado de acerto");
        } else {
            parts.add("acerto estimado " + Math.round(estimated * 100) + "%");
        }
        if (exams.answers == 0) {
            parts.add("nunca treinou em prova/simulado");
        }
        if (quizzes.answers > 0 && exams.answers == 0 && quizzes.total < quizzes.answers) {
            parts.add("acerto só de quizzes fáceis");
        }
        if (missing > 0) {
            parts.add(missing + " de " + edital + " tópicos do edital sem estudo");
        }
        if (!weak.isEmpty()) {
            parts.add("pior tópico: " + weak.get(0).topico());
        }
        return String.join("; ", parts);
    }

    /**
     * A disciplina do edital que corresponde a um nome livre ("AFO", "Dir. Adm.", "Contabilidade"): nome ou apelido
     * igual, sigla das palavras, ou cada palavra do nome sendo inicio de uma palavra da disciplina.
     */
    static RuleSpec match(String name, List<RuleSpec> rules) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = ScoringCalculator.normalize(name);
        for (RuleSpec r : rules) {
            if (ScoringCalculator.normalize(r.subject()).equals(key)
                    || r.aliases().stream().anyMatch(a -> ScoringCalculator.normalize(a).equals(key))) {
                return r;
            }
        }
        List<String> words = words(key);
        RuleSpec found = null;
        for (RuleSpec r : rules) {
            List<String> names = new ArrayList<>(List.of(r.subject()));
            names.addAll(r.aliases());
            for (String n : names) {
                List<String> target = words(ScoringCalculator.normalize(n));
                String initials = target.stream().map(w -> w.substring(0, 1)).reduce("", String::concat);
                boolean prefixes = !words.isEmpty() && words.stream()
                        .allMatch(w -> target.stream().anyMatch(t -> t.startsWith(w)));
                if (initials.equals(key.replaceAll("[^a-z]", "")) && key.length() >= 2 || prefixes) {
                    if (found != null && found != r) {
                        return null;   // ambiguo: melhor nao chutar
                    }
                    found = r;
                }
            }
        }
        return found;
    }

    private static List<String> words(String normalized) {
        return java.util.Arrays.stream(normalized.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(w -> !w.isBlank() && !STOP.contains(w)).toList();
    }

    private static Double pct(Double rate) {
        return rate == null ? null : Math.round(rate * 1000) / 10.0;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(round(v));
    }
}
