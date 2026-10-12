package com.pdf2questao.block;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StudyBlockExclusionRepository extends JpaRepository<StudyBlockExclusion, Long> {

    List<StudyBlockExclusion> findByBlockId(Long blockId);

    void deleteByBlockId(Long blockId);
}
