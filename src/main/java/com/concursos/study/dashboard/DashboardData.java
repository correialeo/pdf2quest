package com.concursos.study.dashboard;

import com.concursos.study.session.StudySession;

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
