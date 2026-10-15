package com.pdf2questao.web;

import com.pdf2questao.Pdf2QuestaoApplication;
import com.pdf2questao.study.StudyPage;
import com.pdf2questao.study.StudyPageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Percorre as telas novas pelo HTTP, como o navegador: renderiza cada template e segue os redirecionamentos. */
@SpringBootTest(classes = Pdf2QuestaoApplication.class)
@AutoConfigureMockMvc
class NewScreensWebTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("test.db"));
        registry.add("pdf2questao.pdf-dir", () -> tempDir.resolve("pdfs").toString());
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    StudyPageService pageService;

    @Test
    void importacaoJsonDoInicioAoFim() throws Exception {
        mvc.perform(get("/import/arquivo")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Importar questões de arquivo")));
        byte[] json = Files.readAllBytes(Path.of("src/main/resources/static/exemplos/questoes-exemplo.json"));
        MvcResult upload = mvc.perform(multipart("/import/json").file(new MockMultipartFile("file", "ex.json",
                "application/json", json))).andExpect(status().is3xxRedirection()).andReturn();
        String previewUrl = upload.getResponse().getRedirectedUrl();
        mvc.perform(get(previewUrl)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Prévia da importação")))
                .andExpect(content().string(containsString("exemplo-2024-q1")));
        MvcResult confirm = mvc.perform(post(previewUrl + "/confirmar").param("title", "Minha prova"))
                .andExpect(redirectedUrlPattern("/import/result/*")).andReturn();
        mvc.perform(get(confirm.getResponse().getRedirectedUrl())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Minha prova")))
                .andExpect(content().string(containsString("Importada de arquivo")));
        mvc.perform(get("/import")).andExpect(status().isOk()).andExpect(content().string(containsString("Minha prova")));

        // Reimportar o mesmo arquivo: tudo aparece como ja cadastrado.
        MvcResult again = mvc.perform(multipart("/import/json").file(new MockMultipartFile("file", "ex.json",
                "application/json", json))).andReturn();
        mvc.perform(get(again.getResponse().getRedirectedUrl())).andExpect(status().isOk())
                .andExpect(content().string(containsString("já cadastrada")));
    }

    @Test
    void jsonInvalidoMostraOErroSemGravar() throws Exception {
        MvcResult upload = mvc.perform(multipart("/import/json").file(new MockMultipartFile("file", "x.json",
                "application/json", "{ quebrado".getBytes(StandardCharsets.UTF_8)))).andReturn();
        mvc.perform(get(upload.getResponse().getRedirectedUrl())).andExpect(status().isOk())
                .andExpect(content().string(containsString("não é um JSON válido")));
    }

    @Test
    void csvMapeamentoPreviaEConfirmacao() throws Exception {
        byte[] csv = Files.readAllBytes(Path.of("src/main/resources/static/exemplos/questoes-exemplo.csv"));
        MvcResult upload = mvc.perform(multipart("/import/csv").file(new MockMultipartFile("file", "q.csv",
                "text/csv", csv))).andExpect(status().is3xxRedirection()).andReturn();
        String mapUrl = upload.getResponse().getRedirectedUrl();
        mvc.perform(get(mapUrl)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Como ler a planilha")))
                .andExpect(content().string(containsString("Coluna 3: enunciado")));
        mvc.perform(get(mapUrl).param("delimitador", ",")).andExpect(status().isOk());
        MvcResult preview = mvc.perform(post(mapUrl).param("delimitador", ";").param("cabecalho", "on")
                        .param("modoAlternativas", "COLUNAS").param("col_ID", "0").param("col_ENUNCIADO", "2")
                        .param("col_A", "3").param("col_B", "4").param("col_C", "5").param("col_D", "6")
                        .param("col_E", "7").param("col_RESPOSTA", "8").param("col_DISCIPLINA", "9"))
                .andExpect(status().is3xxRedirection()).andReturn();
        String previewUrl = preview.getResponse().getRedirectedUrl();
        mvc.perform(get(previewUrl)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Mudar as colunas")));
        mvc.perform(post(previewUrl.replaceFirst("\\?.*", "") + "/confirmar"))
                .andExpect(redirectedUrlPattern("/import/result/*"));

        // Gabarito em arquivo, pelo ID.
        mvc.perform(get("/import/gabarito/arquivo")).andExpect(status().isOk());
        MvcResult key = mvc.perform(multipart("/import/gabarito/arquivo").param("modo", "ID")
                .param("texto", "exemplo-csv-q1;C\nnao-existe;A")).andReturn();
        mvc.perform(get(key.getResponse().getRedirectedUrl())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Conflitos (1)")))
                .andExpect(content().string(containsString("Respostas sem questão correspondente (1)")));
    }

    @Test
    void telasDeEstudosEAutosave() throws Exception {
        mvc.perform(get("/estudos")).andExpect(status().isOk()).andExpect(content().string(containsString("Para hoje")));
        mvc.perform(get("/estudos/paginas")).andExpect(status().isOk()).andExpect(content().string(containsString("Criar página")));
        mvc.perform(get("/estudos/cronograma")).andExpect(status().isOk()).andExpect(content().string(containsString("Nova tarefa")));
        mvc.perform(get("/estudos/caixa-de-entrada")).andExpect(status().isOk()).andExpect(content().string(containsString("Caixa vazia")));

        MvcResult created = mvc.perform(post("/estudos/paginas").param("title", "Página web"))
                .andExpect(redirectedUrlPattern("/estudos/paginas/*")).andReturn();
        String pageUrl = created.getResponse().getRedirectedUrl();
        Long id = Long.parseLong(pageUrl.substring(pageUrl.lastIndexOf('/') + 1));
        mvc.perform(post("/estudos/paginas").param("parentId", id.toString()).param("title", "Filha"));
        mvc.perform(get(pageUrl)).andExpect(status().isOk()).andExpect(content().string(containsString("Filha")))
                .andExpect(content().string(containsString("data-version=\"0\"")));

        mvc.perform(put(pageUrl + "/conteudo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Novo\",\"content\":\"<p>oi</p>\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        // Versao antiga: recusa e devolve o que esta gravado, para o editor ver se e conflito de verdade.
        mvc.perform(put(pageUrl + "/conteudo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Velho\",\"content\":\"<p>x</p>\",\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.title").value("Novo")).andExpect(jsonPath("$.content").value("<p>oi</p>"));
        mvc.perform(get(pageUrl)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .header().string("Cache-Control", "no-store"));
        StudyPage page = pageService.find(id).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("Novo", page.getTitle());

        mvc.perform(post("/estudos/cronograma").param("title", "Tarefa web").param("plannedDate", java.time.LocalDate.now().toString())
                .param("pageId", id.toString())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/estudos/cronograma")).andExpect(status().isOk()).andExpect(content().string(containsString("Tarefa web")));
        mvc.perform(post("/estudos/caixa-de-entrada").param("content", "nota web")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/estudos/caixa-de-entrada")).andExpect(status().isOk()).andExpect(content().string(containsString("nota web")));
        // Com tarefa para hoje, o aviso de dia vazio nao pode aparecer (th:if junto de th:replace o mostrava sempre).
        mvc.perform(get("/estudos")).andExpect(status().isOk()).andExpect(content().string(containsString("Novo")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Nada planejado para hoje"))));

        // Resumo do dia pelo cronograma: cria na primeira vez, abre o mesmo depois, e a tela mostra o painel do dia.
        String summary = mvc.perform(post("/estudos/cronograma/dia/" + java.time.LocalDate.now() + "/resumo"))
                .andExpect(redirectedUrlPattern("/estudos/paginas/*")).andReturn().getResponse().getRedirectedUrl();
        mvc.perform(post("/estudos/cronograma/dia/" + java.time.LocalDate.now() + "/resumo"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(summary));
        mvc.perform(get(summary)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Tarefa web")))
                .andExpect(content().string(containsString("Adicionar tarefa neste dia")));
        mvc.perform(get("/estudos/cronograma")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Abrir resumo")));

        mvc.perform(post(pageUrl + "/excluir")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/estudos/paginas"));
        mvc.perform(put(pageUrl + "/conteudo").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\",\"content\":\"\",\"version\":1}")).andExpect(status().isNotFound());
    }

    @Autowired
    com.pdf2questao.study.StudyTaskRepository taskRepository;

    @Test
    void docxViraPaginaETarefasSemDuplicarNaReimportacao() throws Exception {
        String body = com.pdf2questao.study.DocxImportTest.p("Heading1", "Plano docx")
                + "<w:tbl>" + com.pdf2questao.study.DocxImportTest.row("Dia", "A1 – P1 (60 min)")
                + com.pdf2questao.study.DocxImportTest.row("Seg 02/03/2026", "Docx: tarefa um")
                + com.pdf2questao.study.DocxImportTest.row("Ter 03/03", "Docx: tarefa dois") + "</w:tbl>";
        MockMultipartFile file = new MockMultipartFile("file", "plano.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                com.pdf2questao.study.DocxImportTest.docx(body, null));
        String preview = mvc.perform(multipart("/estudos/importar").file(file)).andReturn().getResponse().getRedirectedUrl();
        mvc.perform(get(preview)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Criar <strong>2</strong> tarefas")))
                .andExpect(content().string(containsString("Docx: tarefa dois")));
        String page = mvc.perform(post(preview).param("createTasks", "true"))
                .andExpect(redirectedUrlPattern("/estudos/paginas/*")).andReturn().getResponse().getRedirectedUrl();
        Long pageId = Long.parseLong(page.substring(page.lastIndexOf('/') + 1));
        mvc.perform(get(page)).andExpect(status().isOk()).andExpect(content().string(containsString("Plano docx")));
        org.junit.jupiter.api.Assertions.assertEquals(2, taskRepository.findByPageId(pageId).size());

        String again = mvc.perform(multipart("/estudos/importar").file(file)).andReturn().getResponse().getRedirectedUrl();
        mvc.perform(get(again)).andExpect(status().isOk())
                .andExpect(content().string(containsString("2 tarefas iguais")));

        mvc.perform(multipart("/estudos/importar").file(new MockMultipartFile("file", "x.docx", "application/octet-stream",
                "nao".getBytes()))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/estudos/paginas"));
    }
}
