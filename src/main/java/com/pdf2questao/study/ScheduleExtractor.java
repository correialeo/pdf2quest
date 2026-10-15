package com.pdf2questao.study;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Acha cronogramas nas tabelas de um documento: tabela cuja primeira coluna tem datas ("Qui 15/10", "15/10/2026")
 * vira uma tarefa por celula preenchida. O cabecalho da coluna da o bloco e o tempo ("B – TI (90 min)"); um tempo
 * escrito na propria celula ("(90 min)", "4 horas") vale mais. Celulas vazias ou com "—" nao viram tarefa.
 */
public final class ScheduleExtractor {

    private static final Pattern DATE = Pattern.compile("(?<!\\d)(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?(?!\\d)");
    private static final Pattern MINUTES = Pattern.compile("(\\d{1,3})\\s*min", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOURS = Pattern.compile("(\\d{1,2})\\s*h(?:oras?)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR = Pattern.compile("\\b(20\\d{2})\\b");
    /** "AFO: conceitos" -> "AFO"; "Contabilidade 1: estoques" e "DAX II: contexto" perdem a numeracao da parte. */
    private static final Pattern SUBJECT = Pattern.compile(
            "^([^:()]{2,30}?)(?:\\s+(?:\\d{1,2}|[IVX]{1,4}))?\\s*(?:\\([^)]*\\))?\\s*:");
    private static final Pattern EMPTY = Pattern.compile("^[\\s—–\\-.]*$");

    public record Item(LocalDate date, String title, String subject, Integer minutes, String block) {
    }

    private ScheduleExtractor() {
    }

    /** Ano usado quando a data da tabela nao traz ano: o primeiro ano citado no documento, ou o atual. */
    public static int guessYear(String text, LocalDate today) {
        Matcher m = YEAR.matcher(text == null ? "" : text);
        return m.find() ? Integer.parseInt(m.group(1)) : today.getYear();
    }

    public static List<Item> extract(List<List<List<String>>> tables, int startYear) {
        List<Item> items = new ArrayList<>();
        int year = startYear;
        int lastMonth = 0;
        for (List<List<String>> table : tables) {
            if (!isSchedule(table)) {
                continue;
            }
            List<String> header = table.get(0);
            for (List<String> row : table.subList(1, table.size())) {
                if (row.isEmpty()) {
                    continue;
                }
                Matcher d = DATE.matcher(row.get(0));
                if (!d.find()) {
                    continue;
                }
                int day = Integer.parseInt(d.group(1));
                int month = Integer.parseInt(d.group(2));
                LocalDate date;
                try {
                    if (d.group(3) != null) {
                        int y = Integer.parseInt(d.group(3));
                        year = y < 100 ? 2000 + y : y;
                    } else if (lastMonth > 0 && month < lastMonth) {
                        // Cronograma que passa de dezembro para janeiro.
                        year++;
                    }
                    date = LocalDate.of(year, month, day);
                } catch (DateTimeException e) {
                    continue;
                }
                lastMonth = month;
                for (int c = 1; c < row.size(); c++) {
                    String cell = row.get(c).strip();
                    if (EMPTY.matcher(cell).matches()) {
                        continue;
                    }
                    String column = c < header.size() ? header.get(c).strip() : "";
                    items.add(new Item(date, cell, subject(cell, column), minutes(cell, column), column));
                }
            }
        }
        return items;
    }

    /** Tabela de cronograma: ao menos 2 linhas de dados com data na primeira coluna e mais de uma coluna. */
    static boolean isSchedule(List<List<String>> table) {
        if (table.size() < 2 || table.get(0).size() < 2) {
            return false;
        }
        long dated = table.stream().skip(1).filter(r -> !r.isEmpty() && DATE.matcher(r.get(0)).find()).count();
        return dated >= 2 && dated * 2 >= table.size() - 1;
    }

    static Integer minutes(String cell, String column) {
        Integer own = time(cell);
        return own != null ? own : time(column);
    }

    private static Integer time(String text) {
        Matcher m = MINUTES.matcher(text);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        m = HOURS.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) * 60 : null;
    }

    /** Disciplina: o que vem antes de ":" na celula; senao o nome do bloco no cabecalho ("B – TI (90 min)" -> "TI"). */
    static String subject(String cell, String column) {
        Matcher m = SUBJECT.matcher(cell);
        // "Scrum Guide 2020: ..." e nome de topico, nao disciplina: fica com o bloco do cabecalho.
        if (m.find() && !m.group(1).matches(".*\\d{3,}.*")) {
            return m.group(1).strip();
        }
        // So cabecalho "bloco – disciplina" vira disciplina; "Foco", "Atividade" etc. nao dizem a materia.
        String label = column.replaceAll("\\([^)]*\\)", "").strip();
        int dash = Math.max(label.lastIndexOf('–'), label.lastIndexOf(" - "));
        if (dash < 0) {
            return null;
        }
        label = label.substring(dash + 1).replaceFirst("^-\\s*", "").strip();
        return label.isEmpty() || label.length() > 40 ? null : label;
    }
}
