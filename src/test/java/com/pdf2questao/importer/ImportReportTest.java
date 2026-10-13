package com.pdf2questao.importer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportReportTest {

    private static ParsedQuestion q(int number, String heading) {
        return new ParsedQuestion(number, "enunciado", "a", "b", "c", "d", "e", null, heading, null, null, 1,
                false, null, heading);
    }

    @Test
    void semMapaInformadoSugereUmMapaComOsTitulosDetectados() {
        ParseResult result = new ParseResult("FCC", 2026,
                List.of(q(1, "Língua Portuguesa"), q(2, null), q(3, null), q(4, "Matemática"), q(5, null)), List.of());

        String report = ImportReport.forExam("prova.pdf", result, SectionMap.empty(), true);

        assertTrue(report.contains("Mapa de secoes sugerido"), report);
        SectionMap suggested = SectionMap.parse(report.substring(report.indexOf("[\n"), report.indexOf("]\n") + 1));
        assertEquals("Língua Portuguesa", suggested.find(3).orElseThrow().subject());
        assertEquals("Matemática", suggested.find(4).orElseThrow().subject());
        assertEquals("Matemática", suggested.find(5).orElseThrow().subject());
    }

    @Test
    void comMapaInformadoNaoSugereOutro() {
        ParseResult result = new ParseResult("FCC", 2026, List.of(q(1, "Língua Portuguesa")), List.of());
        SectionMap map = SectionMap.parse("[{\"from\": 1, \"to\": 5, \"subject\": \"X\"}]");

        assertTrue(!ImportReport.forExam("prova.pdf", result, map, false).contains("sugerido"));
    }
}
