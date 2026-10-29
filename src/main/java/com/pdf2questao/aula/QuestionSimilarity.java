package com.pdf2questao.aula;

import com.pdf2questao.scoring.ScoringCalculator;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Detecta pergunta de quiz repetida: o mesmo enunciado (ou quase) com as mesmas alternativas, mesmo que reescrito de
 * leve. Compara conjuntos de palavras (Jaccard), sem depender da ordem nem da pontuacao.
 */
final class QuestionSimilarity {

    /** Enunciado + alternativas: acima disto a pergunta e tratada como a mesma. */
    static final double FULL_THRESHOLD = 0.6;
    /** So o enunciado (quando tem texto suficiente): as alternativas podem ter sido trocadas, o ponto e o mesmo. */
    static final double STATEMENT_THRESHOLD = 0.7;
    /** Texto com menos palavras que isto so conta como repetido se for identico (itens curtos do Certo/Errado). */
    private static final int MIN_TOKENS = 6;

    private QuestionSimilarity() {
    }

    /** Palavras com 4+ letras e numeros (as pequenas, como "de" e "que", nao distinguem uma pergunta da outra). */
    static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        for (String t : ScoringCalculator.normalize(text).split("[^a-z0-9]+")) {
            if (t.length() >= 4 || !t.isEmpty() && Character.isDigit(t.charAt(0))) {
                out.add(t);
            }
        }
        return out;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> common = new HashSet<>(a);
        common.retainAll(b);
        return common.size() / (double) (a.size() + b.size() - common.size());
    }

    /** Pergunta ja reduzida as palavras que a distinguem. */
    record Fingerprint(Set<String> statement, Set<String> full) {

        static Fingerprint of(String statement, List<String> alternatives) {
            Set<String> s = tokens(statement);
            Set<String> full = new HashSet<>(s);
            if (alternatives != null) {
                alternatives.forEach(a -> full.addAll(tokens(a)));
            }
            return new Fingerprint(s, full);
        }

        boolean repeats(Fingerprint other) {
            if (full.isEmpty() || other.full.isEmpty()) {
                return false;
            }
            if (full.equals(other.full)) {
                return true;
            }
            return full.size() >= MIN_TOKENS && other.full.size() >= MIN_TOKENS
                    && (jaccard(full, other.full) >= FULL_THRESHOLD
                    || statement.size() >= MIN_TOKENS && other.statement.size() >= MIN_TOKENS
                    && jaccard(statement, other.statement) >= STATEMENT_THRESHOLD);
        }
    }
}
