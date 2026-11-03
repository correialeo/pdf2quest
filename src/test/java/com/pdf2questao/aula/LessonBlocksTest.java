package com.pdf2questao.aula;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LessonBlocksTest {

    static final String CALC = """
            {"titulo": "Montante", "enunciado": "Capital de R$ {C} a {taxa}% ao mês por {anos} ano(s).",
             "variaveis": {"C": {"min": 5000, "max": 50000, "passo": 1000}, "taxa": {"min": 1, "max": 3, "passo": 0.5},
                           "anos": {"min": 1, "max": 3, "passo": 1}},
             "restricoes": ["C % 2000 == 0"],
             "exemplo": {"C": 10000, "taxa": 2, "anos": 0.5},
             "etapas": [
               {"titulo": "Taxa", "pergunta": "Taxa em decimal?", "resposta": "taxa/100", "dica": "Divida por 100.",
                "errosTipicos": [{"resposta": "taxa", "mensagem": "Você usou a taxa em %."}]},
               {"titulo": "Prazo", "pergunta": "Prazo em meses?", "resposta": "anos*12", "dica": "Mesma unidade da taxa.",
                "errosTipicos": [{"resposta": "anos", "mensagem": "Taxa mensal com prazo em anos."}]},
               {"titulo": "Montante", "pergunta": "Montante?", "resposta": "C*(1+e1)^e2", "tolerancia": 1,
                "dica": "M = C(1+i)^n."}],
             "resposta": "C*(1+taxa/100)^(anos*12)"}""";

    static final String AULA = """
            === Juros compostos {#jc}
            ```formula {#jc-formula}
            {"titulo": "Montante", "formula": "M = C(1+i)^n", "simbolos": [{"simbolo": "M", "significado": "montante",
             "unidade": "R$"}], "quandoUsar": "Juros sobre juros.", "pegadinha": "Taxa e prazo em unidades diferentes."}
            ```
            ```calculo
            %s
            ```
            === SQL {#sql}
            :::dados debitos
            contribuinte,valor
            Alfa,120
            Beta,80
            :::
            ```sql run {#sql-sum}
            SELECT SUM(valor) AS total FROM debitos;
            ```

            ```saida
            total
            200
            ```
            ```tarefa python
            ### enunciado
            Some a lista.
            ### codigo
            total = 0  # TODO
            ### teste
            assert total == 6
            ### dica
            Use sum().
            ### solucao
            total = sum([1, 2, 3])
            ```
            """.formatted(CALC);

    @Test
    void separaOsBlocosComSlideConceitoESaida() {
        var parsed = LessonSlides.parseAll(AULA);
        assertEquals(List.of(), parsed.problems());
        var kinds = parsed.blocks().stream().map(LessonBlocks.Block::kind).toList();
        assertEquals(List.of(LessonBlocks.Kind.FORMULA, LessonBlocks.Kind.CALCULO, LessonBlocks.Kind.RUN,
                LessonBlocks.Kind.TAREFA), kinds);
        var run = parsed.blocks().get(2);
        assertEquals("sql", run.lang());
        assertEquals(2, run.slide());
        assertEquals("total\n200", run.expected());
        assertEquals("contribuinte,valor\nAlfa,120\nBeta,80", parsed.datasets().get("debitos"));
        assertEquals(List.of("jc", "jc-formula", "sql", "sql-sum"), List.copyOf(LessonSlides.concepts(AULA).keySet()));
        assertEquals(2, parsed.blocks().stream().filter(LessonBlocks.Block::graded).count());

        String html = String.join("", parsed.slides().stream().flatMap(s -> s.parts().stream())
                .map(LessonSlides.Part::html).toList());
        assertTrue(html.contains("class=\"lesson-box lesson-box-formula\" id=\"c-jc-formula\""), html);
        assertTrue(html.contains("data-tex=\"M = C(1+i)^n\""));
        assertTrue(html.contains("class=\"calc-block\" data-block=\"1\""));
        assertTrue(html.contains("class=\"code-run\" data-block=\"2\" data-lang=\"sql\" id=\"c-sql-sum\""));
        assertTrue(html.contains("<pre class=\"code-expected\""), "saída conferida aparece antes de rodar");
        assertTrue(html.contains("<th>contribuinte</th>"), "tabela :::dados vira tabela");
        assertTrue(html.contains("data-test=\"assert total == 6\""));
        assertTrue(!html.contains("```saida"), "o bloco saida não aparece cru");
        assertEquals("Some a lista.", LessonBlocks.task(parsed.blocks().get(3).body()).enunciado());
    }

    @Test
    void saidaSemBlocoRunEProblema() {
        var parsed = LessonSlides.parseAll("=== A {#a}\n```saida\nx\n```\n=== B {#b}\n```calculo\n{}");
        assertEquals(2, parsed.problems().size(), parsed.problems().toString());
    }

    @Test
    void geradorValidoNuncaRepeteOExemplo() {
        LessonBlocks.Calc c = LessonBlocks.calc(CALC);
        LessonBlocks.validate(c, 1, new Random(7));
        Random r = new Random(1);
        for (int i = 0; i < 200; i++) {
            var vars = LessonBlocks.sample(c, r);
            assertNotEquals(10000.0, vars.get("C"));
            assertNotEquals(2.0, vars.get("taxa"));
            assertEquals(0, vars.get("C") % 2000, "restrição respeitada");
        }
    }

    @Test
    void geradorQueNaoFechaERecusado() {
        var naoFecha = assertThrows(IllegalArgumentException.class, () -> LessonBlocks.validate(
                LessonBlocks.calc(CALC.replace("\"resposta\": \"C*(1+taxa/100)^(anos*12)\"",
                        "\"resposta\": \"C*(1+taxa/100*anos*12)\"")), 3, new Random(1)));
        assertTrue(naoFecha.getMessage().contains("não fecha com a última etapa"), naoFecha.getMessage());
        assertTrue(naoFecha.getMessage().startsWith("Exercício guiado do slide 3"));

        var impossivel = assertThrows(IllegalArgumentException.class, () -> LessonBlocks.validate(
                LessonBlocks.calc(CALC.replace("C % 2000 == 0", "C > 90000")), 1, new Random(1)));
        assertTrue(impossivel.getMessage().contains("nenhuma combinação"), impossivel.getMessage());

        var erroIgual = assertThrows(IllegalArgumentException.class, () -> LessonBlocks.validate(
                LessonBlocks.calc(CALC.replace("{\"resposta\": \"anos\"", "{\"resposta\": \"anos*12\"")), 1, new Random(1)));
        assertTrue(erroIgual.getMessage().contains("erro típico dá o mesmo valor"), erroIgual.getMessage());

        var semExemplo = assertThrows(IllegalArgumentException.class, () -> LessonBlocks.validate(
                LessonBlocks.calc(CALC.replace("\"exemplo\": {\"C\": 10000, \"taxa\": 2, \"anos\": 0.5},", "")), 1,
                new Random(1)));
        assertTrue(semExemplo.getMessage().contains("exemplo"), semExemplo.getMessage());

        var variavelErrada = assertThrows(IllegalArgumentException.class, () -> LessonBlocks.validate(
                LessonBlocks.calc(CALC.replace("\"C*(1+e1)^e2\"", "\"K*(1+e1)^e2\"")), 1, new Random(1)));
        assertTrue(variavelErrada.getMessage().contains("variável desconhecida \"K\""), variavelErrada.getMessage());
    }

    @Test
    void cartaoDeFormulaIncompletoERecusado() {
        var e = assertThrows(IllegalArgumentException.class, () -> LessonBlocks.validate(
                LessonBlocks.formula("{\"titulo\": \"x\", \"formula\": \"a\", \"simbolos\": []}"), 2));
        assertTrue(e.getMessage().contains("simbolos, quandoUsar, pegadinha"), e.getMessage());
    }
}
