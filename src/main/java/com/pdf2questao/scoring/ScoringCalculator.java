package com.pdf2questao.scoring;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Calcula a nota de uma sessao a partir de um perfil de pontuacao. Funcao pura: nao conhece banco
 * nem entidades. Cada questao cai na primeira regra (na ordem do perfil) cujo {@code subject} ou
 * apelido bate com a disciplina da questao, ignorando acentos e caixa. Questao anulada conta como
 * acerto para todos, respondida ou nao. Erro nao desconta. Disciplinas sem regra nao pontuam e
 * saem em {@link ScoreResult#unweightedSubjects()}.
 */
public final class ScoringCalculator {

    private ScoringCalculator() {
    }

    public record RuleSpec(String subject, Set<String> aliases, double weight, Integer questionCount) {
    }

    public record GroupSpec(String name, Double minScore, List<RuleSpec> rules) {
    }

    public record ProfileSpec(String name, Double minTotal, List<GroupSpec> groups) {
    }

    /** Uma questao da sessao: disciplina, se acertou (null = sem resposta) e se foi anulada. */
    public record Item(String subject, Boolean correct, boolean annulled) {
    }

    public record RuleResult(String subject, double weight, int questions, int correct, double points,
                             double maxPoints) {
    }

    public record GroupResult(String name, double points, double maxPoints, Double minScore, boolean minMet,
                              List<RuleResult> rules) {
    }

    public record ScoreResult(String profileName, double total, double maxTotal, Double minTotal,
                              boolean minTotalMet, boolean passed, List<GroupResult> groups,
                              Map<String, Integer> unweightedSubjects) {
    }

    public static ScoreResult calculate(ProfileSpec profile, List<Item> items) {
        List<int[]> counts = new ArrayList<>();
        List<RuleSpec> flat = new ArrayList<>();
        for (GroupSpec group : profile.groups()) {
            for (RuleSpec rule : group.rules()) {
                flat.add(rule);
                counts.add(new int[2]);
            }
        }
        Map<String, Integer> unweighted = new LinkedHashMap<>();
        for (Item item : items) {
            int rule = findRule(flat, item.subject());
            if (rule < 0) {
                unweighted.merge(item.subject() == null || item.subject().isBlank() ? "(sem disciplina)" : item.subject(),
                        1, Integer::sum);
                continue;
            }
            counts.get(rule)[0]++;
            if (item.annulled() || Boolean.TRUE.equals(item.correct())) {
                counts.get(rule)[1]++;
            }
        }

        List<GroupResult> groups = new ArrayList<>();
        double total = 0;
        double maxTotal = 0;
        boolean allGroupsMet = true;
        int index = 0;
        for (GroupSpec group : profile.groups()) {
            List<RuleResult> rules = new ArrayList<>();
            double points = 0;
            double max = 0;
            for (RuleSpec rule : group.rules()) {
                int[] c = counts.get(index++);
                double rulePoints = c[1] * rule.weight();
                double ruleMax = (rule.questionCount() != null ? rule.questionCount() : c[0]) * rule.weight();
                rules.add(new RuleResult(rule.subject(), rule.weight(), c[0], c[1], rulePoints, ruleMax));
                points += rulePoints;
                max += ruleMax;
            }
            boolean minMet = group.minScore() == null || points >= group.minScore();
            allGroupsMet &= minMet;
            groups.add(new GroupResult(group.name(), points, max, group.minScore(), minMet, rules));
            total += points;
            maxTotal += max;
        }
        boolean minTotalMet = profile.minTotal() == null || total >= profile.minTotal();
        return new ScoreResult(profile.name(), total, maxTotal, profile.minTotal(), minTotalMet,
                allGroupsMet && minTotalMet, groups, unweighted);
    }

    private static int findRule(List<RuleSpec> rules, String subject) {
        if (subject == null || subject.isBlank()) {
            return -1;
        }
        String wanted = normalize(subject);
        for (int i = 0; i < rules.size(); i++) {
            RuleSpec rule = rules.get(i);
            if (normalize(rule.subject()).equals(wanted)
                    || rule.aliases().stream().anyMatch(a -> normalize(a).equals(wanted))) {
                return i;
            }
        }
        return -1;
    }

    public static String normalize(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
