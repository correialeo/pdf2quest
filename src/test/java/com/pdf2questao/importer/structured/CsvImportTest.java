package com.pdf2questao.importer.structured;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.Charset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvImportTest {

    @ParameterizedTest
    @ValueSource(strings = {";", ",", "\t", "|"})
    void reconheceOSeparadorEMapeiaPeloCabecalho(String d) {
        String csv = String.join(d, "Enunciado", "A", "B", "C", "Gabarito", "Disciplina") + "\n"
                + String.join(d, "\"Quanto é 2" + d + "2?\"", "3", "4", "5", "b", "Matemática") + "\r\n"
                + String.join(d, "\"Uma \"\"citação\"\"\nem duas linhas\"", "x", "y", "z", "A", "Português") + "\n";
        char delimiter = CsvTable.detectDelimiter(csv);
        assertEquals(d.charAt(0), delimiter);

        CsvTable table = CsvTable.parse(csv, delimiter);
        CsvMapping mapping = CsvMapping.guess(table.rows().get(0), delimiter);
        assertTrue(mapping.isHeader());
        assertEquals(0, mapping.column(CsvMapping.Field.ENUNCIADO));
        assertEquals(4, mapping.column(CsvMapping.Field.RESPOSTA));

        List<StructuredQuestion> questions = new CsvQuestionReader().read(table, mapping);
        assertEquals(2, questions.size());
        assertEquals("Quanto é 2" + d + "2?", questions.get(0).statement);
        assertEquals("b", questions.get(0).answer);
        assertEquals("Uma \"citação\"\nem duas linhas", questions.get(1).statement);
        assertEquals("Linha 3", questions.get(1).label);

        var items = new StructuredQuestionValidator().validate(questions, none());
        assertEquals("B", items.get(0).getAnswer());
        assertEquals("4", items.get(0).getAlternatives().get("B"));
    }

    @Test
    void alternativasNumaColunaComRotulos() {
        String csv = "pergunta;opcoes;resposta\nCapital?;A) Rio | B) Brasília | C) Recife;B\nSem rótulo;x|y;A\n";
        CsvTable table = CsvTable.parse(csv, ';');
        CsvMapping mapping = CsvMapping.guess(table.rows().get(0), ';');
        mapping.map(CsvMapping.Field.ALTERNATIVAS, 1);
        mapping.setAlternativesMode(CsvMapping.AlternativesMode.UMA_COLUNA);
        var items = new StructuredQuestionValidator().validate(new CsvQuestionReader().read(table, mapping), none());
        assertEquals("Brasília", items.get(0).getAlternatives().get("B"));
        assertEquals("B", items.get(0).getAnswer());
        assertEquals("y", items.get(1).getAlternatives().get("B"));
    }

    @Test
    void semCabecalhoReconhecidoAPrimeiraLinhaEQuestao() {
        CsvMapping mapping = CsvMapping.guess(List.of("Qual a cor do céu?", "Azul", "Verde"), ';');
        assertTrue(mapping.getColumns().isEmpty());
        assertTrue(!mapping.isHeader());
    }

    @Test
    void linhasInvalidasApontamONumeroDaLinha() {
        String csv = "enunciado;a;b;resposta;ano\n;x;y;A;2020\nOk;x;y;C;2020\nOk 2;x;y;A;vinte\n\n";
        CsvTable table = CsvTable.parse(csv, ';');
        CsvMapping mapping = CsvMapping.guess(table.rows().get(0), ';');
        var items = new StructuredQuestionValidator().validate(new CsvQuestionReader().read(table, mapping), none());
        assertEquals(3, items.size());
        assertEquals("Linha 2", items.get(0).getSource().getLabel());
        assertTrue(items.get(0).getErrors().contains("Falta o enunciado."));
        assertTrue(items.get(1).getErrors().get(0).contains("não corresponde"));
        assertTrue(items.get(2).getErrors().get(0).contains("Ano \"vinte\""));
    }

    @Test
    void arquivoDoExcelEmWindows1252() {
        byte[] bytes = "enunciado;resposta\nInformação;Certo\n".getBytes(Charset.forName("windows-1252"));
        assertEquals("enunciado;resposta\nInformação;Certo\n", TextDecoding.decode(bytes));
    }

    @Test
    void certoErradoSemAlternativas() {
        String csv = "enunciado;resposta\nItem;Errado\n";
        CsvTable table = CsvTable.parse(csv, ';');
        var items = new StructuredQuestionValidator().validate(
                new CsvQuestionReader().read(table, CsvMapping.guess(table.rows().get(0), ';')), none());
        assertEquals("E", items.get(0).getAnswer());
        assertNull(items.get(0).getAlternatives().get("A"));
    }

    private static StructuredQuestionValidator.ExistingQuestions none() {
        return new StructuredQuestionValidator.ExistingQuestions() {
            @Override
            public Long idByExternalId(String externalId) {
                return null;
            }

            @Override
            public Long idByStatement(String normalizedStatement) {
                return null;
            }
        };
    }
}
