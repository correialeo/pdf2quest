package com.pdf2questao.concurso;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConcursoRepository extends JpaRepository<Concurso, Long> {

    List<Concurso> findByArquivadoEmIsNullOrderByDataProvaAscNomeAsc();

    List<Concurso> findAllByOrderByArquivadoEmAscDataProvaAscNomeAsc();
}
