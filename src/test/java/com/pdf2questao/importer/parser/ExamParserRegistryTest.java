package com.pdf2questao.importer.parser;

import com.pdf2questao.importer.Banca;
import com.pdf2questao.importer.parser.fgv.FgvPdfParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExamParserRegistryTest {

    @Test
    void encontraParserRegistradoPelaBanca() {
        FgvPdfParser fgvParser = new FgvPdfParser();
        ExamParserRegistry registry = new ExamParserRegistry(List.of(fgvParser));

        assertTrue(registry.find(Banca.FGV).isPresent());
        assertEquals(fgvParser, registry.find(Banca.FGV).get());
    }

    @Test
    void retornaVazioParaBancaSemParser() {
        ExamParserRegistry registry = new ExamParserRegistry(List.of(new FgvPdfParser()));

        assertFalse(registry.find(Banca.CESGRANRIO).isPresent());
    }

    @Test
    void supportedBancasListaApenasBancasComParser() {
        ExamParserRegistry registry = new ExamParserRegistry(List.of(new FgvPdfParser()));

        assertEquals(Set.of(Banca.FGV), registry.supportedBancas());
    }
}
