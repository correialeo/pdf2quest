package com.pdf2questao.simulado.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EditalTopicRepository extends JpaRepository<EditalTopic, Long> {

    List<EditalTopic> findByScoringProfileIdOrderByPosition(Long profileId);

    List<EditalTopic> findByScoringProfileIdAndSubjectOrderByPosition(Long profileId, String subject);

    void deleteByScoringProfileIdAndSubject(Long profileId, String subject);
}
