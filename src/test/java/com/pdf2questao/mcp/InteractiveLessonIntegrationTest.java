package com.pdf2questao.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdf2questao.Pdf2QuestaoApplication;
import com.pdf2questao.aula.LessonService;
import com.pdf2questao.mcp.LessonMcpTools.QuizAlternative;
import com.pdf2questao.mcp.LessonMcpTools.QuizItem;
import com.pdf2questao.runner.CodeRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Aulas interativas de ponta a ponta: as duas aulas de demonstracao (docs/aulas-demo) passam pela criacao com os
 * blocos validados de verdade (o codigo roda no Pyodide/sql.js via Node), exercicio guiado conta no desempenho com as
 * dicas, e trechos literais das fontes. Os testes que rodam codigo pulam sem ./instalar-runtimes.sh.
 */
@SpringBootTest(classes = Pdf2QuestaoApplication.class)
@AutoConfigureMockMvc
class InteractiveLessonIntegrationTest {

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
    LessonService lessonService;
    @Autowired
    CodeRunner runner;
    @Autowired
    MockMvc mvc;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Cria uma aula de docs/aulas-demo pelo criar_aula (sem trechos: as fontes aqui sao so links). */
    LessonMcpTools.LessonCreated demo(String name) throws Exception {
        Path dir = Path.of("docs/aulas-demo");
        JsonNode p = JSON.readTree(dir.resolve(name + ".json").toFile());
        String content = Files.readString(dir.resolve(p.get("conteudoArquivo").asText()));
        List<QuizItem> items = new ArrayList<>();
        for (JsonNode q : p.get("perguntasDaAula")) {
            List<QuizAlternative> alts = new ArrayList<>();
            q.get("alternativas").forEach(a -> alts.add(new QuizAlternative(a.get("texto").asText(),
                    a.get("explicacao").asText())));
            List<String> concepts = new ArrayList<>();
            q.get("conceitos").forEach(c -> concepts.add(c.asText()));
            items.add(new QuizItem(q.get("enunciado").asText(), q.get("topico").asText(), alts, q.get("correta").asText(),
                    concepts, null));
        }
        return tools.createLesson(p.get("titulo").asText(), p.get("disciplina").asText(), p.get("topicoEdital").asText(),
                content, p.get("fontes").asText(), null, null, null, null, items, p.get("estiloDaBanca").asText(),
                p.get("referencias").asText(), null);
    }

    @Test
    void aulaDeMatematicaFinanceiraComFormulaEExercicioGuiado() throws Exception {
        Assumptions.assumeTrue(runner.installed(), "rode ./instalar-runtimes.sh");
        var aula = demo("matematica-financeira");
        assertEquals(4, aula.slides(), "3 slides de conteúdo + Fontes");
        assertEquals(5, aula.questoesDeFixacao(), "3 de múltipla escolha + 2 exercícios guiados");

        mvc.perform(get("/aulas/" + aula.aulaId())).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("connect-src 'self'")))
                .andExpect(content().string(containsString("lesson-box-formula")))
                .andExpect(content().string(containsString("class=\"calc-block\"")))
                .andExpect(content().string(containsString("/vendor/katex/katex.min.js")))
                .andExpect(content().string(containsString("/js/lesson-interactive.js")));

        // Exercicio guiado: concluiu com 2 dicas (conta como acerto), depois viu a resolucao (conta como erro).
        var fixacao = lessonService.fixation(aula.aulaId());
        var exercicios = lessonService.questions(fixacao.getId()).stream().filter(q -> q.getKind() != null).toList();
        assertEquals(List.of("calculo", "calculo"), exercicios.stream().map(q -> q.getKind()).toList());
        assertEquals("jc-montante", exercicios.get(0).getConcepts());
        int bloco = exercicios.get(0).getBlockIndex();
        mvc.perform(post("/aulas/" + aula.aulaId() + "/exercicio").contentType("application/json")
                        .content("{\"block\":" + bloco + ",\"correct\":true,\"hints\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.correct").value(true))
                .andExpect(jsonPath("$.hints").value(2));
        var r = tools.quizResult(fixacao.getId()).questoes().stream()
                .filter(q -> q.perguntaId().equals(exercicios.get(0).getId())).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, r.acertou());
        assertEquals(2, r.dicasUsadas());
        assertEquals("calculo", r.tipo());
        lessonService.recordExercise(aula.aulaId(), bloco, false, 3);
        assertEquals(Boolean.FALSE, tools.quizResult(fixacao.getId()).questoes().stream()
                .filter(q -> q.perguntaId().equals(exercicios.get(0).getId())).findFirst().orElseThrow().acertou(),
                "vale a última tentativa");
        assertTrue(lessonService.stats().stream().anyMatch(s -> "Mat. Fin.".equals(s.disciplina())),
                "o exercício entra no desempenho por tópico");
    }

    @Test
    void aulaDeSqlPandasEPySparkRodaOsExemplosNaCriacao() throws Exception {
        Assumptions.assumeTrue(runner.installed(), "rode ./instalar-runtimes.sh");
        var aula = demo("sql-pandas");
        assertEquals(5, aula.questoesDeFixacao(), "3 de múltipla escolha + 2 tarefas de código");
        mvc.perform(get("/aulas/" + aula.aulaId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"code-run\"")))
                .andExpect(content().string(containsString("Simulação — não é Spark real")))
                .andExpect(content().string(containsString("data-datasets=")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("/vendor/katex/"))));
        mvc.perform(get("/runner/runner-worker.js")).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")));
        mvc.perform(get("/vendor/sqljs/sql-wasm.js")).andExpect(status().isOk());
    }

    private static final String LINK = "[SQLite](https://www.sqlite.org/lang_select.html)";
    private static final String DADOS = ":::dados t\nnome,valor\nA,10\nB,32\n:::\n";

    private Exception create(String extra) {
        String content = "=== Soma {#regra}\n" + DADOS + extra + "\n[[questão 1]]\n=== Outro {#outro}\nTexto.\n"
                + "[[questão 2]]\n[[questão 3]]";
        return assertThrows(IllegalArgumentException.class, () -> tools.createLesson("x", "TI", "SQL", content, LINK,
                null, null, null, null, LessonMcpToolsIntegrationTest.fixacao(), LessonMcpToolsIntegrationTest.ESTILO,
                LessonMcpToolsIntegrationTest.REFS, null));
    }

    @Test
    void codigoQueNaoRodaOuNaoDaASaidaDeclaradaERecusado() {
        Assumptions.assumeTrue(runner.installed(), "rode ./instalar-runtimes.sh");
        var saidaErrada = create("```sql run\nSELECT SUM(valor) AS total FROM t;\n```\n```saida\ntotal\n40\n```");
        assertTrue(saidaErrada.getMessage().contains("não dá a saída declarada. Saída obtida:\ntotal\n42"),
                saidaErrada.getMessage());
        var naoRoda = create("```python run\nimport pandas as pd\npd.read_csv('dados/nao_existe.csv')\n```");
        assertTrue(naoRoda.getMessage().contains("não roda: Linha 2: FileNotFoundError"), naoRoda.getMessage());
        var inicialJaPassa = create("```tarefa python\n### enunciado\nx\n### codigo\nx = 2\n### teste\nassert x == 2\n"
                + "### dica\nd\n### solucao\nx = 2\n```");
        assertTrue(inicialJaPassa.getMessage().contains("já passa no teste"), inicialJaPassa.getMessage());
        var solucaoFalha = create("```tarefa sql\n### enunciado\nx\n### codigo\nSELECT 1;\n### esperado\ntotal\n42\n"
                + "### dica\nd\n### solucao\nSELECT SUM(valor) AS total FROM t WHERE valor > 10;\n```");
        assertTrue(solucaoFalha.getMessage().contains("(solução) não dá a saída declarada"), solucaoFalha.getMessage());
    }

    @Test
    void trechoLiteralEExigidoParaOArtigoCitado() {
        String texto = "Art. 42. É vedado ao titular de Poder ou órgão referido no art. 20, nos últimos dois "
                + "quadrimestres do seu mandato, contrair obrigação de despesa que não possa ser cumprida integralmente "
                + "dentro dele.";
        var resumo = assertThrows(IllegalArgumentException.class, () -> tools.registerExcerpt(
                "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp101.htm", "LRF, art. 42", texto, "resumo do WebFetch"));
        assertTrue(resumo.getMessage().contains("baixe o arquivo"), resumo.getMessage());
        assertThrows(IllegalArgumentException.class, () -> tools.registerExcerpt(
                "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp101.htm", "LRF, art. 42", "Art. 42. curto", "curl"));
        var semNorma = assertThrows(IllegalArgumentException.class, () -> tools.registerExcerpt(
                "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp101.htm", "art. 42", texto, "curl do HTML"));
        assertTrue(semNorma.getMessage().contains("diga a norma"), semNorma.getMessage());
        var t = tools.registerExcerpt("https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp101.htm", "LRF, art. 42",
                texto, "curl do HTML do Planalto, recorte do art. 42");
        assertEquals(t.trechoId(), tools.registerExcerpt("https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp101.htm",
                "LRF, art. 42", texto, "curl de novo").trechoId(), "mesmo texto não duplica");

        String link = "[LRF](https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp101.htm)";
        String aula = "=== Art. 42 {#regra}\nO art. 42 da LRF veda despesa no fim do mandato.\n[[questão 1]]\n"
                + "=== Exceção {#lrf-excecao}\nTexto.\n[[questão 2]]\n[[questão 3]]";
        var semTrecho = assertThrows(IllegalArgumentException.class, () -> tools.createLesson("LRF", "AFO", "LRF",
                aula, link, null, null, null, null, LessonMcpToolsIntegrationTest.fixacao(),
                LessonMcpToolsIntegrationTest.ESTILO, LessonMcpToolsIntegrationTest.REFS, null, List.of()));
        assertTrue(semTrecho.getMessage().contains("Informe 'trechos'"), semTrecho.getMessage());
        var faltaArt = assertThrows(IllegalArgumentException.class, () -> tools.createLesson("LRF", "AFO", "LRF",
                aula.replace("art. 42 da LRF", "art. 42 da Lei 4.320"), link, null, null, null, null, LessonMcpToolsIntegrationTest.fixacao(),
                LessonMcpToolsIntegrationTest.ESTILO, LessonMcpToolsIntegrationTest.REFS, null, List.of(t.trechoId())));
        assertTrue(faltaArt.getMessage().contains("cita art. 42 da Lei 4.320 sem trecho literal"), faltaArt.getMessage());

        var criada = tools.createLesson("LRF", "AFO", "LRF", aula, link, null, null, null, null,
                LessonMcpToolsIntegrationTest.fixacao(), LessonMcpToolsIntegrationTest.ESTILO,
                LessonMcpToolsIntegrationTest.REFS, null, List.of(t.trechoId()));
        String conteudo = lessonService.find(criada.aulaId()).getContent();
        assertTrue(conteudo.contains("### Trechos literais conferidos"), conteudo);
        assertTrue(conteudo.contains("> Art. 42. É vedado"), conteudo);
        assertEquals(1, lessonService.lessonExcerpts(criada.aulaId()).size());
    }
}
