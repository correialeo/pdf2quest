package com.pdf2questao.concurso;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CronogramaRepository extends JpaRepository<Cronograma, Long> {

    List<Cronograma> findAllByOrderByAtivoDescCreatedAtDesc();

    List<Cronograma> findByConcursoIdOrderByAtivoDescCreatedAtDesc(Long concursoId);

    @Modifying
    @Query("UPDATE Cronograma c SET c.concursoId = NULL WHERE c.concursoId = :concursoId")
    int unlinkConcurso(Long concursoId);
}
