package com.pdf2questao.guess;

import com.pdf2questao.importer.ImportJob;
import com.pdf2questao.question.Question;

import java.util.List;

/** Resultado do diagnostico de chutes: totais, por disciplina e a lista de acertos no chute para estudar. */
public final class GuessDiagnostic {

    private GuessDiagnostic() {
    }

    /**
     * Contagem pela ULTIMA resposta de cada questao. "Seguro" = o usuario informou que nao foi chute;
     * {@code unknown} = resposta sem a informacao (anterior ao recurso).
     */
    public record Counts(long safeCorrect, long guessCorrect, long safeWrong, long guessWrong, long unknown) {

        public long informed() {
            return safeCorrect + guessCorrect + safeWrong + guessWrong;
        }

        public long total() {
            return informed() + unknown;
        }

        public long correct() {
            return safeCorrect + guessCorrect;
        }

        /** Acerto contando tudo que foi informado, inclusive o que acertou no chute. */
        public double rawPercent() {
            return informed() == 0 ? 0 : correct() * 100.0 / informed();
        }

        /** Acerto "de verdade": so o que acertou sabendo (os acertos no chute nao contam). */
        public double realPercent() {
            return informed() == 0 ? 0 : safeCorrect * 100.0 / informed();
        }

        /** Parcela dos acertos que foi no chute. */
        public double luckySharePercent() {
            return correct() == 0 ? 0 : guessCorrect * 100.0 / correct();
        }
    }

    public record SubjectRow(String label, Counts counts) {
    }

    public record LuckyQuestion(Question question, ImportJob job) {
    }

    public record JobOption(ImportJob job, long luckyCorrect) {
    }

    public record Report(Counts counts, List<SubjectRow> subjects, List<LuckyQuestion> lucky,
                         List<JobOption> jobs, Long selectedJob) {
    }
}
