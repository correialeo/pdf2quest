package com.pdf2questao.importer.structured;

import java.util.List;

/** Planilha recebida e ainda nao mapeada: a tela de mapeamento mostra as primeiras linhas e a sugestao. */
public record CsvUpload(String token, String fileName, CsvTable table, CsvMapping mapping) {

    public List<List<String>> sample() {
        return table.rows().subList(0, Math.min(6, table.rows().size()));
    }

    public int columnCount() {
        return table.columnCount();
    }

    /** Nome de cada coluna para os selects: o cabecalho, ou "Coluna 1", "Coluna 2"... */
    public List<String> columnNames() {
        List<String> first = table.rows().isEmpty() ? List.of() : table.rows().get(0);
        return java.util.stream.IntStream.range(0, columnCount())
                .mapToObj(i -> mapping.isHeader() && i < first.size() && !first.get(i).isBlank()
                        ? "Coluna " + (i + 1) + ": " + first.get(i) : "Coluna " + (i + 1))
                .toList();
    }
}
