package com.concursos.study.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StudySessionRepository extends JpaRepository<StudySession, Long> {
    List<StudySession> findByFinishedAtIsNotNullOrderByStartedAtDesc();
}
