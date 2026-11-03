package com.pdf2questao.aula;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalcExprTest {

    static final Map<String, Double> VARS = Map.of("C", 8000.0, "taxa", 3.0, "anos", 2.0, "e1", 0.03);

    /** Expressoes cobrindo a gramatica toda; o JS (calc-expr.js) tem de dar o mesmo valor. */
    static final List<String> EXPRS = List.of("C*(1+taxa/100)^(anos*12)", "-2^2", "2^3^2", "10 % 4 + 7/2",
            "round(1234.5678, 2)", "round(-2.5)", "ln(exp(2)) + log10(1000) + sqrt(16) + abs(-3)", "floor(2.7) + ceil(2.1)",
            "min(3, 1, 2) + max(4, 9) + pow(2, 10)", "C > 5000 && taxa <= 3 || 0", "!(taxa == 3) + (anos != 2)",
            "1.5e3 + .5", "(1+e1)^12-1");

    @Test
    void avaliaAGramaticaToda() {
        assertEquals(8000 * Math.pow(1.03, 24), CalcExpr.eval(EXPRS.get(0), VARS), 1e-9);
        assertEquals(-4, CalcExpr.eval("-2^2", VARS));
        assertEquals(512, CalcExpr.eval("2^3^2", VARS), "potência associa à direita");
        assertEquals(5.5, CalcExpr.eval("10 % 4 + 7/2", VARS));
        assertEquals(1234.57, CalcExpr.eval("round(1234.5678, 2)", VARS), 1e-9);
        assertEquals(1, CalcExpr.eval("C > 5000 && taxa <= 3 || 0", VARS));
    }

    @Test
    void errosDizemOQueFaltou() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> CalcExpr.eval("x + 1", VARS)).getMessage()
                .contains("variável desconhecida \"x\""));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> CalcExpr.eval("(1 + 2", VARS)).getMessage()
                .contains("falta \")\""));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> CalcExpr.eval("foo(1)", VARS)).getMessage()
                .contains("função desconhecida"));
        assertThrows(IllegalArgumentException.class, () -> CalcExpr.eval("1 2", VARS));
    }

    /** Mesma gramatica no navegador: roda calc-expr.js no Node e compara com o Java (pula sem Node). */
    @Test
    void javascriptDaOMesmoValor() throws Exception {
        Process which;
        try {
            which = new ProcessBuilder("node", "--version").start();
            which.waitFor(10, TimeUnit.SECONDS);
        } catch (java.io.IOException e) {
            Assumptions.abort("Node não instalado");
            return;
        }
        Path js = Path.of("src/main/resources/static/js/calc-expr.js").toAbsolutePath();
        String vars = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(VARS);
        String exprs = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(EXPRS);
        String script = "const E=require(" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(js.toString())
                + ");const v=" + vars + ";process.stdout.write(JSON.stringify(" + exprs + ".map(e=>E.evaluate(e,v))))";
        Process p = new ProcessBuilder("node", "-e", script).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        double[] fromJs = new com.fasterxml.jackson.databind.ObjectMapper().readValue(out, double[].class);
        for (int i = 0; i < EXPRS.size(); i++) {
            assertEquals(CalcExpr.eval(EXPRS.get(i), VARS), fromJs[i], 1e-9, EXPRS.get(i));
        }
    }
}
