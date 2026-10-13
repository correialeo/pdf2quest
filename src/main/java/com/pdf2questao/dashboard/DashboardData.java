package com.pdf2questao.dashboard;

import com.pdf2questao.block.StudyBlock;
import com.pdf2questao.importer.ImportJob;
import com.pdf2questao.session.StudySession;

import java.time.LocalDateTime;
import java.util.List;

/** Dados das telas do dashboard: visao geral (cartoes por prova e por bloco) e paineis de uma prova/bloco. */
public final class DashboardData {

    private DashboardData() {
    }

    /** Percentual de acertos entre as questoes corrigidas (0 se nenhuma). */
    static double percent(long correct, long wrong) {
        return (correct + wrong) == 0 ? 0 : (correct * 100.0) / (correct + wrong);
    }

    public record ProvaCard(ImportJob job, long totalQuestions, long answered, long correct, long wrong,
                            long sessions, LocalDateTime lastSession) {

        public double percent() {
            return DashboardData.percent(correct, wrong);
        }

        public double coverage() {
            return totalQuestions == 0 ? 0 : (answered * 100.0) / totalQuestions;
        }

        public boolean hasAttempts() {
            return correct + wrong > 0;
        }
    }

    public record ProvaGroup(String banca, List<ProvaCard> provas) {
    }

    public record BlockCard(StudyBlock block, int questions, long sessions, Double lastScore, Boolean lastPassed,
                            Double bestScore, LocalDateTime lastSession) {
    }

    public record SessionRow(StudySession session, long correct, long wrong) {

        public double percent() {
            return DashboardData.percent(correct, wrong);
        }

        public String label() {
            return session.getBlockName() != null ? session.getBlockName() : session.getMode().name();
        }
    }

    public record Overview(long provas, long totalQuestions, long answered, long correct, long wrong,
                           List<ProvaGroup> groups, List<BlockCard> blocks, List<SessionRow> recent,
                           List<SubjectStat> bySubject) {

        public double percent() {
            return DashboardData.percent(correct, wrong);
        }
    }

    /** Disciplina de uma prova: quantas questoes tem, quantas foram respondidas e o desempenho. */
    public record SubjectProgress(String label, long questions, long answered, long correct, long wrong) {

        public double percent() {
            return DashboardData.percent(correct, wrong);
        }

        public double coverage() {
            return questions == 0 ? 0 : (answered * 100.0) / questions;
        }
    }

    public record ProvaPanel(ImportJob job, long totalQuestions, long answered, long correct, long wrong,
                             long errors, long needsReview, List<SubjectProgress> subjects,
                             List<SubjectStat> topics, List<SessionRow> sessions) {

        public double percent() {
            return DashboardData.percent(correct, wrong);
        }

        public double coverage() {
            return totalQuestions == 0 ? 0 : (answered * 100.0) / totalQuestions;
        }
    }

    public record BlockSession(StudySession session, com.pdf2questao.scoring.ScoringCalculator.ScoreResult score) {
    }

    public record BlockPanel(StudyBlock block, int questions, String profileName, List<BlockSession> sessions) {
    }
}
