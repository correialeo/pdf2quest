package com.pdf2questao.question;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PassageRepository extends JpaRepository<Passage, Long> {

    @Modifying
    @Query("DELETE FROM Passage e WHERE e.importJobId = :importJobId")
    void deleteByImportJobId(@Param("importJobId") Long importJobId);
}
