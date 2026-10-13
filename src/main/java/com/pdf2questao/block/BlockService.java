package com.pdf2questao.block;

import com.pdf2questao.importer.ImportJob;
import com.pdf2questao.importer.ImportJobRepository;
import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionRepository;
import com.pdf2questao.scoring.ScoringCalculator;
import com.pdf2questao.scoring.ScoringProfile;
import com.pdf2questao.scoring.ScoringProfileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BlockService {

    private static final Pattern EXCLUSION_TOKEN = Pattern.compile("^(?:#(\\d+)|(?:(\\d+):)?([\\d\\s\\-–,]+))$");

    public static final String DIAG_1 = "Diagnóstico APOFP – Sessão 1";
    public static final String DIAG_2 = "Diagnóstico APOFP – Sessão 2";

    /** Um bloco ainda nao salvo (vem do formulario). */
    public record Draft(StudyBlock block, List<StudyBlockSource> sources, List<StudyBlockExclusion> exclusions) {
    }

    /** Linha do formulario de origem; campos em branco = sem filtro. */
    public record SourceForm(String importJobId, String ranges, String subject, String topic, String organization,
                             String year, String max, boolean shuffle) {

        boolean isBlank() {
            return blank(importJobId) && blank(ranges) && blank(subject) && blank(topic) && blank(organization)
                    && blank(year) && blank(max);
        }
    }

    public record SourceCount(String label, int count) {
    }

    public record Preview(int total, Map<String, Integer> bySubject, List<SourceCount> perSource, int excluded,
                          List<String> warnings, Map<String, Integer> unweightedSubjects, List<Question> questions) {
    }

    private final StudyBlockRepository blockRepository;
    private final StudyBlockSourceRepository sourceRepository;
    private final StudyBlockExclusionRepository exclusionRepository;
    private final QuestionRepository questionRepository;
    private final ImportJobRepository importJobRepository;
    private final ScoringProfileService profileService;

    public BlockService(StudyBlockRepository blockRepository, StudyBlockSourceRepository sourceRepository,
                        StudyBlockExclusionRepository exclusionRepository, QuestionRepository questionRepository,
                        ImportJobRepository importJobRepository, ScoringProfileService profileService) {
        this.blockRepository = blockRepository;
        this.sourceRepository = sourceRepository;
        this.exclusionRepository = exclusionRepository;
        this.questionRepository = questionRepository;
        this.importJobRepository = importJobRepository;
        this.profileService = profileService;
    }

    // ---- montagem a partir do formulario ----

    /** @throws IllegalArgumentException com a mensagem a mostrar ao usuario. */
    public Draft draft(Long id, String name, String description, String timeLimit, Long profileId,
                       List<SourceForm> sourceForms, String exclusionText) {
        if (blank(name)) {
            throw new IllegalArgumentException("Dê um nome ao bloco.");
        }
        StudyBlock block = id == null ? new StudyBlock() : blockRepository.findById(id).orElseThrow();
        block.setName(name.trim());
        block.setDescription(blank(description) ? null : description.trim());
        block.setTimeLimitMinutes(parseInt(timeLimit, "Tempo limite"));
        if (block.getTimeLimitMinutes() != null && block.getTimeLimitMinutes() <= 0) {
            throw new IllegalArgumentException("Tempo limite deve ser maior que zero (ou fique em branco).");
        }
        block.setScoringProfileId(profileId);

        List<StudyBlockSource> sources = new ArrayList<>();
        int position = 0;
        for (SourceForm f : sourceForms) {
            if (f.isBlank()) {
                continue;
            }
            int n = ++position;
            StudyBlockSource s = new StudyBlockSource();
            s.setPosition(n);
            s.setImportJobId(blank(f.importJobId()) ? null : parseLong(f.importJobId(), "Prova da origem " + n));
            try {
                s.setRanges(blank(f.ranges()) ? null : RangeExpression.parse(f.ranges()).toString());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Origem " + n + ": " + e.getMessage());
            }
            s.setSubject(trimToNull(f.subject()));
            s.setTopic(trimToNull(f.topic()));
            s.setOrganization(trimToNull(f.organization()));
            s.setYear(parseInt(f.year(), "Ano da origem " + n));
            s.setMaxQuestions(parseInt(f.max(), "Limite da origem " + n));
            s.setShuffle(f.shuffle());
            sources.add(s);
        }
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("Inclua ao menos uma origem de questões (prova ou filtro).");
        }
        return new Draft(block, sources, parseExclusions(exclusionText));
    }

    /** Aceita {@code #ID}, {@code 7}, {@code 5-8, 12} (numero em qualquer prova do bloco) e {@code 42:7-9} (numero numa prova). */
    List<StudyBlockExclusion> parseExclusions(String text) {
        List<StudyBlockExclusion> result = new ArrayList<>();
        if (blank(text)) {
            return result;
        }
        for (String line : text.split("[\\n;]")) {
            if (line.isBlank()) {
                continue;
            }
            for (String token : splitTopLevel(line)) {
                String t = token.trim();
                if (t.isEmpty()) {
                    continue;
                }
                Matcher m = EXCLUSION_TOKEN.matcher(t);
                if (!m.matches()) {
                    throw new IllegalArgumentException("Exclusão inválida '" + t
                            + "': use #ID, número, faixa (5-8) ou prova:número (42:7).");
                }
                if (m.group(1) != null) {
                    StudyBlockExclusion e = new StudyBlockExclusion();
                    e.setQuestionId(Long.parseLong(m.group(1)));
                    result.add(e);
                    continue;
                }
                Long job = m.group(2) == null ? null : Long.parseLong(m.group(2));
                RangeExpression numbers;
                try {
                    numbers = RangeExpression.parse(m.group(3));
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("Exclusão '" + t + "': " + ex.getMessage());
                }
                for (int n : numbers.numbers()) {
                    StudyBlockExclusion e = new StudyBlockExclusion();
                    e.setImportJobId(job);
                    e.setQuestionNumber(n);
                    result.add(e);
                }
            }
        }
        return result;
    }

    private List<String> splitTopLevel(String line) {
        return List.of(line.split(","));
    }

    /** Texto editavel das exclusoes: ids como {@code #ID} e numeros agrupados em faixas por prova. */
    public String exclusionText(List<StudyBlockExclusion> exclusions) {
        List<String> parts = new ArrayList<>();
        exclusions.stream().filter(e -> e.getQuestionId() != null).forEach(e -> parts.add("#" + e.getQuestionId()));
        java.util.Map<Long, java.util.TreeSet<Integer>> byJob = new java.util.LinkedHashMap<>();
        for (StudyBlockExclusion e : exclusions) {
            if (e.getQuestionId() == null && e.getQuestionNumber() != null) {
                byJob.computeIfAbsent(e.getImportJobId() == null ? -1L : e.getImportJobId(), k -> new java.util.TreeSet<>())
                        .add(e.getQuestionNumber());
            }
        }
        byJob.forEach((job, numbers) -> {
            // Cada faixa leva o prefixo da prova ("42:10-13, 42:22-25").
            for (String r : compress(numbers)) {
                parts.add(job < 0 ? r : job + ":" + r);
            }
        });
        return String.join(", ", parts);
    }

    private List<String> compress(java.util.TreeSet<Integer> numbers) {
        List<String> out = new ArrayList<>();
        Integer start = null;
        Integer prev = null;
        for (int n : numbers) {
            if (start == null) {
                start = n;
            } else if (n != prev + 1) {
                out.add(start.equals(prev) ? start.toString() : start + "-" + prev);
                start = n;
            }
            prev = n;
        }
        if (start != null) {
            out.add(start.equals(prev) ? start.toString() : start + "-" + prev);
        }
        return out;
    }

    // ---- persistencia ----

    @Transactional
    public StudyBlock save(Draft draft) {
        StudyBlock block = blockRepository.save(draft.block());
        sourceRepository.deleteByBlockId(block.getId());
        exclusionRepository.deleteByBlockId(block.getId());
        for (StudyBlockSource s : draft.sources()) {
            s.setId(null);
            s.setBlockId(block.getId());
            sourceRepository.save(s);
        }
        for (StudyBlockExclusion e : draft.exclusions()) {
            e.setId(null);
            e.setBlockId(block.getId());
            exclusionRepository.save(e);
        }
        return block;
    }

    /** Apaga so o bloco e suas origens/exclusoes - questoes e sessoes ja feitas ficam intactas. */
    @Transactional
    public void delete(Long id) {
        sourceRepository.deleteByBlockId(id);
        exclusionRepository.deleteByBlockId(id);
        blockRepository.deleteById(id);
    }

    public List<StudyBlock> findAll() {
        return blockRepository.findAll();
    }

    public Optional<StudyBlock> find(Long id) {
        return blockRepository.findById(id);
    }

    public List<StudyBlockSource> sources(Long blockId) {
        return sourceRepository.findByBlockIdOrderByPosition(blockId);
    }

    public List<StudyBlockExclusion> exclusions(Long blockId) {
        return exclusionRepository.findByBlockId(blockId);
    }

    // ---- resolucao ----

    public BlockSelector.Selection resolve(Draft draft) {
        return BlockSelector.select(questionRepository.findAll(), draft.sources(), draft.exclusions(), new Random());
    }

    public BlockSelector.Selection resolve(Long blockId) {
        return resolve(new Draft(blockRepository.findById(blockId).orElseThrow(), sources(blockId), exclusions(blockId)));
    }

    public List<Long> questionIds(Long blockId) {
        return resolve(blockId).questions().stream().map(Question::getId).toList();
    }

    public Preview preview(Draft draft) {
        BlockSelector.Selection selection = resolve(draft);
        List<SourceCount> perSource = new ArrayList<>();
        for (int i = 0; i < draft.sources().size(); i++) {
            perSource.add(new SourceCount(label(draft.sources().get(i)), selection.perSource().get(i)));
        }
        Map<String, Integer> unweighted = Map.of();
        Long profileId = draft.block().getScoringProfileId();
        if (profileId != null) {
            unweighted = profileService.spec(profileId)
                    .map(spec -> ScoringCalculator.calculate(spec, selection.questions().stream()
                            .map(q -> new ScoringCalculator.Item(q.getSubject(), null, false)).toList())
                            .unweightedSubjects())
                    .orElse(Map.of());
        }
        return new Preview(selection.questions().size(), BlockSelector.bySubject(selection.questions()), perSource,
                selection.excluded(), selection.warnings(), unweighted, selection.questions());
    }

    public Preview preview(Long blockId) {
        return preview(new Draft(blockRepository.findById(blockId).orElseThrow(), sources(blockId), exclusions(blockId)));
    }

    private String label(StudyBlockSource s) {
        List<String> parts = new ArrayList<>();
        if (s.getImportJobId() != null) {
            String file = importJobRepository.findById(s.getImportJobId()).map(ImportJob::getFileName)
                    .orElse("prova " + s.getImportJobId());
            parts.add(shorten(file) + (blank(s.getRanges()) ? " (toda)" : " [" + s.getRanges() + "]"));
        } else if (!blank(s.getRanges())) {
            parts.add("faixas " + s.getRanges());
        }
        if (!blank(s.getSubject())) {
            parts.add("disciplina: " + s.getSubject());
        }
        if (!blank(s.getTopic())) {
            parts.add("assunto: " + s.getTopic());
        }
        if (!blank(s.getOrganization())) {
            parts.add("banca: " + s.getOrganization());
        }
        if (s.getYear() != null) {
            parts.add("ano " + s.getYear());
        }
        if (s.getMaxQuestions() != null) {
            parts.add("máx. " + s.getMaxQuestions() + (s.isShuffle() ? " (embaralhadas)" : ""));
        } else if (s.isShuffle()) {
            parts.add("embaralhadas");
        }
        return String.join(" • ", parts);
    }

    private String shorten(String file) {
        int i = file.lastIndexOf("conhecimentos-");
        return i >= 0 ? file.substring(i).replace(".pdf", "") : file;
    }

    // ---- blocos de diagnostico de exemplo ----

    /** Diagnostico com as provas achadas pelo nome do arquivo ({@code -p1-prova}, {@code -p2-}, {@code -p3-}). */
    @Transactional
    public String createDiagnosticBlocks() {
        return createDiagnosticBlocks(jobFor("-p1-prova"), jobFor("-p2-prova"), jobFor("-p3-prova"));
    }

    /**
     * Cria (ou atualiza) os dois blocos de diagnostico APOFP sobre as provas escolhidas: a P1 (Conhecimentos
     * Gerais), a P2 (Conhecimentos Basicos) e a P3 (Especificos) de uma mesma prova FCC. As faixas sao as da
     * prova AFRE-TIC; para outras provas, crie os blocos pela tela de blocos.
     *
     * @return mensagem de resultado; se faltar prova, diz qual.
     */
    @Transactional
    public String createDiagnosticBlocks(Long p1, Long p2, Long p3) {
        List<String> missing = new ArrayList<>();
        if (p1 == null) {
            missing.add("P1");
        }
        if (p2 == null) {
            missing.add("P2");
        }
        if (p3 == null) {
            missing.add("P3");
        }
        if (!missing.isEmpty()) {
            return "Escolha as provas que faltam: " + String.join(", ", missing) + ".";
        }
        Long profileId = profileService.findAll().stream()
                .filter(p -> ScoringProfileService.EXAMPLE_NAME.equals(p.getName())).map(ScoringProfile::getId)
                .findFirst().orElse(null);

        save(draft(findByName(DIAG_1), DIAG_1, "P1: Português 1-10, Mat. Financeira/Estatística 32-46, "
                        + "Administração 71-86, Economia 87-100.", null, profileId,
                List.of(new SourceForm(p1.toString(), "1-10, 31-45, 71-85, 86-100", null, null, null, null, null, false)),
                ""));
        // Dentro de P2 1-25: Civil (10-13), Empresarial (14-17) e Penal (22-25) ficam de fora; Financeiro (18-21) fica.
        save(draft(findByName(DIAG_2), DIAG_2, "P2: Direito 1-25 (sem Civil, Empresarial e Penal), "
                        + "Contabilidade 56-70; P3: TIC 21-40.", null, profileId,
                List.of(new SourceForm(p2.toString(), "1-25, 56-70", null, null, null, null, null, false),
                        new SourceForm(p3.toString(), "21-40", null, null, null, null, null, false)),
                p2 + ":10-17, " + p2 + ":22-25"));
        return "Blocos de diagnóstico criados/atualizados.";
    }

    /** Bloco com a prova inteira, nomeado pela prova; se ja existir um com esse nome, so o atualiza. */
    @Transactional
    public StudyBlock createBlockForProva(Long importJobId) {
        ImportJob job = importJobRepository.findById(importJobId).orElseThrow();
        String name = job.getDisplayName();
        return save(draft(findByName(name), name, "Prova inteira.", null, null,
                List.of(new SourceForm(importJobId.toString(), null, null, null, null, null, null, false)), ""));
    }

    private Long findByName(String name) {
        return blockRepository.findAll().stream().filter(b -> name.equals(b.getName())).map(StudyBlock::getId)
                .findFirst().orElse(null);
    }

    /** Provas que o diagnostico escolheria sozinho (por nome de arquivo), para pre-selecionar no formulario. */
    public Map<String, Long> suggestedDiagnosticJobs() {
        Map<String, Long> suggested = new java.util.LinkedHashMap<>();
        suggested.put("p1", jobFor("-p1-prova"));
        suggested.put("p2", jobFor("-p2-prova"));
        suggested.put("p3", jobFor("-p3-prova"));
        return suggested;
    }

    private Long jobFor(String marker) {
        return importJobRepository.findAllByOrderByStartedAtDesc().stream()
                .filter(j -> j.getFileName() != null && j.getFileName().contains(marker) && j.getImportedQuestions() > 0)
                .map(ImportJob::getId).findFirst().orElse(null);
    }

    // ---- helpers ----

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        return blank(s) ? null : s.trim();
    }

    private static Integer parseInt(String s, String field) {
        if (blank(s)) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " deve ser um número inteiro.");
        }
    }

    private static Long parseLong(String s, String field) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " inválida.");
        }
    }
}
