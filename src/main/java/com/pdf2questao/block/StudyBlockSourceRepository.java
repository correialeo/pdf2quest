package com.pdf2questao.block;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StudyBlockSourceRepository extends JpaRepository<StudyBlockSource, Long> {

    List<StudyBlockSource> findByBlockIdOrderByPosition(Long blockId);

    void deleteByBlockId(Long blockId);
}
