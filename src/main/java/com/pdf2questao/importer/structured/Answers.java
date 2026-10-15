package com.pdf2questao.importer.structured;

import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionType;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Normaliza o que o usuario escreve como resposta ("b", "(B)", "Certo", "V", "Anulada") para a letra do banco. */
public final class Answers {

    public static final String CERTO = "Certo";
    public static final String ERRADO = "Errado";

    private static final Set<String> ANNULLED = Set.of("X", "*", "ANULADA", "ANULADO", "NULA", "NULO", "ANULAR");
    private static final Map<String, String> CERTO_ERRADO = Map.of(
            "C", "C", "CERTO", "C", "CERTA", "C", "V", "C", "VERDADEIRO", "C",
            "E", "E", "ERRADO", "E", "ERRADA", "E", "F", "E", "FALSO", "E");

    private Answers() {
    }

    public static boolean isAnnulledMarker(String raw) {
        return raw != null && ANNULLED.contains(clean(raw));
    }

    /** Letra correta para a questao, ou nulo se o texto nao corresponde a nenhuma alternativa. */
    public static String normalize(String raw, QuestionType type, Set<String> letters) {
        String value = clean(raw);
        if (type == QuestionType.CERTO_ERRADO) {
            return CERTO_ERRADO.get(value);
        }
        return value.length() == 1 && letters.contains(value) ? value : null;
    }

    /** Tipo de uma questao ja gravada: o informado na importacao ou, nas de PDF, deduzido das alternativas. */
    public static QuestionType typeOf(Question q) {
        if (q.getQuestionType() != null) {
            return q.getQuestionType();
        }
        Map<String, String> alternatives = q.alternatives();
        return alternatives.size() == 2 && CERTO.equals(alternatives.get("C")) && ERRADO.equals(alternatives.get("E"))
                ? QuestionType.CERTO_ERRADO : QuestionType.MULTIPLA_ESCOLHA;
    }

    /** Maiusculas, sem acento e sem a pontuacao comum em gabaritos ("(B)", "b)", "B."). */
    static String clean(String raw) {
        String s = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.toUpperCase(Locale.ROOT).replaceAll("^[(\\[]\\s*|\\s*[)\\].:-]$", "");
        return s.trim();
    }
}
