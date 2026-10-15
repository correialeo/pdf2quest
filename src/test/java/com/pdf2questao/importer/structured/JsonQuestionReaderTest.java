package com.pdf2questao.importer.structured;

import com.pdf2questao.question.QuestionType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonQuestionReaderTest {

    private static final StructuredQuestionValidator.ExistingQuestions EMPTY = new StructuredQuestionValidator.ExistingQuestions() {
        @Override
        public Long idByExternalId(String externalId) {
            return null;
        }

        @Override
        public Long idByStatement(String normalizedStatement) {
            return null;
        }
    };

    static ImportPreview read(String json) {
        ImportPreview preview = new ImportPreview("t", "JSON", "teste.json");
        new JsonQuestionReader().read(json.getBytes(StandardCharsets.UTF_8), preview);
        if (preview.getFileErrors().isEmpty()) {
            preview.setItems(new StructuredQuestionValidator().validate(preview.getQuestions(), EMPTY));
        }
        return preview;
    }

    static String file(String questions) {
        return "{\"formato\":\"pdf2questao-questoes\",\"versao\":1,\"questoes\":[" + questions + "]}";
    }

    @Test
    void exemploDistribuidoEValidoEPreencheTodosOsCampos() throws Exception {
        String json = Files.readString(Path.of("src/main/resources/static/exemplos/questoes-exemplo.json"));
        ImportPreview preview = read(json);

        assertTrue(preview.getFileErrors().isEmpty(), preview.getFileErrors().toString());
        assertTrue(preview.getFileWarnings().isEmpty(), preview.getFileWarnings().toString());
        assertEquals(4, preview.getNewCount());
        assertEquals("Exemplo – Direito Constitucional e Português", preview.getTitle());

        PreviewItem first = preview.getItems().get(0);
        assertEquals(QuestionType.MULTIPLA_ESCOLHA, first.getType());
        assertEquals("B", first.getAnswer());
        assertEquals(5, first.getAlternatives().size());
        assertEquals(2024, first.getSource().getYear());
        assertEquals("Exemplo", first.getSource().getOrganization());
        assertEquals(List.of("União", "Distrito Federal"), first.getSource().subtopics);

        PreviewItem certoErrado = preview.getItems().get(1);
        assertEquals(QuestionType.CERTO_ERRADO, certoErrado.getType());
        assertEquals("C", certoErrado.getAnswer());
        assertEquals(List.of("C", "E"), List.copyOf(certoErrado.getAlternatives().keySet()));

        // Alternativas como textos simples recebem as letras pela ordem.
        assertEquals("Exceção", preview.getItems().get(2).getAlternatives().get("B"));
        assertTrue(preview.getItems().get(3).getSource().isAnnulled());
        assertNull(preview.getItems().get(3).getAnswer());
    }

    @Test
    void jsonQuebradoApontaLinha() {
        ImportPreview preview = read("{\"versao\": 1, \"questoes\": [ {\"enunciado\": \"x\" ]}");
        assertEquals(1, preview.getFileErrors().size());
        assertTrue(preview.getFileErrors().get(0).contains("não é um JSON válido"));
    }

    @Test
    void versaoAusenteOuDesconhecidaERecusada() {
        assertTrue(read("{\"questoes\": []}").getFileErrors().get(0).contains("versão"));
        assertTrue(read("{\"versao\": 2, \"questoes\": []}").getFileErrors().get(0).contains("não é suportada"));
        assertTrue(read("{\"formato\": \"outro\", \"versao\": 1, \"questoes\": []}").getFileErrors().get(0)
                .contains("desconhecido"));
    }

    @Test
    void questoesPrecisaSerListaNaoVazia() {
        assertFalse(read("{\"versao\":1,\"questoes\":{}}").getFileErrors().isEmpty());
        assertFalse(read("{\"versao\":1,\"questoes\":[]}").getFileErrors().isEmpty());
    }

    @Test
    void camposObrigatoriosETiposErrados() {
        ImportPreview preview = read(file("""
                {"alternativas": ["a", "b"], "resposta": "A"},
                {"enunciado": "Ano errado", "ano": "dois mil", "alternativas": ["a", "b"]},
                {"enunciado": "Ok", "alternativas": ["a", "b"], "campoNovo": 1}
                """));
        List<PreviewItem> items = preview.getItems();
        assertEquals(PreviewItem.Status.INVALIDA, items.get(0).getStatus());
        assertTrue(items.get(0).getErrors().contains("Falta o enunciado."));
        assertEquals(PreviewItem.Status.INVALIDA, items.get(1).getStatus());
        assertTrue(items.get(1).getErrors().get(0).contains("\"ano\""));
        assertEquals(PreviewItem.Status.NOVA, items.get(2).getStatus());
        assertTrue(items.get(2).getWarnings().stream().anyMatch(w -> w.contains("campoNovo")));
        assertTrue(items.get(2).getWarnings().stream().anyMatch(w -> w.contains("Sem resposta")));
    }

    @Test
    void alternativasInvalidas() {
        ImportPreview preview = read(file("""
                {"enunciado": "Uma so", "alternativas": ["a"]},
                {"enunciado": "Seis", "alternativas": ["a","b","c","d","e","f"]},
                {"enunciado": "Letra repetida", "alternativas": [{"letra":"A","texto":"x"},{"letra":"A","texto":"y"}]},
                {"enunciado": "Letra F", "alternativas": [{"letra":"A","texto":"x"},{"letra":"F","texto":"y"}]},
                {"enunciado": "Vazia", "alternativas": ["x", " "]},
                {"enunciado": "Resposta fora", "alternativas": ["x","y","z"], "resposta": "D"},
                {"enunciado": "CE com alternativas", "tipo": "certo_errado", "alternativas": ["x","y","z"]},
                {"enunciado": "CE resposta B", "tipo": "certo_errado", "resposta": "B"},
                {"enunciado": "Duas respostas", "alternativas": ["x","y"], "resposta": ["A","B"]},
                {"enunciado": "Tipo estranho", "tipo": "discursiva"}
                """));
        preview.getItems().forEach(i -> assertEquals(PreviewItem.Status.INVALIDA, i.getStatus(),
                i.getSource().getStatement() + " deveria ser invalida"));
        assertTrue(preview.getItems().get(5).getErrors().get(0).contains("não corresponde"));
        assertFalse(preview.isImportable());
    }

    @Test
    void respostasEscritasDeVariosJeitosSaoNormalizadas() {
        ImportPreview preview = read(file("""
                {"enunciado": "1", "alternativas": ["x","y","z"], "resposta": "(c)"},
                {"enunciado": "2", "resposta": "errado"},
                {"enunciado": "3", "resposta": "V"},
                {"enunciado": "4", "alternativas": ["x","y"], "resposta": "anulada"}
                """));
        assertEquals("C", preview.getItems().get(0).getAnswer());
        assertEquals("E", preview.getItems().get(1).getAnswer());
        assertEquals("C", preview.getItems().get(2).getAnswer());
        assertTrue(preview.getItems().get(3).getSource().isAnnulled());
    }

    @Test
    void idsRepetidosNoArquivoInvalidamTodasAsCopias() {
        ImportPreview preview = read(file("""
                {"id": "q1", "enunciado": "Primeira", "resposta": "C"},
                {"id": "q1", "enunciado": "Segunda", "resposta": "E"},
                {"id": "q2", "enunciado": "Terceira", "resposta": "E"}
                """));
        assertEquals(PreviewItem.Status.INVALIDA, preview.getItems().get(0).getStatus());
        assertEquals(PreviewItem.Status.INVALIDA, preview.getItems().get(1).getStatus());
        assertTrue(preview.getItems().get(0).getErrors().get(0).contains("Questão 1, Questão 2"));
        assertEquals(PreviewItem.Status.NOVA, preview.getItems().get(2).getStatus());
    }

    @Test
    void enunciadoRepetidoNoArquivoViraPossivelDuplicata() {
        ImportPreview preview = read(file("""
                {"enunciado": "Mesma   pergunta", "resposta": "C"},
                {"enunciado": "mesma pérgunta", "resposta": "E"}
                """));
        assertEquals(PreviewItem.Status.NOVA, preview.getItems().get(0).getStatus());
        assertEquals(PreviewItem.Status.POSSIVEL_DUPLICATA, preview.getItems().get(1).getStatus());
        assertEquals("Questão 1 deste arquivo", preview.getItems().get(1).getDuplicateOf());
    }
}
