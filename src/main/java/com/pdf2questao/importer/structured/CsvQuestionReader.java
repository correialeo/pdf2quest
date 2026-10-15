package com.pdf2questao.importer.structured;

import com.pdf2questao.importer.structured.CsvMapping.Field;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Converte as linhas da planilha em {@link StructuredQuestion}, seguindo o {@link CsvMapping} escolhido. */
public class CsvQuestionReader {

    /** "A) texto", "a - texto", "(B) texto": rotulo opcional no inicio de cada alternativa. */
    private static final Pattern LABEL = Pattern.compile("^\\(?([A-Ea-e])\\s*[).:\\-]\\s+");
    private static final Field[] LETTER_FIELDS = {Field.A, Field.B, Field.C, Field.D, Field.E};

    List<StructuredQuestion> read(CsvTable table, CsvMapping mapping) {
        List<StructuredQuestion> result = new ArrayList<>();
        List<List<String>> rows = table.rows();
        for (int r = mapping.isHeader() ? 1 : 0; r < rows.size(); r++) {
            result.add(row(rows.get(r), "Linha " + table.lineNumber(r), mapping));
        }
        return result;
    }

    private StructuredQuestion row(List<String> cells, String label, CsvMapping mapping) {
        StructuredQuestion q = new StructuredQuestion();
        q.label = label;
        q.externalId = cell(cells, mapping, Field.ID);
        q.number = integer(cells, mapping, Field.NUMERO, "Número", q);
        q.type = cell(cells, mapping, Field.TIPO);
        q.statement = cell(cells, mapping, Field.ENUNCIADO);
        q.passage = cell(cells, mapping, Field.TEXTO_BASE);
        q.answer = cell(cells, mapping, Field.RESPOSTA);
        q.explanation = cell(cells, mapping, Field.EXPLICACAO);
        q.organization = cell(cells, mapping, Field.BANCA);
        q.exam = cell(cells, mapping, Field.CONCURSO);
        q.year = integer(cells, mapping, Field.ANO, "Ano", q);
        q.subject = cell(cells, mapping, Field.DISCIPLINA);
        q.topic = cell(cells, mapping, Field.ASSUNTO);
        q.subtopics = list(cell(cells, mapping, Field.SUBTOPICOS));
        q.tags = list(cell(cells, mapping, Field.TAGS));
        q.difficulty = cell(cells, mapping, Field.DIFICULDADE);

        if (mapping.getAlternativesMode() == CsvMapping.AlternativesMode.UMA_COLUNA) {
            String all = cell(cells, mapping, Field.ALTERNATIVAS);
            if (all != null) {
                String separator = mapping.getAlternativesSeparator() == null || mapping.getAlternativesSeparator().isEmpty()
                        ? "|" : mapping.getAlternativesSeparator();
                String[] parts = separator.equals("\\n") ? all.split("\\R") : all.split(Pattern.quote(separator));
                for (String part : parts) {
                    if (!part.isBlank()) {
                        q.alternatives.add(labeled(part.trim()));
                    }
                }
            }
        } else {
            // Uma coluna por letra: a letra vem da coluna; alternativa vazia (ex.: so A-D) e ignorada.
            for (int i = 0; i < LETTER_FIELDS.length; i++) {
                String text = cell(cells, mapping, LETTER_FIELDS[i]);
                if (text != null) {
                    q.alternatives.add(new String[]{String.valueOf((char) ('A' + i)),
                            LABEL.matcher(text).replaceFirst("")});
                }
            }
        }
        return q;
    }

    /** Usa o rotulo "A)" da alternativa como letra quando todas tem rotulo; senao a ordem decide. */
    private static String[] labeled(String text) {
        var m = LABEL.matcher(text);
        if (m.find()) {
            return new String[]{m.group(1).toUpperCase(), text.substring(m.end())};
        }
        return new String[]{null, text};
    }

    private static String cell(List<String> cells, CsvMapping mapping, Field field) {
        Integer column = mapping.column(field);
        if (column == null || column >= cells.size()) {
            return null;
        }
        return JsonQuestionReader.blankToNull(cells.get(column));
    }

    private static Integer integer(List<String> cells, CsvMapping mapping, Field field, String name,
                                   StructuredQuestion q) {
        String value = cell(cells, mapping, field);
        if (value == null) {
            return null;
        }
        if (!value.matches("\\d{1,9}")) {
            q.readErrors.add(name + " \"" + value + "\" não é um número inteiro.");
            return null;
        }
        return Integer.parseInt(value);
    }

    private static List<String> list(String value) {
        if (value == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.stream(value.split("\\|")).map(String::trim).filter(s -> !s.isEmpty()).toList());
    }
}
