package com.pdf2questao.block;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangeExpressionTest {

    @Test
    void leFaixasENumerosSoltos() {
        RangeExpression r = RangeExpression.parse("1-10, 32-46, 71-100");

        assertTrue(r.contains(1));
        assertTrue(r.contains(10));
        assertFalse(r.contains(11));
        assertTrue(r.contains(46));
        assertFalse(r.contains(47));
        assertTrue(r.contains(100));
        assertEquals(10 + 15 + 30, r.numbers().size());
        assertEquals("1-10, 32-46, 71-100", r.toString());
        assertEquals(new TreeSet<>(List.of(5, 8)), RangeExpression.parse("5; 8").numbers());
    }

    @Test
    void ignoraEspacosEmQualquerLugar() {
        assertEquals("1-3, 7", RangeExpression.parse("  1 -  3 ,7  ").toString());
        assertEquals("1-3", RangeExpression.parse("1\t-\n3").toString());
        assertEquals("2-4", RangeExpression.parse("2–4").toString());
    }

    @Test
    void sobreposicaoRepeticaoEAdjacenciaViramUmaFaixaSo() {
        assertEquals("1-15", RangeExpression.parse("1-10, 5-15").toString());
        assertEquals("1-10", RangeExpression.parse("1-5, 6-10").toString());
        assertEquals("3", RangeExpression.parse("3, 3, 3").toString());
        assertEquals("1-20", RangeExpression.parse("10-20, 1-12").toString());
        assertEquals(20, RangeExpression.parse("10-20, 1-12").numbers().size());
    }

    @Test
    void emBrancoOuNuloEVazioMasValido() {
        assertTrue(RangeExpression.parse(null).isEmpty());
        assertTrue(RangeExpression.parse("   ").isEmpty());
        assertFalse(RangeExpression.parse("   ").contains(1));
    }

    @Test
    void entradasInvalidasLancamErroComMensagemUtil() {
        for (String invalid : new String[]{"abc", "1-", "-5", "1-2-3", "0", "0-5", "5-1", "1,,2", "1,", ",1",
                "1.5", "10000", "1-a", "#3"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> RangeExpression.parse(invalid), "deveria rejeitar '" + invalid + "'");
            assertFalse(e.getMessage().isBlank());
        }
        assertTrue(assertThrows(IllegalArgumentException.class, () -> RangeExpression.parse("5-1"))
                .getMessage().contains("invertida"));
    }
}
