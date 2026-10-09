package com.pdf2questao.question;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuestionImageRepository extends JpaRepository<QuestionImage, Long> {

    @Modifying
    @Query("DELETE FROM QuestionImage e WHERE e.importJobId = :importJobId")
    void deleteByImportJobId(@Param("importJobId") Long importJobId);
}
