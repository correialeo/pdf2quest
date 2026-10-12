package com.pdf2questao.importer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Mapa de secoes (disciplinas) por faixa de numero de questao, informado na importacao.
 * Formato JSON, uma lista de faixas (a coluna {@code topic} e opcional):
 * <pre>[{"from": 1, "to": 31, "subject": "Lingua Portuguesa"},
 *  {"from": 32, "to": 36, "subject": "Direito", "topic": "Civil"}]</pre>
 * Varias faixas podem repetir o mesmo subject com topics diferentes (assunto dentro da disciplina),
 * mas as faixas nao podem se sobrepor. O mapa informado tem prioridade sobre os titulos de
 * secao que o parser detecta no texto.
 */
public final class SectionMap {

    public record Section(int from, int to, String subject, String topic) {

        public boolean contains(int number) {
            return number >= from && number <= to;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SectionMap EMPTY = new SectionMap(List.of());

    private final List<Section> sections;

    private SectionMap(List<Section> sections) {
        this.sections = sections;
    }

    public static SectionMap empty() {
        return EMPTY;
    }

    public static SectionMap of(List<Section> sections) {
        List<Section> sorted = new ArrayList<>(sections);
        sorted.sort(Comparator.comparingInt(Section::from));
        for (int i = 0; i < sorted.size(); i++) {
            Section s = sorted.get(i);
            if (s.from() > s.to()) {
                throw new IllegalArgumentException("Faixa invalida " + s.from() + "-" + s.to() + " em '" + s.subject() + "'.");
            }
            if (i > 0 && sorted.get(i - 1).to() >= s.from()) {
                Section previous = sorted.get(i - 1);
                throw new IllegalArgumentException("As faixas de '" + previous.subject() + "' (" + previous.from()
                        + "-" + previous.to() + ") e '" + s.subject() + "' (" + s.from() + "-" + s.to() + ") se sobrepoem.");
            }
        }
        return new SectionMap(List.copyOf(sorted));
    }

    /** @throws IllegalArgumentException com mensagem pronta para mostrar ao usuario. */
    public static SectionMap parse(String json) {
        if (json == null || json.isBlank()) {
            return EMPTY;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Mapa de secoes nao e um JSON valido: " + e.getOriginalMessage());
        }
        if (!root.isArray()) {
            throw new IllegalArgumentException("Mapa de secoes deve ser uma lista de {from, to, subject}.");
        }
        List<Section> sections = new ArrayList<>();
        for (JsonNode node : root) {
            if (!node.hasNonNull("from") || !node.hasNonNull("to") || !node.hasNonNull("subject")
                    || node.get("subject").asText().isBlank()) {
                throw new IllegalArgumentException("Cada faixa do mapa precisa de from, to e subject: " + node);
            }
            sections.add(new Section(node.get("from").asInt(), node.get("to").asInt(),
                    node.get("subject").asText().trim(),
                    node.hasNonNull("topic") ? node.get("topic").asText().trim() : null));
        }
        return of(sections);
    }

    public boolean isEmpty() {
        return sections.isEmpty();
    }

    public List<Section> sections() {
        return sections;
    }

    /** Faixa que cobre a questao, se houver. */
    public Optional<Section> find(int number) {
        return sections.stream().filter(s -> s.contains(number)).findFirst();
    }
}
