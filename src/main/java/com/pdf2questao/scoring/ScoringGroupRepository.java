package com.pdf2questao.scoring;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScoringGroupRepository extends JpaRepository<ScoringGroup, Long> {

    List<ScoringGroup> findByProfileIdOrderByPosition(Long profileId);

    void deleteByProfileId(Long profileId);
}
