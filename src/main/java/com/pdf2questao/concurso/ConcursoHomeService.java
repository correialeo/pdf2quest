package com.pdf2questao.concurso;

import com.pdf2questao.aula.Lesson;
import com.pdf2questao.aula.LessonService;
import com.pdf2questao.aula.Quiz;
import com.pdf2questao.dashboard.DashboardService;
import com.pdf2questao.dashboard.SubjectStat;
import com.pdf2questao.scoring.ScoringCalculator;
import com.pdf2questao.scoring.ScoringCalculator.ScoreResult;
import com.pdf2questao.session.StudySession;
import com.pdf2questao.session.StudySessionRepository;
import com.pdf2questao.session.StudySessionService;
import com.pdf2questao.simulado.review.EditalTopic;
import com.pdf2questao.simulado.review.EditalTopicRepository;
import com.pdf2questao.study.StudyTask;
import com.pdf2questao.study.StudyTaskService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/** Home do concurso: o que fazer hoje, como esta indo nos simulados/quizzes e quanto do edital ja foi estudado. */
@Service
public class ConcursoHomeService {

    private final Workspace workspace;
    private final ConcursoService concursoService;
    private final StudyTaskService taskService;
    private final DashboardService dashboardService;
    private final LessonService lessonService;
    private final StudySessionRepository sessionRepository;
    private final StudySessionService sessionService;
    private final EditalTopicRepository topicRepository;

    public ConcursoHomeService(Workspace workspace, ConcursoService concursoService, StudyTaskService taskService,
                               DashboardService dashboardService, LessonService lessonService,
                               StudySessionRepository sessionRepository, StudySessionService sessionService,
                               EditalTopicRepository topicRepository) {
        this.workspace = workspace;
        this.concursoService = concursoService;
        this.taskService = taskService;
        this.dashboardService = dashboardService;
        this.lessonService = lessonService;
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
        this.topicRepository = topicRepository;
    }

    /** Nota de um simulado do concurso, para a evolucao. */
    public record SimuladoPoint(Long sessaoId, String nome, LocalDate data, Double nota, double maximo, Double minimo,
                                Boolean passaria) {
    }

    /** Topicos do edital de uma disciplina e quantos ja tem aula ou quiz. */
    public record Coverage(String disciplina, int topicos, int estudados, List<String> faltam) {
        public int percentual() {
            return topicos == 0 ? 0 : Math.round(estudados * 100f / topicos);
        }
    }

    public record Home(Concurso concurso, Long diasParaProva, List<StudyTask> hoje, StudyTaskService.WeekSummary semana,
                       List<SubjectStat> disciplinas, LessonService.Totals quizzes, long cadernoDeErros,
                       List<SimuladoPoint> simulados, List<Coverage> edital, List<Cronograma> cronogramas,
                       Map<Long, long[]> tarefasPorCronograma) {

        public int editalTopicos() {
            return edital.stream().mapToInt(Coverage::topicos).sum();
        }

        public int editalEstudados() {
            return edital.stream().mapToInt(Coverage::estudados).sum();
        }

        /** Topicos do edital ainda sem aula/quiz por dia ate a prova (nulo sem data ou sem edital). */
        public Double topicosPorDia() {
            int faltam = editalTopicos() - editalEstudados();
            return diasParaProva == null || diasParaProva <= 0 || editalTopicos() == 0 ? null
                    : Math.round(faltam * 10.0 / diasParaProva) / 10.0;
        }

        /** Grafico da evolucao: so com 2 ou mais simulados com nota. */
        public Chart grafico() {
            return Chart.of(simulados);
        }
    }

    /** Linha das notas (SVG 600x160) com a linha do minimo; coordenadas ja calculadas para o template. */
    public record Chart(String linha, List<double[]> pontos, Double minimoY, double maximo, Double minimo) {
        static final double W = 600;
        static final double H = 160;
        static final double PAD = 12;

        static Chart of(List<SimuladoPoint> sims) {
            List<SimuladoPoint> scored = sims.stream().filter(s -> s.nota() != null).toList();
            if (scored.size() < 2) {
                return null;
            }
            double max = scored.stream().mapToDouble(SimuladoPoint::maximo).max().orElse(1);
            Double min = scored.get(scored.size() - 1).minimo();
            List<double[]> pts = new ArrayList<>();
            for (int i = 0; i < scored.size(); i++) {
                double x = PAD + i * (W - 2 * PAD) / (scored.size() - 1);
                pts.add(new double[]{Math.round(x), Math.round(y(scored.get(i).nota(), max)), scored.get(i).nota()});
            }
            String line = pts.stream().map(p -> (long) p[0] + "," + (long) p[1])
                    .collect(java.util.stream.Collectors.joining(" "));
            return new Chart(line, pts, min == null ? null : (double) Math.round(y(min, max)), max, min);
        }

        private static double y(double value, double max) {
            return H - PAD - (Math.max(0, Math.min(value, max)) / max) * (H - 2 * PAD);
        }
    }

    public Home home(Long concursoId) {
        Concurso concurso = concursoService.get(concursoId);
        return workspace.as(concursoId, () -> {
            LocalDate today = LocalDate.now();
            List<StudyTask> due = taskService.dueBy(today).stream().filter(t -> !t.isReview()).toList();
            List<SubjectStat> subjects = dashboardService.subjectStats().stream()
                    .sorted(Comparator.comparingDouble(SubjectStat::percent)).toList();
            return new Home(concurso, concurso.diasParaProva(today), due, taskService.week(today), subjects,
                    lessonService.totals(), dashboardService.errorNotebookCount(), simulados(),
                    coverage(concurso), concursoService.cronogramas(), concursoService.taskCounts());
        });
    }

    /** Simulados finalizados do concurso com nota do perfil, do mais antigo ao mais recente. */
    public List<SimuladoPoint> simulados() {
        List<SimuladoPoint> out = new ArrayList<>();
        for (StudySession s : sessionRepository.findByFinishedAtIsNotNullOrderByStartedAtDesc()) {
            if (s.getScoreReport() == null || !workspace.matches(s.getConcursoId())) {
                continue;
            }
            ScoreResult score = sessionService.scoreResult(s);
            if (score == null) {
                continue;
            }
            out.add(new SimuladoPoint(s.getId(), s.getBlockName(), s.getFinishedAt().toLocalDate(),
                    score.projectedTotal() == null ? null : Math.round(score.projectedTotal() * 10) / 10.0,
                    score.maxTotal(), score.minTotal(), score.projectedPassed()));
        }
        java.util.Collections.reverse(out);
        return out;
    }

    /** Topicos do edital (perfil do concurso) com aula ou quiz do concurso sobre eles. */
    public List<Coverage> coverage(Concurso concurso) {
        if (concurso.getScoringProfileId() == null) {
            return List.of();
        }
        List<String> studied = Stream.concat(
                        lessonService.visible().stream().map(Lesson::getEditalTopic),
                        lessonService.visibleQuizzes().stream().map(Quiz::getTopic))
                .filter(Objects::nonNull).map(ScoringCalculator::normalize).filter(t -> t.length() >= 4).toList();
        Map<String, List<EditalTopic>> bySubject = new LinkedHashMap<>();
        topicRepository.findByScoringProfileIdOrderByPosition(concurso.getScoringProfileId())
                .forEach(t -> bySubject.computeIfAbsent(t.getSubject(), k -> new ArrayList<>()).add(t));
        List<Coverage> out = new ArrayList<>();
        bySubject.forEach((subject, topics) -> {
            List<String> missing = new ArrayList<>();
            int done = 0;
            for (EditalTopic t : topics) {
                String topic = ScoringCalculator.normalize(t.getTopic());
                if (studied.stream().anyMatch(s -> topic.contains(s) || s.contains(topic))) {
                    done++;
                } else {
                    missing.add(t.getTopic());
                }
            }
            out.add(new Coverage(subject, topics.size(), done, missing));
        });
        return out;
    }
}
