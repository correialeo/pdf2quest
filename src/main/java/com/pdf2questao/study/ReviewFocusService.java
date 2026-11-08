package com.pdf2questao.study;

import com.pdf2questao.scoring.ScoringCalculator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Foco da revisao do dia: em vez de juntar todos os assuntos vencidos, escolhe poucas disciplinas (as mais fracas) e
 * traz, dentro delas, os conceitos que o usuario mais erra. A fraqueza vem dos quizzes (1a tentativa de cada questao,
 * na janela de dias configurada) e das autoavaliacoes dos flashcards; simulados e provas importadas nao entram.
 * <p>
 * Prioridade de uma disciplina = taxa de erro suavizada x atraso: o que se domina espera, mas nao para sempre (cada
 * semana de atraso soma uma vez a taxa, ate 4 semanas). Vale para todos os concursos: a medida e por disciplina.
 */
@Service
public class ReviewFocusService {

    /** Disciplina de quiz/aula que nao diz a materia (revisao misturada, treino da banca): fora da medida. */
    private static final Pattern GENERIC = Pattern.compile(
            "^(revisao.*|mist[oa]s?|fcc.*|cebraspe.*|simulado.*|discursiva.*|p[123]|sem disciplina)$");
    /** Erro "a priori" de quem nao tem historico: 1 erro em 4 (25%). */
    private static final double PRIOR_WRONG = 1, PRIOR_N = 4;
    /** Peso de tarefa que nao e materia (revisao semanal, treino da banca): metade de uma materia sem historico. */
    private static final double GENERIC_WEIGHT = 0.4;
    static final int MAX_WEAK_CONCEPTS = 5;

    private final JdbcTemplate jdbc;
    private final int subjectsPerDay;
    private final int subjectsSunday;
    private final int itemsPerSubject;
    private final int windowDays;

    public ReviewFocusService(JdbcTemplate jdbc,
                              @Value("${pdf2questao.revisao.foco.disciplinas:2}") int subjectsPerDay,
                              @Value("${pdf2questao.revisao.foco.disciplinas-domingo:3}") int subjectsSunday,
                              @Value("${pdf2questao.revisao.foco.assuntos-por-disciplina:6}") int itemsPerSubject,
                              @Value("${pdf2questao.revisao.foco.janela-dias:60}") int windowDays) {
        this.jdbc = jdbc;
        this.subjectsPerDay = Math.max(1, subjectsPerDay);
        this.subjectsSunday = Math.max(1, subjectsSunday);
        this.itemsPerSubject = Math.max(1, itemsPerSubject);
        this.windowDays = Math.max(1, windowDays);
    }

    public record WeakConcept(Long aulaId, String conceito, int erros, int vezes) {
    }

    /** Disciplina em foco: acerto nos quizzes (nulo = sem historico), conceitos mais errados e os assuntos do dia. */
    public record FocusSubject(String disciplina, Integer acertoQuiz, int respostasQuiz,
                               List<WeakConcept> conceitosFracos, List<StudyTask> assuntos) {
    }

    /** Disciplina com assunto vencido que ficou para os proximos dias. */
    public record Waiting(String disciplina, int assuntos, LocalDate vencidoDesde, Integer acertoQuiz) {
    }

    public record Focus(List<FocusSubject> foco, List<Waiting> aguardando) {

        public List<StudyTask> items() {
            return foco.stream().flatMap(f -> f.assuntos().stream()).toList();
        }

        public String label() {
            return String.join(" + ", foco.stream().map(FocusSubject::disciplina).toList());
        }
    }

    /** Escolhe o foco do dia entre os assuntos vencidos. */
    public Focus choose(List<StudyTask> due, LocalDate today) {
        Stats stats = stats(today);
        Map<String, List<StudyTask>> groups = group(due);
        int limit = today.getDayOfWeek() == DayOfWeek.SUNDAY ? subjectsSunday : subjectsPerDay;
        List<String> ranked = groups.keySet().stream()
                .sorted(Comparator.comparingDouble((String k) -> -priority(k, groups.get(k), stats, today))
                        .thenComparing(k -> oldest(groups.get(k))))
                .toList();
        List<StudyTask> chosen = new ArrayList<>();
        for (String k : ranked.subList(0, Math.min(limit, ranked.size()))) {
            groups.get(k).stream().sorted(Comparator.comparing(StudyTask::getNextReviewDate).thenComparing(StudyTask::getId))
                    .limit(itemsPerSubject).forEach(chosen::add);
        }
        Set<Long> ids = new LinkedHashSet<>(chosen.stream().map(StudyTask::getId).toList());
        return describe(chosen, due.stream().filter(t -> !ids.contains(t.getId())).toList(), stats);
    }

    /** Monta o foco de assuntos ja escolhidos (a tarefa do dia), com a medida de fraqueza atual. */
    public Focus describe(List<StudyTask> chosen, List<StudyTask> others, LocalDate today) {
        return describe(chosen, others, stats(today));
    }

    private Focus describe(List<StudyTask> chosen, List<StudyTask> others, Stats stats) {
        List<FocusSubject> foco = new ArrayList<>();
        group(chosen).forEach((k, tasks) -> {
            Count c = stats.subject(k);
            foco.add(new FocusSubject(display(tasks), c == null ? null : c.accuracy(), c == null ? 0 : c.n,
                    stats.weakConcepts(k), tasks));
        });
        List<Waiting> waiting = new ArrayList<>();
        group(others).forEach((k, tasks) -> {
            Count c = stats.subject(k);
            waiting.add(new Waiting(display(tasks), tasks.size(), oldest(tasks), c == null ? null : c.accuracy()));
        });
        waiting.sort(Comparator.comparing(Waiting::vencidoDesde));
        return new Focus(foco, waiting);
    }

    private double priority(String key, List<StudyTask> tasks, Stats stats, LocalDate today) {
        Count c = stats.subject(key);
        double wrong = c == null ? 0 : c.wrong;
        double n = c == null ? 0 : c.n;
        double errorRate = (wrong + PRIOR_WRONG) / (n + PRIOR_N);
        if (generic(key)) {
            // "Revisao semanal", "FCC", "Discursiva"...: nao sao materia nem tem medida; ficam atras das materias e
            // so sobem com o atraso.
            errorRate *= GENERIC_WEIGHT;
        }
        long late = Math.max(0, ChronoUnit.DAYS.between(oldest(tasks), today));
        return errorRate * (1 + Math.min(late, 28) / 7.0);
    }

    private static LocalDate oldest(List<StudyTask> tasks) {
        return tasks.stream().map(StudyTask::getNextReviewDate).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(LocalDate.MAX);
    }

    private static String display(List<StudyTask> tasks) {
        return tasks.stream().map(StudyTask::getSubject).filter(s -> s != null && !s.isBlank()).findFirst()
                .orElse("Sem disciplina");
    }

    private static Map<String, List<StudyTask>> group(List<StudyTask> tasks) {
        Map<String, List<StudyTask>> out = new LinkedHashMap<>();
        tasks.forEach(t -> out.computeIfAbsent(key(t.getSubject()), k -> new ArrayList<>()).add(t));
        return out;
    }

    /**
     * Chave da disciplina: sem acento/caixa e com as abreviacoes comuns por extenso, para "Dir. Financeiro" e
     * "Direito Financeiro" (ou "Mat. Fin." e "Matematica Financeira") contarem juntos.
     */
    static String key(String subject) {
        String s = ScoringCalculator.normalize(subject == null || subject.isBlank() ? "Sem disciplina" : subject);
        s = (" " + s.replaceAll("[–—-]", " ") + " ")
                .replaceAll("\\sdir\\.?\\s", " direito ")
                .replaceAll("\\sadm\\.?\\s", " administrativo ")
                .replaceAll("\\smat\\.?\\s", " matematica ")
                .replaceAll("\\sfin\\.?\\s", " financeira ")
                .replaceAll("\\scontab\\.?\\s", " contabilidade ")
                .replaceAll("\\sconst\\.?\\s", " constitucional ");
        return s.replaceAll("[.,;:/()]", " ").replaceAll("\\s+", " ").strip();
    }

    static boolean generic(String key) {
        return GENERIC.matcher(key).matches();
    }

    /** A primeira disciplina que diz a materia (nao "Revisao", "Misto"...); nenhuma = a primeira informada. */
    static String firstSpecific(String... subjects) {
        String first = null;
        for (String s : subjects) {
            if (s == null || s.isBlank()) {
                continue;
            }
            if (!generic(key(s))) {
                return s;
            }
            first = first == null ? s : first;
        }
        return first;
    }

    // ---------- medida de fraqueza ----------

    private static final class Count {
        int n;
        double wrong;

        Integer accuracy() {
            return n == 0 ? null : (int) Math.round(100.0 * (n - wrong) / n);
        }
    }

    private static final class ConceptCount {
        final Long lessonId;
        final String concept;
        double wrong;
        int n;

        ConceptCount(Long lessonId, String concept) {
            this.lessonId = lessonId;
            this.concept = concept;
        }
    }

    private final class Stats {
        final Map<String, Count> bySubject = new HashMap<>();
        final Map<String, Map<String, ConceptCount>> conceptsBySubject = new HashMap<>();

        /** Contagem da disciplina; sem a exata, a de nome que contem (ou esta contido em) o pedido com mais respostas. */
        Count subject(String key) {
            Count exact = bySubject.get(key);
            if (exact != null || generic(key)) {
                return exact;
            }
            return bySubject.entrySet().stream()
                    .filter(e -> e.getKey().contains(key) || key.contains(e.getKey()))
                    .map(Map.Entry::getValue).max(Comparator.comparingInt(c -> c.n)).orElse(null);
        }

        List<WeakConcept> weakConcepts(String key) {
            Map<String, ConceptCount> found = new LinkedHashMap<>();
            conceptsBySubject.forEach((k, m) -> {
                if (k.equals(key) || (!generic(key) && (k.contains(key) || key.contains(k)))) {
                    m.forEach(found::putIfAbsent);
                }
            });
            return found.values().stream().filter(c -> c.wrong > 0)
                    .sorted(Comparator.comparingDouble((ConceptCount c) -> -c.wrong).thenComparingInt(c -> c.n))
                    .limit(MAX_WEAK_CONCEPTS)
                    .map(c -> new WeakConcept(c.lessonId, c.concept, (int) Math.ceil(c.wrong), c.n)).toList();
        }

        void add(String subject, Long lessonId, String concepts, double wrong) {
            String k = key(subject);
            if (!generic(k)) {
                Count c = bySubject.computeIfAbsent(k, x -> new Count());
                c.n++;
                c.wrong += wrong;
            }
            if (concepts == null || concepts.isBlank() || lessonId == null || generic(k)) {
                return;
            }
            for (String concept : concepts.split(",")) {
                String id = concept.strip();
                if (id.isEmpty()) {
                    continue;
                }
                ConceptCount cc = conceptsBySubject.computeIfAbsent(k, x -> new LinkedHashMap<>())
                        .computeIfAbsent(lessonId + "#" + id, x -> new ConceptCount(lessonId, id));
                cc.n++;
                cc.wrong += wrong;
            }
        }
    }

    private Stats stats(LocalDate today) {
        long since = today.minusDays(windowDays).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        Stats stats = new Stats();
        // 1a tentativa de cada questao de quiz: refazer depois nao apaga o erro. Vale a primeira disciplina que diz a
        // materia: a da questao, a da aula ou a do quiz (quiz de revisao costuma gravar so "Revisao" no quiz).
        jdbc.query("""
                SELECT qq.subject AS q_subject, l.subject AS l_subject, q.subject AS quiz_subject, qq.lesson_id,
                       qq.concepts, qa.correct
                FROM quiz_attempt qa
                JOIN quiz_question qq ON qq.id = qa.quiz_question_id
                JOIN quiz q ON q.id = qq.quiz_id
                LEFT JOIN lesson l ON l.id = qq.lesson_id
                WHERE qa.id IN (SELECT MIN(id) FROM quiz_attempt GROUP BY quiz_question_id)
                  AND qa.answered_at >= ?
                """, rs -> {
            String subject = firstSpecific(rs.getString("q_subject"), rs.getString("l_subject"),
                    rs.getString("quiz_subject"));
            Object lesson = rs.getObject("lesson_id");
            stats.add(subject, lesson == null ? null : ((Number) lesson).longValue(), rs.getString("concepts"),
                    rs.getBoolean("correct") ? 0 : 1);
        }, since);
        // Flashcards: "errei" conta como erro, "dificil" como meio erro, "facil" como acerto (so a ultima de cada um).
        jdbc.query("""
                SELECT l.subject, f.lesson_id, f.concept, r.rating
                FROM flashcard_rating r
                JOIN flashcard f ON f.id = r.flashcard_id
                JOIN lesson l ON l.id = f.lesson_id
                WHERE r.id IN (SELECT MAX(id) FROM flashcard_rating GROUP BY flashcard_id) AND r.rated_at >= ?
                """, rs -> {
            String rating = rs.getString("rating");
            double wrong = "errei".equals(rating) ? 1 : "dificil".equals(rating) ? 0.5 : 0;
            stats.add(rs.getString("subject"), rs.getLong("lesson_id"), rs.getString("concept"), wrong);
        }, since);
        return stats;
    }
}
