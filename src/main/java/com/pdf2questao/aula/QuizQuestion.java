package com.pdf2questao.aula;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Pergunta de quiz de uma aula. Separada das questoes de prova/simulado (metricas proprias). Cada alternativa traz a
 * explicacao de por que esta certa ou errada, mostrada ao clicar nela.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class QuizQuestion {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long lessonId;
    private Integer position;
    private String subject;
    private String topic;
    @Lob
    private String statement;
    /** JSON: [{"letra": "A", "texto": "...", "explicacao": "..."}]. */
    @Lob
    private String alternatives;
    private String correctAnswer;
    private LocalDateTime createdAt = LocalDateTime.now();

    public record Alternative(String letra, String texto, String explicacao) {
    }

    public List<Alternative> alternativeList() {
        try {
            return MAPPER.readValue(alternatives, new TypeReference<List<Alternative>>() { });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    public void setAlternativeList(List<Alternative> list) {
        try {
            this.alternatives = MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
