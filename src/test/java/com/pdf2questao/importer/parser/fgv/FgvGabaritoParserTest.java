package com.pdf2questao.importer.parser.fgv;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FgvGabaritoParserTest {

    private final FgvGabaritoParser parser = new FgvGabaritoParser();

    @Test
    void parseiaTabelaComLetrasEmUmaUnicaLinhaPorBloco() {
        String texto = """
                ATI - DESENVOLVIMENTO DE SOFTWARE – PROVA TIPO 1
                1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20
                E D C C A D C D A D E A B B E C D E D A
                21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40
                B E B C C B B D C A B A D C E D A B E C
                41 42 43 44 45 46 47 48 49 50 51 52 53 54 55 56 57 58 59 60
                A C D E A B A B C B B D A B B C E B D D
                61 62 63 64 65 66 67 68 69 70
                C B A C C C D B B B

                ATI - DESENVOLVIMENTO DE SOFTWARE – PROVA TIPO 2
                1 2 3
                A A A
                """;

        Map<Integer, String> answers = parser.parse(texto, "ATI - DESENVOLVIMENTO DE SOFTWARE – PROVA TIPO 1");

        assertEquals(70, answers.size());
        assertEquals("E", answers.get(1));
        assertEquals("A", answers.get(20));
        assertEquals("A", answers.get(41));
        assertEquals("B", answers.get(70));
        assertTrue(!answers.containsKey(71));
    }

    @Test
    void parseiaTabelaComUmaLetraPorLinha() {
        String texto = """
                ATI - ADVOCACIA - PROVA TIPO 1
                1 2 3
                A
                B
                C
                """;

        Map<Integer, String> answers = parser.parse(texto, "ATI - ADVOCACIA - PROVA TIPO 1");

        assertEquals(3, answers.size());
        assertEquals("A", answers.get(1));
        assertEquals("B", answers.get(2));
        assertEquals("C", answers.get(3));
    }

    @Test
    void tituloNaoEncontradoRetornaMapaVazio() {
        String texto = """
                ATI - ADVOCACIA - PROVA TIPO 1
                1 2 3
                A B C
                """;

        Map<Integer, String> answers = parser.parse(texto, "PERFIL INEXISTENTE");

        assertTrue(answers.isEmpty());
    }

    @Test
    void normalizaVariacoesDeTracoEspacoECaixaAoBuscarTitulo() {
        String texto = """
                ati   -   desenvolvimento de software - prova tipo 1
                1 2
                A B
                """;

        Map<Integer, String> answers = parser.parse(texto, "ATI - DESENVOLVIMENTO DE SOFTWARE – PROVA TIPO 1");

        assertEquals(2, answers.size());
        assertEquals("A", answers.get(1));
        assertEquals("B", answers.get(2));
    }
}
