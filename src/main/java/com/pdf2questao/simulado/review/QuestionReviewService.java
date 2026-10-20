package com.pdf2questao.simulado.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pdf2questao.importer.ImportJob;
import com.pdf2questao.importer.ImportJobRepository;
import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionRepository;
import com.pdf2questao.scoring.ScoringCalculator;
import com.pdf2questao.scoring.ScoringCalculator.RuleSpec;
import com.pdf2questao.scoring.ScoringProfileService;
import com.pdf2questao.simulado.GeneratedExam;
import com.pdf2questao.simulado.GeneratedExamRepository;
import com.pdf2questao.simulado.GeneratedExamService;
import com.pdf2questao.simulado.review.ReviewRules.Criterion;
import com.pdf2questao.simulado.review.ReviewRules.Outcome;
import com.pdf2questao.simulado.review.ReviewRules.Source;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Revisao em camadas das questoes geradas pelo Claude: (A) estrutura, (B) fidelidade a banca com referencias reais,
 * (C) correcao material com fonte e vigencia, (D) cobertura do edital. As notas e a resolucao independente vem do
 * revisor (o Claude); aqui ficam as regras que decidem o estado, as checagens objetivas (referencias existem,
 * duplicatas, topico do edital, conteudo alterado depois da revisao) e o relatorio de auditoria.
 */
@Service
public class QuestionReviewService {

    private final QuestionReviewRepository reviewRepository;
    private final QuestionRevisionRepository revisionRepository;
    private final EditalTopicRepository topicRepository;
    private final GeneratedExamRepository examRepository;
    private final QuestionRepository questionRepository;
    private final ImportJobRepository importJobRepository;
    private final ScoringProfileService profileService;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public QuestionReviewService(QuestionReviewRepository reviewRepository,
                                 QuestionRevisionRepository revisionRepository, EditalTopicRepository topicRepository,
                                 GeneratedExamRepository examRepository, QuestionRepository questionRepository,
                                 ImportJobRepository importJobRepository, ScoringProfileService profileService) {
        this.reviewRepository = reviewRepository;
        this.revisionRepository = revisionRepository;
        this.topicRepository = topicRepository;
        this.examRepository = examRepository;
        this.questionRepository = questionRepository;
        this.importJobRepository = importJobRepository;
        this.profileService = profileService;
    }

    // ---- conteudo programatico (D) ----

    /** Grava (substitui) os topicos do edital de uma disciplina do perfil. @return quantos */
    @Transactional
    public int saveTopics(Long profileId, String subject, List<String> topics) {
        RuleSpec rule = profileRules(profileId).stream().filter(r -> matches(r, subject)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Disciplina \"" + subject + "\" não está no perfil."));
        topicRepository.deleteByScoringProfileIdAndSubject(profileId, rule.subject());
        int position = 0;
        for (String t : topics == null ? List.<String>of() : topics) {
            if (t == null || t.isBlank()) {
                continue;
            }
            EditalTopic topic = new EditalTopic();
            topic.setScoringProfileId(profileId);
            topic.setSubject(rule.subject());
            topic.setTopic(t.strip());
            topic.setPosition(position++);
            topicRepository.save(topic);
        }
        return position;
    }

    // ---- revisoes (B e C) ----

    /** Referencia usada como modelo: questao real local (id) ou prova externa (url + o que foi lido). */
    public record Reference(Long questaoId, String url, String descricao) {
    }

    public record ReviewView(Long simuladoId, int numero, Long questaoId, String disciplina, String assunto,
                             ReviewStatus estado, String estadoDescricao, ReviewStatus fidelidade, Double notaFidelidade,
                             ReviewStatus correcao, ContentType tipoConteudo, String topicoEdital,
                             boolean alteradaDepoisDaRevisao, List<String> problemas) {
    }

    @Transactional
    public ReviewView reviewFidelity(Long examId, int number, List<Criterion> criteria, List<Reference> references,
                                     String editalTopic) {
        GeneratedExam exam = exam(examId);
        Question q = question(exam, number);
        String banca = importJobRepository.findById(exam.getImportJobId()).map(ImportJob::getBanca).orElse(null);
        List<String> refIssues = new ArrayList<>();
        List<Reference> valid = new ArrayList<>();
        List<String> referenced = new ArrayList<>();
        for (Reference r : references == null ? List.<Reference>of() : references) {
            if (r.questaoId() != null) {
                Optional<Question> ref = questionRepository.findById(r.questaoId());
                if (ref.isEmpty()) {
                    refIssues.add("Referência questão #" + r.questaoId() + " não existe.");
                } else if (isGenerated(ref.get())) {
                    refIssues.add("Referência #" + r.questaoId() + " foi escrita pelo Claude, não é prova real.");
                } else if (banca != null && ref.get().getOrganization() != null
                        && !banca.equalsIgnoreCase(ref.get().getOrganization())) {
                    refIssues.add("Referência #" + r.questaoId() + " é de outra banca (" + ref.get().getOrganization() + ").");
                } else {
                    valid.add(r);
                    referenced.add(ref.get().getStatement());
                }
            } else if (r.url() != null && r.url().strip().startsWith("http") && r.descricao() != null
                    && r.descricao().strip().length() >= 15) {
                valid.add(r);
            } else {
                refIssues.add("Referência sem questão local nem link + descrição do que foi lido.");
            }
        }
        double similarity = maxSimilarity(q, referenced);
        Outcome outcome = ReviewRules.fidelity(criteria, valid.size(), similarity);
        List<String> issues = new ArrayList<>(refIssues);
        issues.addAll(outcome.issues());
        QuestionReview review = review(q);
        review.setFidelityStatus(outcome.status());
        review.setFidelityScore(outcome.score());
        review.setFidelityCriteria(json(criteria));
        review.setFidelityReferences(json(valid));
        review.setFidelityIssues(json(issues));
        review.setFidelityHash(hash(q));
        review.setFidelityAt(LocalDateTime.now());
        if (editalTopic != null && !editalTopic.isBlank()) {
            review.setEditalTopic(editalTopic.strip());
        }
        return view(exam, q, save(review, q));
    }

    @Transactional
    public ReviewView reviewMaterial(Long examId, int number, ContentType type, String independentAnswer,
                                     List<String> defensible, boolean involvesCalculation, String calculation,
                                     List<Source> sources, boolean historicalRule, String temporalReference,
                                     String notes) {
        GeneratedExam exam = exam(examId);
        Question q = question(exam, number);
        Outcome outcome = ReviewRules.material(type, q.getCorrectAnswer(), independentAnswer, defensible,
                involvesCalculation, calculation, sources, historicalRule, temporalReference);
        QuestionReview review = review(q);
        review.setMaterialStatus(outcome.status());
        review.setContentType(type);
        review.setIndependentAnswer(independentAnswer == null ? null : independentAnswer.strip().toUpperCase(Locale.ROOT));
        review.setDefensibleAnswers(defensible == null ? null : String.join(",", defensible));
        review.setCalculation(calculation);
        review.setSources(json(sources));
        review.setHistoricalRule(historicalRule);
        review.setTemporalReference(temporalReference);
        review.setMaterialNotes(notes);
        review.setMaterialIssues(json(outcome.issues()));
        review.setMaterialHash(hash(q));
        review.setMaterialAt(LocalDateTime.now());
        return view(exam, q, save(review, q));
    }

    public ReviewView view(Long examId, int number) {
        GeneratedExam exam = exam(examId);
        Question q = question(exam, number);
        return view(exam, q, reviewRepository.findByQuestionId(q.getId()).orElse(null));
    }

    /** Questao sem gabarito nem comentario, para o revisor resolver de forma independente. */
    public record BlindQuestion(int numero, String disciplina, String assunto, String textoBase, String enunciado,
                                Map<String, String> alternativas) {
    }

    public BlindQuestion blind(Long examId, int number, Function<Long, String> passageText) {
        Question q = question(exam(examId), number);
        return new BlindQuestion(number, q.getSubject(), q.getTopic(),
                q.getPassageId() == null ? null : passageText.apply(q.getPassageId()), q.getStatement(), q.alternatives());
    }

    /** Guarda a versao atual antes de corrigir (o hash muda e as revisoes antigas deixam de valer). */
    @Transactional
    public void snapshot(Question q, String reason) {
        QuestionRevision revision = new QuestionRevision();
        revision.setQuestionId(q.getId());
        revision.setStatement(q.getStatement());
        revision.setAlternatives(json(q.alternatives()));
        revision.setCorrectAnswer(q.getCorrectAnswer());
        revision.setExplanation(q.getExplanation());
        revision.setReviewSnapshot(reviewRepository.findByQuestionId(q.getId()).map(this::json).orElse(null));
        revision.setReason(reason);
        revisionRepository.save(revision);
    }

    /** Questoes que impedem de finalizar (nao aprovadas nas duas revisoes do conteudo atual). */
    public List<String> blocking(Collection<GeneratedExam> exams) {
        List<String> out = new ArrayList<>();
        for (GeneratedExam exam : exams) {
            for (Question q : questions(exam)) {
                ReviewView v = view(exam, q, reviewRepository.findByQuestionId(q.getId()).orElse(null));
                if (v.estado() != ReviewStatus.APROVADA) {
                    out.add((exam.getGroupName() == null ? "" : exam.getGroupName() + " ") + "Q" + v.numero() + ": "
                            + v.estado().label());
                }
            }
        }
        return out;
    }

    // ---- auditoria ----

    public record SubjectAudit(String disciplina, int questoes, int revisadasFidelidade, Double mediaFidelidade,
                               int comReferenciaReal, String confianca, int conteudoQueExigeFonte,
                               int fontesVerificadas, int topicosNoEdital, List<String> topicosSemQuestao,
                               List<String> topicosRepetidos) {
    }

    public record QuestionAudit(String prova, int numero, String disciplina, String assunto, String estado,
                                Double notaFidelidade, ContentType tipoConteudo, String respostaGabarito,
                                String respostaIndependente, boolean alteradaDepoisDaRevisao, int versoesAnteriores,
                                List<String> problemas) {
    }

    public record Audit(LocalDateTime geradoEm, List<String> estrutura, Map<ReviewStatus, Long> porEstado,
                        int aprovadas, int total, List<QuestionAudit> falhasCriticas, List<QuestionAudit> pendentes,
                        List<SubjectAudit> porDisciplina, Map<String, Long> distribuicaoGabarito,
                        List<String> duplicatas, Set<String> referenciasUsadas, List<String> referenciasDisponiveis,
                        List<QuestionAudit> questoes, List<String> limitacoes, String conclusao) {
    }

    public Audit audit(List<Long> examIds) {
        List<GeneratedExam> exams = examIds.stream().map(this::exam).toList();
        List<String> structure = new ArrayList<>();
        List<QuestionAudit> all = new ArrayList<>();
        Map<String, Long> letters = new TreeMap<>();
        Set<String> usedRefs = new LinkedHashSet<>();
        List<String> available = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        List<SubjectAudit> subjects = new ArrayList<>();
        List<Question> everyQuestion = new ArrayList<>();
        for (GeneratedExam exam : exams) {
            String prova = exam.getGroupName() == null ? "#" + exam.getId() : exam.getGroupName();
            if (exam.getStyleSources() != null) {
                exam.getStyleSources().lines().filter(l -> !l.isBlank()).forEach(l -> available.add(prova + ": " + l.strip()));
            }
            List<Question> questions = questions(exam);
            everyQuestion.addAll(questions);
            Map<Long, QuestionReview> reviews = reviewRepository.findByQuestionIdIn(
                    questions.stream().map(Question::getId).toList()).stream()
                    .collect(Collectors.toMap(QuestionReview::getQuestionId, r -> r));
            Map<Long, Long> revisions = revisionRepository.findByQuestionIdInOrderByCreatedAt(
                    questions.stream().map(Question::getId).toList()).stream()
                    .collect(Collectors.groupingBy(QuestionRevision::getQuestionId, Collectors.counting()));
            // (A) estrutura
            List<RuleSpec> rules = rules(exam);
            for (RuleSpec rule : rules) {
                long count = questions.stream().filter(q -> rule.subject().equals(q.getSubject())).count();
                if (rule.questionCount() != null && count != rule.questionCount()) {
                    structure.add(prova + " · " + rule.subject() + ": " + count + " questões (edital: " + rule.questionCount() + ").");
                }
            }
            for (Question q : questions) {
                if (q.alternatives().size() != 5) {
                    structure.add(prova + " Q" + q.getQuestionNumber() + ": " + q.alternatives().size() + " alternativas (FCC usa 5).");
                }
                if (q.getCorrectAnswer() == null || !q.alternatives().containsKey(q.getCorrectAnswer())) {
                    structure.add(prova + " Q" + q.getQuestionNumber() + ": gabarito ausente ou fora das alternativas.");
                }
                if (q.getExplanation() == null || q.getExplanation().isBlank()) {
                    structure.add(prova + " Q" + q.getQuestionNumber() + ": sem comentário.");
                }
                if (q.getSubject() == null || rules.stream().noneMatch(r -> r.subject().equals(q.getSubject()))) {
                    structure.add(prova + " Q" + q.getQuestionNumber() + ": disciplina fora do perfil.");
                }
                letters.merge(q.getCorrectAnswer() == null ? "?" : q.getCorrectAnswer(), 1L, Long::sum);
                QuestionReview r = reviews.get(q.getId());
                ReviewView v = view(exam, q, r);
                if (r != null && r.getFidelityReferences() != null) {
                    for (Reference ref : list(r.getFidelityReferences(), new TypeReference<List<Reference>>() { })) {
                        usedRefs.add(ref.questaoId() != null ? "questão local #" + ref.questaoId() : ref.url());
                    }
                }
                all.add(new QuestionAudit(prova, q.getQuestionNumber() == null ? 0 : q.getQuestionNumber(),
                        q.getSubject(), q.getTopic(), v.estado().label(), v.notaFidelidade(), v.tipoConteudo(),
                        q.getCorrectAnswer(), r == null ? null : r.getIndependentAnswer(), v.alteradaDepoisDaRevisao(),
                        revisions.getOrDefault(q.getId(), 0L).intValue(), v.problemas()));
            }
            // (D) cobertura e disciplinas
            for (RuleSpec rule : rules) {
                List<Question> qs = questions.stream().filter(q -> rule.subject().equals(q.getSubject())).toList();
                List<String> topics = topicRepository.findByScoringProfileIdAndSubjectOrderByPosition(
                        exam.getScoringProfileId(), rule.subject()).stream().map(EditalTopic::getTopic).toList();
                List<QuestionReview> rs = qs.stream().map(q -> reviews.get(q.getId())).filter(r -> r != null).toList();
                Map<String, Long> byTopic = rs.stream().filter(r -> r.getEditalTopic() != null)
                        .collect(Collectors.groupingBy(r -> r.getEditalTopic().strip(), Collectors.counting()));
                List<String> uncovered = topics.stream().filter(t -> byTopic.keySet().stream()
                        .noneMatch(c -> sameTopic(c, t))).toList();
                List<String> repeated = byTopic.entrySet().stream()
                        .filter(e -> qs.size() >= 4 && e.getValue() > Math.max(2, qs.size() / 3))
                        .map(e -> e.getKey() + " (" + e.getValue() + ")").toList();
                List<QuestionReview> fid = rs.stream().filter(r -> r.getFidelityScore() != null
                        && hash(questionOf(qs, r)).equals(r.getFidelityHash())).toList();
                int withRef = (int) fid.stream().filter(r -> r.getFidelityReferences() != null
                        && !list(r.getFidelityReferences(), new TypeReference<List<Reference>>() { }).isEmpty()).count();
                Double avg = fid.isEmpty() ? null
                        : Math.round(fid.stream().mapToDouble(QuestionReview::getFidelityScore).average().orElse(0) * 100) / 100.0;
                int needSource = (int) rs.stream().filter(r -> r.getContentType() != null && r.getContentType().needsSource()).count();
                int verified = (int) rs.stream().filter(r -> r.getContentType() != null && r.getContentType().needsSource()
                        && r.getMaterialStatus() == ReviewStatus.APROVADA_CORRECAO
                        && hash(questionOf(qs, r)).equals(r.getMaterialHash())).count();
                String confidence = fid.size() < qs.size() ? "baixa: " + (qs.size() - fid.size()) + " sem revisão de fidelidade válida"
                        : withRef < fid.size() ? "média: há questões sem referência real" : "alta para o que foi revisado";
                subjects.add(new SubjectAudit(prova + " · " + rule.subject(), qs.size(), fid.size(), avg, withRef,
                        confidence, needSource, verified, topics.size(),
                        topics.isEmpty() ? List.of("(conteúdo programático não registrado: cobertura não verificável)")
                                : uncovered, repeated));
            }
        }
        // duplicatas dentro do(s) simulado(s)
        for (int i = 0; i < everyQuestion.size(); i++) {
            for (int j = i + 1; j < everyQuestion.size(); j++) {
                double s = ReviewRules.similarity(everyQuestion.get(i).getStatement(), everyQuestion.get(j).getStatement());
                if (s >= ReviewRules.DUPLICATE_THRESHOLD) {
                    duplicates.add("Q#" + everyQuestion.get(i).getId() + " e Q#" + everyQuestion.get(j).getId()
                            + ": " + Math.round(s * 100) + "% iguais");
                }
            }
        }
        Map<ReviewStatus, Long> byStatus = new LinkedHashMap<>();
        for (ReviewStatus s : ReviewStatus.values()) {
            long n = all.stream().filter(a -> a.estado().equals(s.label())).count();
            if (n > 0) {
                byStatus.put(s, n);
            }
        }
        List<QuestionAudit> critical = all.stream().filter(a -> a.estado().equals(ReviewStatus.REPROVADA.label())).toList();
        List<QuestionAudit> pending = all.stream().filter(a -> a.estado().equals(ReviewStatus.PENDENTE_FONTE.label())).toList();
        int approved = (int) all.stream().filter(a -> a.estado().equals(ReviewStatus.APROVADA.label())).count();
        List<String> limits = new ArrayList<>(List.of(
                "Notas de fidelidade e resoluções independentes são registradas pelo revisor (o Claude); o sistema "
                        + "confere referências, duplicatas, gabarito, fontes e vigência declaradas, não o mérito jurídico.",
                "Fonte só conta como verificada se o revisor registrou dispositivo, data e redação vigente; fonte "
                        + "indisponível fica pendente, nunca aprovada.",
                "A distribuição das letras do gabarito é informativa: não prova qualidade nem defeito."));
        if (subjects.stream().anyMatch(s -> s.topicosNoEdital() == 0)) {
            limits.add("Há disciplinas sem conteúdo programático registrado: a cobertura delas não foi verificada.");
        }
        String conclusion = critical.isEmpty() && pending.isEmpty() && approved == all.size()
                ? "Todas as " + all.size() + " questões aprovadas nas duas revisões do conteúdo atual."
                : approved + " de " + all.size() + " aprovadas; " + critical.size() + " reprovadas; " + pending.size()
                + " pendentes de fonte; " + (all.size() - approved - critical.size() - pending.size())
                + " sem revisão completa. O simulado NÃO está validado.";
        return new Audit(LocalDateTime.now(), structure, byStatus, approved, all.size(), critical, pending, subjects,
                letters, duplicates, usedRefs, available, all, limits, conclusion);
    }

    // ---- apoio ----

    private ReviewView view(GeneratedExam exam, Question q, QuestionReview r) {
        String current = hash(q);
        boolean fidelityValid = r != null && r.getFidelityStatus() != null && current.equals(r.getFidelityHash());
        boolean materialValid = r != null && r.getMaterialStatus() != null && current.equals(r.getMaterialHash());
        boolean stale = r != null && ((r.getFidelityStatus() != null && !fidelityValid)
                || (r.getMaterialStatus() != null && !materialValid));
        List<String> issues = new ArrayList<>();
        if (fidelityValid) {
            issues.addAll(list(r.getFidelityIssues(), new TypeReference<List<String>>() { }));
        }
        if (materialValid) {
            issues.addAll(list(r.getMaterialIssues(), new TypeReference<List<String>>() { }));
        }
        if (stale) {
            issues.add("Questão alterada depois da revisão: revise de novo.");
        }
        boolean outside = false;
        String topic = r == null ? null : r.getEditalTopic();
        List<String> topics = topicRepository.findByScoringProfileIdAndSubjectOrderByPosition(exam.getScoringProfileId(),
                q.getSubject() == null ? "" : q.getSubject()).stream().map(EditalTopic::getTopic).toList();
        if (!topics.isEmpty() && (fidelityValid || materialValid)) {
            if (topic == null) {
                issues.add("Informe o tópico do edital que a questão cobra.");
                outside = true;
            } else if (topics.stream().noneMatch(t -> sameTopic(topic, t))) {
                issues.add("Tópico \"" + topic + "\" não está no conteúdo programático de " + q.getSubject() + ".");
                outside = true;
            }
        }
        ReviewStatus fidelity = fidelityValid ? r.getFidelityStatus() : null;
        ReviewStatus material = materialValid ? r.getMaterialStatus() : null;
        ReviewStatus overall = ReviewRules.overall(fidelity, material, outside);
        return new ReviewView(exam.getId(), q.getQuestionNumber() == null ? 0 : q.getQuestionNumber(), q.getId(),
                q.getSubject(), q.getTopic(), overall, overall.label(), fidelity,
                fidelityValid ? r.getFidelityScore() : null, material, materialValid ? r.getContentType() : null,
                topic, stale, issues);
    }

    private QuestionReview review(Question q) {
        return reviewRepository.findByQuestionId(q.getId()).orElseGet(() -> {
            QuestionReview r = new QuestionReview();
            r.setQuestionId(q.getId());
            return r;
        });
    }

    private QuestionReview save(QuestionReview review, Question q) {
        review.setContentHash(hash(q));
        review.setUpdatedAt(LocalDateTime.now());
        return reviewRepository.save(review);
    }

    private double maxSimilarity(Question q, List<String> referenced) {
        double max = 0;
        for (String text : referenced) {
            max = Math.max(max, ReviewRules.similarity(q.getStatement(), text));
        }
        // outras questoes geradas pelo Claude (inclusive de outros simulados) e reais da mesma disciplina
        for (Question other : questionRepository.findAll()) {
            if (!other.getId().equals(q.getId()) && other.getStatement() != null
                    && (isGenerated(other) || q.getSubject() != null && q.getSubject().equals(other.getSubject()))) {
                max = Math.max(max, ReviewRules.similarity(q.getStatement(), other.getStatement()));
            }
        }
        return max;
    }

    private static boolean isGenerated(Question q) {
        return GeneratedExamService.SOURCE.equals(q.getSource()) || "Treino no Claude".equals(q.getSource());
    }

    /** Hash do que a revisao avaliou: enunciado, alternativas, gabarito e comentario. */
    public static String hash(Question q) {
        String content = String.join("\u0001", String.valueOf(q.getStatement()), String.valueOf(q.alternatives()),
                String.valueOf(q.getCorrectAnswer()), String.valueOf(q.getExplanation()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private GeneratedExam exam(Long examId) {
        return examRepository.findById(examId)
                .orElseThrow(() -> new IllegalArgumentException("Simulado " + examId + " não existe."));
    }

    private List<Question> questions(GeneratedExam exam) {
        return questionRepository.findByImportJobId(exam.getImportJobId()).stream()
                .sorted(Comparator.comparing(Question::getQuestionNumber, Comparator.nullsLast(Integer::compare)))
                .toList();
    }

    private Question question(GeneratedExam exam, int number) {
        return questions(exam).stream().filter(q -> Integer.valueOf(number).equals(q.getQuestionNumber())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("O simulado " + exam.getId() + " não tem a questão "
                        + number + "."));
    }

    private static Question questionOf(List<Question> qs, QuestionReview r) {
        return qs.stream().filter(q -> q.getId().equals(r.getQuestionId())).findFirst().orElseThrow();
    }

    private List<RuleSpec> rules(GeneratedExam exam) {
        var spec = profileService.spec(exam.getScoringProfileId()).orElseThrow();
        return spec.groups().stream().filter(g -> exam.getGroupName() == null
                        || ScoringCalculator.normalize(g.name()).equals(ScoringCalculator.normalize(exam.getGroupName())))
                .flatMap(g -> g.rules().stream()).toList();
    }

    private List<RuleSpec> profileRules(Long profileId) {
        return profileService.spec(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Perfil " + profileId + " não existe."))
                .groups().stream().flatMap(g -> g.rules().stream()).toList();
    }

    private static boolean matches(RuleSpec rule, String subject) {
        String wanted = ScoringCalculator.normalize(subject);
        return ScoringCalculator.normalize(rule.subject()).equals(wanted)
                || rule.aliases().stream().anyMatch(a -> ScoringCalculator.normalize(a).equals(wanted));
    }

    /** Topico informado bate com o do edital se um contem o outro (ignorando acento e caixa). */
    static boolean sameTopic(String given, String edital) {
        String a = ScoringCalculator.normalize(given);
        String b = ScoringCalculator.normalize(edital);
        return !a.isEmpty() && !b.isEmpty() && (a.contains(b) || b.contains(a));
    }

    private String json(Object value) {
        try {
            return value == null ? null : mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> List<T> list(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
