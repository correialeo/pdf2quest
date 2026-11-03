package com.pdf2questao.aula;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataOverlapTest {

    @Test
    void leNumerosBrasileirosEIgnoraTriviaisEReferenciasDeNorma() {
        Set<BigDecimal> v = DataOverlap.values("Empenho de R$ 100.000, liquidados 60.000 e pagos 40.000,50; "
                + "taxa de 2,5% ao mês, 1 ano, 100% do valor. Art. 42 da LC 101, Lei 4.320, § 2º, Súmula 7.");
        assertEquals(Set.of("100000", "60000", "40000.5", "2.5"), plain(v));
    }

    @Test
    void datasNaoSaoDado() {
        assertTrue(DataOverlap.shared("Em 31/12/2025, saldo de 7.500", "Em 31/12/2026 (ou 31/12), saldo 9.000; 12/2025")
                .isEmpty());
    }

    @Test
    void numeroColadoEmPalavraNaoEDado() {
        assertTrue(DataOverlap.values("termo12x3 e df2").isEmpty());
    }

    @Test
    void comparaValoresIguaisEscritosDeFormasDiferentes() {
        assertEquals(Set.of("100000", "60000"), plain(DataOverlap.shared("Empenho 100.000, pago 60.000",
                "Empenho de 100000 com 60000 pagos e 25.000 cancelados")));
    }

    private static Set<String> plain(Set<BigDecimal> values) {
        return values.stream().map(BigDecimal::toPlainString).collect(java.util.stream.Collectors.toSet());
    }
}
