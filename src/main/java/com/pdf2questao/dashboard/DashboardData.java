package com.pdf2questao.dashboard;

import com.pdf2questao.session.StudySession;

import java.util.List;

public record DashboardData(
        long totalQuestions,
        long resolved,
        long pending,
        long correct,
        long wrong,
        double percentGeral,
        List<SubjectStat> bySubject,
        List<SubjectStat> byTopic,
        List<StudySession> history,
        Double latestNotaDataprev
) {
}
