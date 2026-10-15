package com.pdf2questao.study;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InboxNoteRepository extends JpaRepository<InboxNote, Long> {

    List<InboxNote> findAllByOrderByCreatedAtDesc();
}
