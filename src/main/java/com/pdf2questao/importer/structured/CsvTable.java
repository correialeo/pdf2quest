package com.pdf2questao.importer.structured;

import java.util.ArrayList;
import java.util.List;

/**
 * Planilha CSV lida em linhas e celulas (aspas duplas, "" dentro de aspas e quebras de linha dentro de celulas,
 * como o Excel e o LibreOffice gravam). {@link #lineNumbers} guarda a linha do arquivo onde cada registro comeca.
 */
public final class CsvTable {

    public static final char[] DELIMITERS = {';', ',', '\t', '|'};

    private final List<List<String>> rows;
    private final List<Integer> lineNumbers;

    private CsvTable(List<List<String>> rows, List<Integer> lineNumbers) {
        this.rows = rows;
        this.lineNumbers = lineNumbers;
    }

    public List<List<String>> rows() {
        return rows;
    }

    public int lineNumber(int row) {
        return lineNumbers.get(row);
    }

    public int columnCount() {
        return rows.stream().mapToInt(List::size).max().orElse(0);
    }

    public static CsvTable parse(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int rowStart = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    cell.append(c);
                }
            } else if (c == '"' && cell.toString().isBlank()) {
                cell.setLength(0);
                quoted = true;
            } else if (c == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                addRow(rows, lines, row, rowStart);
                row = new ArrayList<>();
                line++;
                rowStart = line;
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            addRow(rows, lines, row, rowStart);
        }
        return new CsvTable(rows, lines);
    }

    private static void addRow(List<List<String>> rows, List<Integer> lines, List<String> row, int line) {
        // Linha em branco (ou so com separadores, comum no fim de planilhas) nao vira registro.
        if (row.stream().allMatch(String::isBlank)) {
            return;
        }
        rows.add(row.stream().map(String::trim).toList());
        lines.add(line);
    }

    /**
     * Separador mais provavel: o que aparece o mesmo numero de vezes (e mais de uma) nas primeiras linhas,
     * fora de aspas. Empate ou nenhum candidato: ponto e virgula, o padrao do Excel em portugues.
     */
    public static char detectDelimiter(String text) {
        char best = ';';
        int bestScore = 0;
        for (char d : DELIMITERS) {
            List<List<String>> sample = parse(text, d).rows();
            sample = sample.subList(0, Math.min(sample.size(), 20));
            if (sample.isEmpty()) {
                continue;
            }
            int columns = sample.get(0).size();
            long consistent = sample.stream().filter(r -> r.size() == columns).count();
            int score = columns > 1 ? (int) (consistent * 100 / sample.size()) * 100 + columns : 0;
            if (score > bestScore) {
                best = d;
                bestScore = score;
            }
        }
        return best;
    }

    public static String delimiterName(char d) {
        return switch (d) {
            case ';' -> "Ponto e vírgula (;)";
            case ',' -> "Vírgula (,)";
            case '\t' -> "Tabulação";
            case '|' -> "Barra vertical (|)";
            default -> String.valueOf(d);
        };
    }
}
