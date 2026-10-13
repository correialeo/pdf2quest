package com.pdf2questao.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StudySessionRepository extends JpaRepository<StudySession, Long> {
    List<StudySession> findByFinishedAtIsNotNullOrderByStartedAtDesc();

    Optional<StudySession> findFirstByBlockIdAndFinishedAtIsNullOrderByStartedAtDesc(Long blockId);
}
