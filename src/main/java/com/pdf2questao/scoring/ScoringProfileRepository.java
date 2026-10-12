package com.pdf2questao.scoring;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ScoringProfileRepository extends JpaRepository<ScoringProfile, Long> {

    Optional<ScoringProfile> findByName(String name);
}
