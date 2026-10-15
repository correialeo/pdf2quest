package com.pdf2questao.web;

import com.pdf2questao.Pdf2QuestaoApplication;
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

}
