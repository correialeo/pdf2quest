package com.pdf2questao.scoring;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ScoringRuleRepository extends JpaRepository<ScoringRule, Long> {

    List<ScoringRule> findByGroupIdInOrderByPosition(Collection<Long> groupIds);

    void deleteByGroupIdIn(Collection<Long> groupIds);
}
