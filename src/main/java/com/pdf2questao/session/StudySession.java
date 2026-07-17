package com.pdf2questao.session;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class StudySession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private SessionMode mode;

    @Lob
    private String questionIdsOrder;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    private int correctCount;
    private int wrongCount;
    private Double percent;
    private Double notaDataprev;
    private Long totalElapsedMillis;

    public java.util.List<Long> questionIds() {
        if (questionIdsOrder == null || questionIdsOrder.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(questionIdsOrder.split(","))
                .map(Long::parseLong)
                .toList();
    }
}
