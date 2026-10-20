package com.pdf2questao.discursiva;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EssayRepository extends JpaRepository<Essay, Long> {

    List<Essay> findAllByOrderByCreatedAtDesc();
}
