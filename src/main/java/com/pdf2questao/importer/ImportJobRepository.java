package com.pdf2questao.importer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {

    List<ImportJob> findAllByOrderByStartedAtDesc();
}
