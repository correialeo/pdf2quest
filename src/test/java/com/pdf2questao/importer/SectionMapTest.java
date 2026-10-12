package com.pdf2questao.importer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionMapTest {

    @Test
    void encontraAFaixaDeCadaQuestao() {
        SectionMap map = SectionMap.parse("""
                [{"from": 1, "to": 31, "subject": "Língua Portuguesa"},
                 {"from": 32, "to": 46, "subject": "Matemática", "topic": "Juros"}]""");

        assertEquals("Língua Portuguesa", map.find(1).orElseThrow().subject());
        assertEquals("Língua Portuguesa", map.find(31).orElseThrow().subject());
        assertEquals("Matemática", map.find(32).orElseThrow().subject());
        assertEquals("Juros", map.find(46).orElseThrow().topic());
        assertTrue(map.find(47).isEmpty());
    }

    @Test
    void vazioOuNuloNaoTemFaixas() {
        assertTrue(SectionMap.parse(null).isEmpty());
        assertTrue(SectionMap.parse("  ").isEmpty());
    }

    @Test
    void rejeitaJsonInvalidoFaixaIncompletaInvertidaOuSobreposta() {
        assertThrows(IllegalArgumentException.class, () -> SectionMap.parse("{nao e json"));
        assertThrows(IllegalArgumentException.class, () -> SectionMap.parse("{\"from\": 1}"));
        assertThrows(IllegalArgumentException.class, () -> SectionMap.parse("[{\"from\": 1, \"to\": 5}]"));
        assertThrows(IllegalArgumentException.class,
                () -> SectionMap.parse("[{\"from\": 9, \"to\": 5, \"subject\": \"A\"}]"));
        IllegalArgumentException overlap = assertThrows(IllegalArgumentException.class, () -> SectionMap.parse(
                "[{\"from\": 1, \"to\": 10, \"subject\": \"A\"}, {\"from\": 10, \"to\": 20, \"subject\": \"B\"}]"));
        assertTrue(overlap.getMessage().contains("sobrepoem"));
    }
}
