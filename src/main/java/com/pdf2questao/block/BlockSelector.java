package com.pdf2questao.block;

import com.pdf2questao.question.Question;
import com.pdf2questao.scoring.ScoringCalculator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Escolhe as questoes de um bloco a partir de todas as questoes do banco. Funcao pura (sem
 * repositorios), para poder testar. As origens rodam na ordem; uma questao ja escolhida por uma
 * origem anterior nao entra de novo, e exclusoes valem para o bloco todo e sao aplicadas antes do
 * limite da origem (o limite conta so questoes validas).
 */
public final class BlockSelector {

    private BlockSelector() {
    }

    public record Selection(List<Question> questions, List<Integer> perSource, int excluded,
                            List<String> warnings) {
    }

    public static Selection select(List<Question> all, List<StudyBlockSource> sources,
                                   List<StudyBlockExclusion> exclusions, Random random) {
        Set<Long> excludedIds = new HashSet<>();
        for (StudyBlockExclusion e : exclusions) {
            if (e.getQuestionId() != null) {
                excludedIds.add(e.getQuestionId());
            }
        }
        Set<StudyBlockExclusion> unmatched = new LinkedHashSet<>(exclusions);

        List<Question> chosen = new ArrayList<>();
        Set<Long> chosenIds = new HashSet<>();
        List<Integer> perSource = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<Long> excludedHit = new HashSet<>();

        for (int i = 0; i < sources.size(); i++) {
            StudyBlockSource source = sources.get(i);
            RangeExpression ranges = RangeExpression.parse(source.getRanges());
            List<Question> candidates = all.stream()
                    .filter(q -> matches(q, source, ranges))
                    .sorted(Comparator.comparing(Question::getImportJobId, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(Question::getQuestionNumber, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(Question::getId))
                    .toList();

            if (source.getImportJobId() != null && !ranges.isEmpty()) {
                Set<Integer> present = new HashSet<>();
                candidates.forEach(q -> present.add(q.getQuestionNumber()));
                TreeSet<Integer> missing = new TreeSet<>(ranges.numbers());
                missing.removeAll(present);
                if (!missing.isEmpty() && present.isEmpty()) {
                    warnings.add("Origem " + (i + 1) + ": a prova " + source.getImportJobId() + " nao tem questoes nas faixas " + ranges + ".");
                } else if (!missing.isEmpty()) {
                    warnings.add("Origem " + (i + 1) + ": faixa cita questoes que a prova nao tem: " + compact(missing) + ".");
                }
            }

            List<Question> valid = new ArrayList<>();
            for (Question q : candidates) {
                if (chosenIds.contains(q.getId())) {
                    continue;
                }
                boolean excluded = excludedIds.contains(q.getId());
                for (StudyBlockExclusion e : exclusions) {
                    if (e.getQuestionId() == null && matchesExclusion(e, q)) {
                        excluded = true;
                        unmatched.remove(e);
                    }
                }
                if (excludedIds.contains(q.getId())) {
                    exclusions.stream().filter(e -> q.getId().equals(e.getQuestionId())).forEach(unmatched::remove);
                }
                if (excluded) {
                    excludedHit.add(q.getId());
                } else {
                    valid.add(q);
                }
            }
            if (source.isShuffle()) {
                java.util.Collections.shuffle(valid, random);
            }
            if (source.getMaxQuestions() != null && source.getMaxQuestions() >= 0
                    && valid.size() > source.getMaxQuestions()) {
                valid = new ArrayList<>(valid.subList(0, source.getMaxQuestions()));
            }
            valid.forEach(q -> chosenIds.add(q.getId()));
            chosen.addAll(valid);
            perSource.add(valid.size());
        }

        for (StudyBlockExclusion e : unmatched) {
            warnings.add("Exclusao " + describe(e) + " nao bate com nenhuma questao do bloco.");
        }
        return new Selection(chosen, perSource, excludedHit.size(), warnings);
    }

    private static boolean matches(Question q, StudyBlockSource s, RangeExpression ranges) {
        if (s.getImportJobId() != null && !s.getImportJobId().equals(q.getImportJobId())) {
            return false;
        }
        if (!ranges.isEmpty() && (q.getQuestionNumber() == null || !ranges.contains(q.getQuestionNumber()))) {
            return false;
        }
        return containsText(q.getSubject(), s.getSubject())
                && containsText(q.getTopic(), s.getTopic())
                && (isBlank(s.getOrganization())
                    || ScoringCalculator.normalize(s.getOrganization()).equals(ScoringCalculator.normalize(q.getOrganization())))
                && (s.getYear() == null || s.getYear().equals(q.getYear()));
    }

    private static boolean containsText(String value, String wanted) {
        if (isBlank(wanted)) {
            return true;
        }
        return value != null && ScoringCalculator.normalize(value).contains(ScoringCalculator.normalize(wanted));
    }

    private static boolean matchesExclusion(StudyBlockExclusion e, Question q) {
        if (e.getQuestionNumber() != null) {
            return e.getQuestionNumber().equals(q.getQuestionNumber())
                    && (e.getImportJobId() == null || e.getImportJobId().equals(q.getImportJobId()));
        }
        return false;
    }

    private static String describe(StudyBlockExclusion e) {
        if (e.getQuestionId() != null) {
            return "#" + e.getQuestionId();
        }
        return (e.getImportJobId() != null ? e.getImportJobId() + ":" : "") + e.getQuestionNumber();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String compact(TreeSet<Integer> numbers) {
        return numbers.size() > 12 ? numbers.stream().limit(12).toList() + "..." : numbers.toString();
    }

    /** Contagem de questoes por disciplina, na ordem em que aparecem. */
    public static Map<String, Integer> bySubject(List<Question> questions) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Question q : questions) {
            counts.merge(q.getSubject() == null || q.getSubject().isBlank() ? "(sem disciplina)" : q.getSubject(),
                    1, Integer::sum);
        }
        return counts;
    }
}
