package com.pdf2questao.mcp;

import com.pdf2questao.Pdf2QuestaoApplication;
import com.pdf2questao.mcp.LessonMcpTools.FlashcardItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;

import static com.pdf2questao.mcp.LessonMcpToolsIntegrationTest.ESTILO;
import static com.pdf2questao.mcp.LessonMcpToolsIntegrationTest.REFS;
import static com.pdf2questao.mcp.LessonMcpToolsIntegrationTest.SLIDES;
import static com.pdf2questao.mcp.LessonMcpToolsIntegrationTest.fixacao;
import static com.pdf2questao.mcp.LessonMcpToolsIntegrationTest.item;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Flashcards: criados por conceito da aula, mostrados antes das questões da revisão e avaliados na tela. */
@SpringBootTest(classes = Pdf2QuestaoApplication.class)
@AutoConfigureMockMvc
class FlashcardIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("test.db"));
        registry.add("pdf2questao.pdf-dir", () -> tempDir.resolve("pdfs").toString());
    }

    @Autowired
    LessonMcpTools tools;
    @Autowired
    MockMvc mvc;

    private static final String LINK = "[MCASP](https://www.tesourotransparente.gov.br/)";

    private static FlashcardItem card(String concept, String kind, String front, String back) {
        return new FlashcardItem(concept, kind, front, back);
    }

    private static final FlashcardItem REGRA = card("regra", "lacuna",
            "O crédito especial atende despesa ___ dotação orçamentária específica.", "sem");
    private static final FlashcardItem EXCECAO = card("excecao", "pergunta",
            "Qual crédito adicional dispensa autorização legislativa prévia?", "O extraordinário (guerra, comoção, calamidade).");

    @Test
    void cartoesSaoValidadosEAparecemAntesDasQuestoes() throws Exception {
        var aula = tools.createLesson("Créditos", "AFO", "Créditos adicionais", SLIDES, LINK, null, null, null, null,
                fixacao(), ESTILO, REFS, null);

        var falta = assertThrows(IllegalArgumentException.class, () -> tools.createFlashcards(aula.aulaId(), List.of(REGRA)));
        assertTrue(falta.getMessage().contains("faltam cartões para {#excecao} (slide 2: Exceção)"), falta.getMessage());
        var lacuna = assertThrows(IllegalArgumentException.class, () -> tools.createFlashcards(aula.aulaId(), List.of(
                card("regra", "lacuna", "O crédito especial atende despesa sem dotação.", "sem"), EXCECAO)));
        assertTrue(lacuna.getMessage().contains("lacuna precisa de \"___\""), lacuna.getMessage());
        var entrega = assertThrows(IllegalArgumentException.class, () -> tools.createFlashcards(aula.aulaId(), List.of(
                card("regra", null, "O limite prudencial é 95% do limite: qual é o percentual?", "95%"), EXCECAO)));
        assertTrue(entrega.getMessage().contains("a frente já contém a resposta"), entrega.getMessage());
        var longo = assertThrows(IllegalArgumentException.class, () -> tools.createFlashcards(aula.aulaId(), List.of(
                card("regra", null, "Explique tudo sobre créditos adicionais?", "x".repeat(300)), EXCECAO)));
        assertTrue(longo.getMessage().contains("verso com 300 caracteres"), longo.getMessage());
        var fora = assertThrows(IllegalArgumentException.class, () -> tools.createFlashcards(aula.aulaId(), List.of(
                card("nao-existe", null, "Pergunta qualquer aqui?", "resposta"), REGRA, EXCECAO)));
        assertTrue(fora.getMessage().contains("{#nao-existe} não está na aula"), fora.getMessage());

        var saved = tools.createFlashcards(aula.aulaId(), List.of(REGRA, EXCECAO));
        assertEquals(2, saved.cartoes());

        var revisao = tools.reviewFlashcards(null, "AFO", null);
        assertEquals(2, revisao.cartoes().size());
        assertEquals(1, revisao.cartoes().get(0).slide());
        List<Long> ids = revisao.cartoes().stream().map(c -> c.id()).toList();

        var quiz = tools.createQuiz(aula.aulaId(), "Revisão", null, null, null, null,
                List.of(), List.of(LessonMcpToolsIntegrationTest.item("Créditos", "A"),
                        new LessonMcpTools.QuizItem("Pergunta da exceção, termo único zeta-quiz:", "Créditos",
                                item("x", "B").alternativas(), "B", List.of("excecao"), null)),
                null, null, null, ESTILO, REFS, ids);
        mvc.perform(get("/quizzes/" + quiz.quizId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"flash-deck mb-3\"")))
                .andExpect(content().string(containsString("O crédito especial atende despesa ___")))
                .andExpect(content().string(containsString("data-concepts=\"excecao\"")))
                .andExpect(content().string(containsString("/js/flashcards.js")));

        mvc.perform(post("/quizzes/" + quiz.quizId() + "/flashcard").contentType("application/json")
                .content("{\"card\":" + ids.get(1) + ",\"rating\":\"Difícil\"}")).andExpect(status().isOk());
        var result = tools.quizResult(quiz.quizId());
        assertNull(result.flashcards().get(0).avaliacao());
        assertEquals("dificil", result.flashcards().get(1).avaliacao());
        assertEquals("dificil", tools.reviewFlashcards(aula.aulaId(), null, null).cartoes().get(1).avaliacao(),
                "a última avaliação aparece na próxima revisão");

        // Avaliação inválida ou cartão de outro quiz: recusados.
        var invalida = assertThrows(Exception.class, () -> mvc.perform(post("/quizzes/" + quiz.quizId() + "/flashcard")
                .contentType("application/json").content("{\"card\":" + ids.get(0) + ",\"rating\":\"talvez\"}")));
        assertTrue(invalida.getMessage().contains("errei, dificil ou facil"), invalida.getMessage());
        assertThrows(IllegalArgumentException.class, () -> tools.createQuiz(aula.aulaId(), null, null, null, null, null,
                List.of(), List.of(item("Créditos", "A")), null, null, null, ESTILO, REFS, List.of(999_999L)));

        // Quiz sem cartões continua igual.
        var semCartoes = tools.createQuiz(aula.aulaId(), null, null, null, null, null, List.of(),
                List.of(item("Créditos", "A")), null, null, null, ESTILO, REFS);
        mvc.perform(get("/quizzes/" + semCartoes.quizId())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("flash-deck"))));
    }
}
