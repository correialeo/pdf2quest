package com.concursos.study.importer.parser;

import com.concursos.study.importer.Banca;
import com.concursos.study.importer.parser.fgv.FgvGabaritoParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GabaritoParserRegistryTest {

    @Test
    void encontraParserRegistradoPelaBanca() {
        FgvGabaritoParser fgvParser = new FgvGabaritoParser();
        GabaritoParserRegistry registry = new GabaritoParserRegistry(List.of(fgvParser));

        assertTrue(registry.find(Banca.FGV).isPresent());
        assertEquals(fgvParser, registry.find(Banca.FGV).get());
    }

    @Test
    void retornaVazioParaBancaSemParser() {
        GabaritoParserRegistry registry = new GabaritoParserRegistry(List.of(new FgvGabaritoParser()));

        assertFalse(registry.find(Banca.CESGRANRIO).isPresent());
    }

    @Test
    void supportedBancasListaApenasBancasComParser() {
        GabaritoParserRegistry registry = new GabaritoParserRegistry(List.of(new FgvGabaritoParser()));

        assertEquals(Set.of(Banca.FGV), registry.supportedBancas());
    }
}
