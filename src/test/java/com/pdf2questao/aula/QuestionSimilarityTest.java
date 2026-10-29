package com.pdf2questao.aula;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionSimilarityTest {

    private static QuestionSimilarity.Fingerprint print(String statement, String... alternatives) {
        return QuestionSimilarity.Fingerprint.of(statement, List.of(alternatives));
    }

    @Test
    void mesmaPerguntaReescritaDeLeveContaComoRepetida() {
        var original = print("Em uma Sprint de um mês, quais os limites máximos da Sprint Planning e da Sprint Review?",
                "8 horas e 4 horas", "4 horas e 8 horas", "3 horas e 8 horas");
        var reescrita = print("Quais os limites máximos de duração da Sprint Planning e da Sprint Review, em uma Sprint de um mês?",
                "8 horas e 4 horas", "4 horas e 8 horas", "3 horas e 8 horas");
        assertTrue(original.repeats(reescrita));
    }

    @Test
    void enunciadoIgualComAlternativasTrocadasTambemEhRepetido() {
        var a = print("Segundo o Scrum Guide 2020, quem tem autoridade para cancelar uma Sprint antes do fim do timebox?",
                "Somente o Product Owner", "O Scrum Master");
        var b = print("Segundo o Scrum Guide 2020, quem tem autoridade para cancelar uma Sprint antes do fim do timebox?",
                "O Scrum Master e os Developers", "O patrocinador");
        assertTrue(a.repeats(b));
    }

    @Test
    void perguntaSobreOutroPontoDoMesmoAssuntoNaoEhRepetida() {
        var cancelar = print("Segundo o Scrum Guide 2020, quem tem autoridade para cancelar uma Sprint?",
                "Somente o Product Owner", "O Scrum Master");
        var daily = print("A Daily Scrum é um evento de quantos minutos, no máximo, para os Developers?", "15", "30");
        assertFalse(cancelar.repeats(daily));
    }

    @Test
    void enunciadosCurtosComAlternativasDiferentesNaoSaoRepetidos() {
        var a = print("Item 1.", "Certo", "Errado");
        var b = print("Item 2.", "Certo", "Errado");
        assertFalse(a.repeats(b), "o número do item muda e o texto curto não conta sozinho");
        assertTrue(a.repeats(print("Item 1.", "Certo", "Errado")), "texto curto idêntico, sim");
        assertFalse(print("O Scrum Master cancela a Sprint.").repeats(print("O Product Owner cancela a Sprint.")));
    }
}
