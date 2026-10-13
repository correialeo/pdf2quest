package com.pdf2questao.session;

import com.pdf2questao.Pdf2QuestaoApplication;
import com.pdf2questao.question.Passage;
import com.pdf2questao.question.PassageRepository;
import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** O texto base fica ao lado da questao (sem modal) e a tela continua igual para questoes sem texto base. */
@SpringBootTest(classes = Pdf2QuestaoApplication.class)
@AutoConfigureMockMvc
class ResolverLayoutTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("resolver.db"));
        registry.add("pdf2questao.pdf-dir", () -> tempDir.resolve("pdfs").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired QuestionRepository questionRepository;
    @Autowired PassageRepository passageRepository;
    @Autowired StudySessionService sessionService;

    private Question question(String statement, Long passageId) {
        Question q = new Question();
        q.setStatement(statement);
        q.setAlternativeA("um");
        q.setAlternativeB("dois");
        q.setCorrectAnswer("A");
        q.setPassageId(passageId);
        return questionRepository.save(q);
    }

    private String page(StudySession session, int index) throws Exception {
        return mvc.perform(get("/resolver/" + session.getId()).param("index", String.valueOf(index)))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void textoBaseVaiParaUmPainelAoLadoEQuestaoSemTextoNaoGanhaPainel() throws Exception {
        Passage passage = new Passage();
        passage.setContent("Maquiavel mudou o ⟦u⟧curso⟦/u⟧ do pensamento <politico>.");
        passage = passageRepository.save(passage);
        Question withText = question("Segundo o texto, Maquiavel", passage.getId());
        Question sameText = question("Ainda segundo o texto", passage.getId());
        Question without = question("Questao sem texto base", null);
        StudySession session = sessionService.startRefazer(List.of(withText.getId(), sameText.getId(), without.getId()));

        String html = page(session, 0);

        assertTrue(html.contains("id=\"passagePanel\""), "painel do texto base");
        assertTrue(html.contains("collapse d-md-block"), "no celular abre/fecha, de tablet em diante fica visivel");
        assertTrue(html.contains("Maquiavel mudou o <u>curso</u> do pensamento &lt;politico&gt;."),
                "texto escapado, com o sublinhado");
        assertTrue(html.contains("has-passage") && html.contains("container-xxl"));
        assertTrue(html.contains("id=\"togglePassage\"") && html.contains("Ocultar texto base"));
        assertFalse(html.contains("passageModal"), "o modal foi removido");
        assertFalse(html.contains("data-bs-toggle=\"modal\""));
        assertTrue(html.indexOf("id=\"passagePanel\"") < html.indexOf("Segundo o texto, Maquiavel"),
                "o texto vem antes (a esquerda) do enunciado");
        assertEquals(1, html.split("Maquiavel mudou", -1).length - 1, "o texto aparece uma vez so");

        // Segunda questao do mesmo texto: o mesmo painel (o navegador mantem a posicao de leitura).
        String second = page(session, 1);
        assertTrue(second.contains("data-passage-id=\"" + passage.getId() + "\""));
        assertTrue(second.contains("Ainda segundo o texto"));

        String none = page(session, 2);
        assertFalse(none.contains("id=\"passagePanel\""), "sem texto base nao ha painel");
        assertFalse(none.contains("togglePassage\""));
        assertFalse(none.contains("has-passage"));
        assertFalse(none.contains("container-xxl"));
        assertTrue(none.contains("Questao sem texto base") && none.contains("answer-option"));
    }
}
