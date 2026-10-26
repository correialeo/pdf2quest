package com.pdf2questao.simulado;

import com.pdf2questao.block.BlockService;
import com.pdf2questao.block.StudyBlock;
import com.pdf2questao.importer.ImportJob;
import com.pdf2questao.importer.ImportJobRepository;
import com.pdf2questao.importer.structured.ImportOutcome;
import com.pdf2questao.importer.structured.QuestionDraft;
import com.pdf2questao.importer.structured.StructuredImportService;
import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionRepository;
import com.pdf2questao.scoring.ScoringCalculator;
import com.pdf2questao.scoring.ScoringCalculator.GroupSpec;
import com.pdf2questao.scoring.ScoringCalculator.ProfileSpec;
import com.pdf2questao.scoring.ScoringCalculator.RuleSpec;
import com.pdf2questao.scoring.ScoringProfile;
import com.pdf2questao.scoring.ScoringProfileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulado novo escrito pelo Claude nas medidas do edital: rascunho ligado a uma prova do perfil de pontuacao
 * (ex.: P1), questoes em lotes conferidas contra as disciplinas e quantidades do perfil, e ao finalizar um bloco
 * com o perfil e o tempo limite. Varios rascunhos podem virar um bloco so ("dia de prova": P1 + P2).
 */
@Service
public class GeneratedExamService {

    /** Marca nas questoes (campo fonte), para nao servirem de exemplo de banca real. */
    public static final String SOURCE = "Simulado do Claude";
    /** Profundidade exigida antes de escrever: perfil da banca detalhado e ao menos duas provas reais analisadas. */
    static final int MIN_STYLE_CHARS = 600;
    static final int MIN_SOURCES = 2;
    /** Questoes por chamada: forca escrever (e revisar) poucas de cada vez. */
    public static final int MAX_BATCH = 5;

    private final GeneratedExamRepository examRepository;
    private final ImportJobRepository importJobRepository;
    private final QuestionRepository questionRepository;
    private final StructuredImportService importService;
    private final ScoringProfileService profileService;
    private final BlockService blockService;
    private final com.pdf2questao.session.StudySessionService sessionService;
    private final com.pdf2questao.simulado.review.QuestionReviewService reviewService;

    private final com.pdf2questao.concurso.ConcursoService concursoService;

    public GeneratedExamService(GeneratedExamRepository examRepository, ImportJobRepository importJobRepository,
                                QuestionRepository questionRepository, StructuredImportService importService,
                                ScoringProfileService profileService, BlockService blockService,
                                com.pdf2questao.session.StudySessionService sessionService,
                                com.pdf2questao.simulado.review.QuestionReviewService reviewService,
                                com.pdf2questao.concurso.ConcursoService concursoService) {
        this.concursoService = concursoService;
        this.sessionService = sessionService;
        this.reviewService = reviewService;
        this.examRepository = examRepository;
        this.importJobRepository = importJobRepository;
        this.questionRepository = questionRepository;
        this.importService = importService;
        this.profileService = profileService;
        this.blockService = blockService;
    }

    /** Disciplina do perfil com quantas questoes ja tem e quantas o edital pede (null = sem quantidade). */
    public record SubjectProgress(String disciplina, List<String> apelidos, double peso, Integer pedidas,
                                  int criadas) {
        public Integer faltam() {
            return pedidas == null ? null : Math.max(0, pedidas - criadas);
        }
    }

    /** Questao gravada, para achar o numero ao corrigir ou remover. */
    public record QuestionRef(int numero, String disciplina, String assunto, String resposta) {
    }

    public record ExamStatus(Long id, String titulo, String banca, String perfil, String prova,
                             Integer tempoMinutos, int questoes, List<SubjectProgress> disciplinas,
                             boolean completo, Long blocoId, List<QuestionRef> gravadas) {
    }

    public record AddResult(int gravadas, List<String> recusadas, ExamStatus status) {
    }

    /** Abre o rascunho. {@code groupName} vazio = todas as provas do perfil num simulado so. */
    @Transactional
    public ExamStatus create(Long profileId, String groupName, String title, String banca, Integer timeLimit,
                             String styleNotes, String styleSources) {
        ProfileSpec spec = profileService.spec(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Perfil " + profileId + " não existe."));
        String group = groupName == null || groupName.isBlank() ? null : groupName.trim();
        if (group != null && groups(spec, group).isEmpty()) {
            throw new IllegalArgumentException("O perfil não tem a prova \"" + group + "\". Provas: "
                    + String.join(", ", spec.groups().stream().map(GroupSpec::name).toList()) + ".");
        }
        if (styleNotes == null || styleNotes.strip().length() < MIN_STYLE_CHARS) {
            throw new IllegalArgumentException("Pesquise a banca antes de criar: leia provas reais da mesma banca e área "
                    + "(na internet e com provas_da_banca/exemplos_da_banca) e descreva em 'estiloDaBanca', por "
                    + "disciplina, o formato dos enunciados, o tipo de comando, como são os distratores e as "
                    + "pegadinhas recorrentes (mínimo " + MIN_STYLE_CHARS + " caracteres).");
        }
        long sources = styleSources == null ? 0 : styleSources.lines().filter(l -> !l.isBlank()).count();
        if (sources < MIN_SOURCES) {
            throw new IllegalArgumentException("Informe em 'fontes' ao menos " + MIN_SOURCES + " provas reais analisadas, "
                    + "uma por linha (link da prova na internet ou \"prova local #id\").");
        }
        ImportJob job = new ImportJob();
        job.setTitle(title);
        job.setBanca(banca);
        job.setSourceFormat("JSON");
        job.setFileName("simulado-claude-" + System.currentTimeMillis() + ".json");
        job.setStartedAt(LocalDateTime.now());
        job.setFinishedAt(LocalDateTime.now());
        job.setReport("Simulado criado pelo Claude (" + spec.name() + (group == null ? "" : " · " + group) + ").");
        Long concurso = concursoService.forProfile(profileId);
        job.setConcursoId(concurso);
        job = importJobRepository.save(job);

        GeneratedExam exam = new GeneratedExam();
        exam.setImportJobId(job.getId());
        exam.setScoringProfileId(profileId);
        exam.setConcursoId(concurso);
        exam.setGroupName(group);
        exam.setTimeLimitMinutes(timeLimit);
        exam.setStyleNotes(styleNotes.strip());
        exam.setStyleSources(styleSources == null ? null : styleSources.strip());
        return status(examRepository.save(exam));
    }

    /**
     * Grava um lote. Cada questao precisa de disciplina do perfil (nome ou apelido; e gravada com o nome do perfil,
     * para pontuar) e nao pode passar da quantidade do edital. As recusadas voltam com o motivo.
     */
    @Transactional
    public AddResult add(Long examId, List<QuestionDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            throw new IllegalArgumentException("Envie ao menos uma questão.");
        }
        if (drafts.size() > MAX_BATCH) {
            throw new IllegalArgumentException("No máximo " + MAX_BATCH + " questões por chamada: escreva e revise "
                    + "poucas de cada vez.");
        }
        GeneratedExam exam = draft(examId);
        ExamStatus before = status(exam);
        Map<String, int[]> room = new LinkedHashMap<>();
        before.disciplinas().forEach(d -> room.put(d.disciplina(),
                new int[]{d.pedidas() == null ? Integer.MAX_VALUE : d.faltam()}));
        List<RuleSpec> rules = rules(exam);
        List<String> refused = new ArrayList<>();
        List<QuestionDraft> accepted = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            QuestionDraft q = drafts.get(i);
            RuleSpec rule = match(rules, q.subject());
            if (rule == null) {
                refused.add("Questão " + (i + 1) + ": disciplina \"" + q.subject() + "\" não é da prova. Use uma de: "
                        + String.join(", ", rules.stream().map(RuleSpec::subject).toList()) + ".");
                continue;
            }
            if (q.explanation() == null || q.explanation().strip().length() < 40) {
                refused.add("Questão " + (i + 1) + ": falta o gabarito comentado (por que a correta está certa e "
                        + "cada distrator errado).");
                continue;
            }
            if (room.get(rule.subject())[0] <= 0) {
                refused.add("Questão " + (i + 1) + ": " + rule.subject() + " já tem as " + rule.questionCount()
                        + " questões do edital.");
                continue;
            }
            room.get(rule.subject())[0]--;
            accepted.add(new QuestionDraft(q.statement(), q.alternatives(), q.answer(), null, null, rule.subject(),
                    q.topic(), q.explanation(), q.passage()));
        }
        int saved = 0;
        if (!accepted.isEmpty()) {
            ImportJob job = importJobRepository.findById(exam.getImportJobId()).orElseThrow();
            String prefix = "simulado-claude-" + exam.getId() + "-" + (before.questoes() + 1);
            ImportOutcome outcome = importService.appendJson(job.getId(),
                    QuestionDraft.toJson(job.getTitle(), job.getBanca(), prefix, SOURCE, accepted));
            if (outcome.error() != null) {
                throw new IllegalArgumentException(outcome.error());
            }
            saved = outcome.created();
            if (outcome.skipped() > 0) {
                refused.add(outcome.skipped() + " questões com erro de formato (resposta fora das alternativas, "
                        + "alternativa vazia...) não entraram; veja o relatório da prova.");
            }
        }
        return new AddResult(saved, refused, status(exam));
    }

    /**
     * Fecha os rascunhos num bloco com o perfil e a soma dos tempos. Incompleto (faltando questao do edital) so com
     * {@code allowIncomplete}.
     */
    @Transactional
    public StudyBlock finish(List<Long> examIds, String name, boolean allowIncomplete) {
        if (examIds == null || examIds.isEmpty()) {
            throw new IllegalArgumentException("Informe ao menos um simulado.");
        }
        List<GeneratedExam> exams = examIds.stream().map(this::draft).toList();
        Long profileId = exams.get(0).getScoringProfileId();
        if (exams.stream().anyMatch(e -> !profileId.equals(e.getScoringProfileId()))) {
            throw new IllegalArgumentException("Os simulados de um bloco precisam usar o mesmo perfil.");
        }
        List<String> missing = new ArrayList<>();
        Integer time = 0;
        List<BlockService.SourceForm> sources = new ArrayList<>();
        StringBuilder description = new StringBuilder("Simulado criado pelo Claude.");
        if (exams.get(0).getStyleSources() != null) {
            description.append("\nProvas analisadas:\n").append(exams.get(0).getStyleSources());
        }
        for (GeneratedExam exam : exams) {
            ExamStatus status = status(exam);
            if (status.questoes() == 0) {
                throw new IllegalArgumentException("O simulado " + exam.getId() + " ainda não tem questões.");
            }
            status.disciplinas().stream().filter(d -> d.faltam() != null && d.faltam() > 0)
                    .forEach(d -> missing.add((status.prova() == null ? "" : status.prova() + " · ") + d.disciplina()
                            + ": faltam " + d.faltam()));
            time = time == null || exam.getTimeLimitMinutes() == null ? null : time + exam.getTimeLimitMinutes();
            sources.add(new BlockService.SourceForm(exam.getImportJobId().toString(), null, null, null, null, null,
                    null, false));
            description.append("\n").append(status.prova() == null ? "Prova" : status.prova()).append(": ")
                    .append(status.titulo()).append(" (").append(status.questoes()).append(" questões)");
        }
        if (!missing.isEmpty() && !allowIncomplete) {
            throw new IllegalArgumentException("Faltam questões do edital: " + String.join("; ", missing)
                    + ". Complete com adicionar_questoes ou finalize com incompleto=true.");
        }
        // Nenhuma questao entra no simulado final sem aprovacao nas revisoes de fidelidade e de correcao material.
        List<String> blocking = reviewService.blocking(exams);
        if (!blocking.isEmpty()) {
            throw new IllegalArgumentException(blocking.size() + " questões sem aprovação nas duas revisões ("
                    + String.join("; ", blocking.stream().limit(15).toList()) + (blocking.size() > 15 ? "; …" : "")
                    + "). Revise com revisar_fidelidade e validar_correcao, corrija as reprovadas e tente de novo.");
        }
        description.append("\n\nEstilo da banca:\n").append(exams.get(0).getStyleNotes());
        String blockName = name != null && !name.isBlank() ? name.trim() : exams.size() == 1
                ? importJobRepository.findById(exams.get(0).getImportJobId()).orElseThrow().getDisplayName()
                : "Dia de prova · " + exams.size() + " provas";
        var blockDraft = blockService.draft(null, blockName, description.toString(),
                time == null ? null : time.toString(), profileId, sources, "");
        blockDraft.block().setConcursoId(exams.get(0).getConcursoId());
        StudyBlock block = blockService.save(blockDraft);
        for (GeneratedExam exam : exams) {
            exam.setBlockId(block.getId());
            exam.setFinishedAt(LocalDateTime.now());
            examRepository.save(exam);
        }
        return block;
    }

    /** Questao de um simulado gerado (por numero na prova), para corrigir ou remover. */
    private Question question(GeneratedExam exam, int number) {
        return questionRepository.findByImportJobId(exam.getImportJobId()).stream()
                .filter(q -> Integer.valueOf(number).equals(q.getQuestionNumber())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("O simulado " + exam.getId() + " não tem a questão "
                        + number + "."));
    }

    /**
     * Corrige uma questao ja gravada (campos nulos ficam como estao), guardando a versao anterior. Vale tambem depois de finalizado: as respostas
     * ja dadas sao recorrigidas pelo novo gabarito.
     */
    @Transactional
    public Question fix(Long examId, int number, String statement, List<String> alternatives, String answer,
                        String explanation, String reason) {
        GeneratedExam exam = examRepository.findById(examId)
                .orElseThrow(() -> new IllegalArgumentException("Simulado " + examId + " não existe."));
        Question q = question(exam, number);
        // Versao anterior fica guardada; o conteudo novo muda o hash e as revisoes antigas deixam de valer.
        reviewService.snapshot(q, reason == null || reason.isBlank() ? "correção sem motivo informado" : reason.strip());
        if (statement != null && !statement.isBlank()) {
            q.setStatement(statement.strip());
        }
        if (alternatives != null && !alternatives.isEmpty()) {
            if (alternatives.size() > 5 || alternatives.stream().anyMatch(a -> a == null || a.isBlank())) {
                throw new IllegalArgumentException("Envie de 2 a 5 alternativas, todas com texto.");
            }
            q.setAlternativeA(alternatives.get(0));
            q.setAlternativeB(alternatives.size() > 1 ? alternatives.get(1) : null);
            q.setAlternativeC(alternatives.size() > 2 ? alternatives.get(2) : null);
            q.setAlternativeD(alternatives.size() > 3 ? alternatives.get(3) : null);
            q.setAlternativeE(alternatives.size() > 4 ? alternatives.get(4) : null);
        }
        if (answer != null && !answer.isBlank()) {
            String letter = answer.strip().toUpperCase(java.util.Locale.ROOT);
            if (!q.alternatives().containsKey(letter)) {
                throw new IllegalArgumentException("Resposta \"" + answer + "\" não é uma das alternativas "
                        + q.alternatives().keySet() + ".");
            }
            q.setCorrectAnswer(letter);
        } else if (!q.alternatives().containsKey(q.getCorrectAnswer())) {
            throw new IllegalArgumentException("Com as novas alternativas, informe a resposta correta.");
        }
        if (explanation != null && !explanation.isBlank()) {
            q.setExplanation(explanation.strip());
        }
        questionRepository.save(q);
        sessionService.regrade(List.of(q.getId()));
        return q;
    }

    /** Tira uma questao do rascunho (so antes de finalizar); a vaga da disciplina volta a ficar livre. */
    @Transactional
    public ExamStatus remove(Long examId, int number) {
        GeneratedExam exam = draft(examId);
        Question q = question(exam, number);
        questionRepository.delete(q);
        ImportJob job = importJobRepository.findById(exam.getImportJobId()).orElseThrow();
        job.setImportedQuestions(Math.max(0, job.getImportedQuestions() - 1));
        importJobRepository.save(job);
        return status(exam);
    }

    public List<ExamStatus> drafts() {
        return examRepository.findByBlockIdIsNullOrderByCreatedAtDesc().stream().map(this::status).toList();
    }

    public ExamStatus status(Long examId) {
        return status(examRepository.findById(examId)
                .orElseThrow(() -> new IllegalArgumentException("Simulado " + examId + " não existe.")));
    }

    private GeneratedExam draft(Long examId) {
        GeneratedExam exam = examRepository.findById(examId)
                .orElseThrow(() -> new IllegalArgumentException("Simulado " + examId + " não existe."));
        if (exam.getBlockId() != null) {
            throw new IllegalArgumentException("O simulado " + examId + " já foi finalizado (bloco "
                    + exam.getBlockId() + ").");
        }
        return exam;
    }

    private ExamStatus status(GeneratedExam exam) {
        ScoringProfile profile = profileService.find(exam.getScoringProfileId()).orElseThrow();
        ImportJob job = importJobRepository.findById(exam.getImportJobId()).orElseThrow();
        List<Question> questions = questionRepository.findByImportJobId(job.getId());
        List<SubjectProgress> subjects = new ArrayList<>();
        for (RuleSpec rule : rules(exam)) {
            int created = (int) questions.stream()
                    .filter(q -> rule.subject().equals(q.getSubject())).count();
            subjects.add(new SubjectProgress(rule.subject(), List.copyOf(rule.aliases()), rule.weight(),
                    rule.questionCount(), created));
        }
        boolean complete = subjects.stream().allMatch(s -> s.faltam() == null || s.faltam() == 0);
        return new ExamStatus(exam.getId(), job.getDisplayName(), job.getBanca(), profile.getName(),
                exam.getGroupName(), exam.getTimeLimitMinutes(), questions.size(), subjects, complete,
                exam.getBlockId(), questions.stream().filter(q -> q.getQuestionNumber() != null)
                .sorted(java.util.Comparator.comparing(Question::getQuestionNumber))
                .map(q -> new QuestionRef(q.getQuestionNumber(), q.getSubject(), q.getTopic(), q.getCorrectAnswer()))
                .toList());
    }

    private List<RuleSpec> rules(GeneratedExam exam) {
        ProfileSpec spec = profileService.spec(exam.getScoringProfileId()).orElseThrow();
        return groups(spec, exam.getGroupName()).stream().flatMap(g -> g.rules().stream()).toList();
    }

    private static List<GroupSpec> groups(ProfileSpec spec, String group) {
        return spec.groups().stream()
                .filter(g -> group == null || ScoringCalculator.normalize(g.name()).equals(ScoringCalculator.normalize(group)))
                .toList();
    }

    private static RuleSpec match(List<RuleSpec> rules, String subject) {
        if (subject == null) {
            return null;
        }
        String wanted = ScoringCalculator.normalize(subject);
        return rules.stream().filter(r -> ScoringCalculator.normalize(r.subject()).equals(wanted)
                        || r.aliases().stream().anyMatch(a -> ScoringCalculator.normalize(a).equals(wanted)))
                .findFirst().orElse(null);
    }
}
