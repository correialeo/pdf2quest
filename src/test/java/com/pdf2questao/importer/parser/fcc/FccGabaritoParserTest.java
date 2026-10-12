package com.pdf2questao.importer.parser.fcc;

import com.pdf2questao.importer.parser.GabaritoEntries;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FccGabaritoParserTest {

    private final FccGabaritoParser parser = new FccGabaritoParser();

    private static final String GABARITO = """
            3/3/26, 10:23 PM about:blank

            SECRETARIA DA FAZENDA E PLANEJAMENTO DO ESTADO DE SÃO PAULO
            Cargo: B - AUD FISCAL REC ESTADUAL - TI E COMUNICAÇÃO. Tipo de Gabarito: 1
            Data de Publicação de Gabarito: 02/03/2026 17:00:00
             Questão   Resposta   Situação
             1    C    Gabarito sem alteração
             2    A    Gabarito sem alteração
             3    D    Gabarito alterado
            about:blank 6/13
            3/3/26, 10:23 PM about:blank
             Questão   Resposta   Situação
             4    ANULADA    Questão anulada
             5    E    Gabarito sem alteração
            Total de questões: 5
            """;

    @Test
    void leTabelaIgnorandoRuidoDoNavegador() {
        GabaritoEntries entries = parser.parseEntries(GABARITO, "");

        assertEquals(Map.of(1, "C", 2, "A", 3, "D", 5, "E"), entries.answers());
        assertEquals(5, entries.declaredTotal());
        assertEquals(5, entries.totalRead());
        assertTrue(entries.warnings().isEmpty(), entries.warnings().toString());
    }

    @Test
    void questaoAnuladaFicaMarcadaENaoEDescartada() {
        GabaritoEntries entries = parser.parseEntries(GABARITO, "");

        assertEquals(Set.of(4), entries.annulled());
        assertTrue(!entries.answers().containsKey(4));
    }

    @Test
    void gabaritoAlteradoFicaComALetraNovaEMarcado() {
        GabaritoEntries entries = parser.parseEntries(GABARITO, "");

        assertEquals(Set.of(3), entries.changed());
        assertEquals("D", entries.answers().get(3));
    }

    @Test
    void anuladaTambemQuandoOTextoVemSoNaColunaSituacao() {
        GabaritoEntries entries = parser.parseEntries("1 A Gabarito sem alteração\n2 X Questão Anulada\nTotal de questões: 2", "");

        assertEquals(Set.of(2), entries.annulled());
    }

    @Test
    void avisaQuandoOTotalLidoNaoBateComODeclarado() {
        GabaritoEntries entries = parser.parseEntries("1 A Gabarito sem alteração\nTotal de questões: 80", "");

        assertEquals(1, entries.totalRead());
        assertEquals(1, entries.warnings().size());
        assertTrue(entries.warnings().get(0).contains("80"));
    }

    @Test
    void avisaQuandoNaoHaTotalDeclaradoEQuestaoRepetida() {
        GabaritoEntries entries = parser.parseEntries("1 A Gabarito sem alteração\n1 B Gabarito sem alteração", "");

        assertEquals(Map.of(1, "A"), entries.answers());
        assertEquals(2, entries.warnings().size());
    }

    @Test
    void parseSimplesDevolveSoAsLetras() {
        assertEquals(Map.of(1, "C", 2, "A", 3, "D", 5, "E"), parser.parse(GABARITO, null));
    }
}
