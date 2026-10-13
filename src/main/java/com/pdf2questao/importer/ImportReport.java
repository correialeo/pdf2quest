package com.pdf2questao.importer;

import com.pdf2questao.importer.parser.GabaritoEntries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Relatorio legivel de uma importacao: total importado, questoes marcadas para revisao,
 * como os titulos de secao detectados no OCR se comparam ao mapa informado e, depois de
 * vincular o gabarito, as divergencias entre prova e gabarito. E texto puro, guardado em
 * {@code ImportJob.report} e mostrado na tela de resultado.
 */
public final class ImportReport {

    private ImportReport() {
    }

    public static String forExam(String fileName, ParseResult result, SectionMap map, boolean usedOcr) {
        List<String> lines = new ArrayList<>();
        lines.add("Importacao da prova: " + fileName + (usedOcr ? " (lida por OCR)" : ""));
        lines.add("Questoes importadas: " + result.questions().size()
                + " | falhas de leitura: " + result.failures().size());
        for (ParseFailure failure : result.failures()) {
            lines.add("  - Questao " + failure.number() + ": " + failure.reason());
        }

        List<ParsedQuestion> review = result.questions().stream().filter(ParsedQuestion::needsReview).toList();
        lines.add("Marcadas para revisao (needs_review): " + review.size());
        for (ParsedQuestion q : review) {
            lines.add("  - Questao " + q.number() + ": " + q.reviewReason());
        }

        lines.addAll(sectionLines(result, map));
        return String.join("\n", lines);
    }

    private static List<String> sectionLines(ParseResult result, SectionMap map) {
        List<String> lines = new ArrayList<>();
        List<ParsedQuestion> headings = result.questions().stream().filter(q -> q.sectionHeading() != null).toList();
        if (map.isEmpty()) {
            lines.add("Mapa de secoes: nao informado (disciplinas vem so dos titulos detectados).");
        } else {
            lines.add("Mapa de secoes informado: " + map.sections().size() + " faixas (tem prioridade sobre o OCR).");
            Set<Integer> uncovered = new TreeSet<>();
            for (ParsedQuestion q : result.questions()) {
                if (map.find(q.number()).isEmpty()) {
                    uncovered.add(q.number());
                }
            }
            if (!uncovered.isEmpty()) {
                lines.add("  - Questoes fora de qualquer faixa do mapa (sem disciplina): " + uncovered);
            }
            int last = result.questions().stream().mapToInt(ParsedQuestion::number).max().orElse(0);
            for (SectionMap.Section s : map.sections()) {
                if (s.to() > last) {
                    lines.add("  - Faixa " + s.from() + "-" + s.to() + " (" + s.subject()
                            + ") passa da ultima questao importada (" + last + ").");
                }
            }
        }
        if (map.isEmpty() && !headings.isEmpty()) {
            lines.add("Mapa de secoes sugerido (copie, ajuste e use ao importar/reprocessar):");
            lines.add(suggestedMap(result, headings));
        }
        lines.add("Titulos de secao detectados pelo OCR (sugestao): " + headings.size());
        for (ParsedQuestion q : headings) {
            String verdict = "";
            if (!map.isEmpty()) {
                var section = map.find(q.number());
                boolean startsSection = map.find(q.number() - 1).map(prev -> prev != section.orElse(null)).orElse(true);
                boolean near = map.sections().stream().anyMatch(s -> Math.abs(s.from() - q.number()) <= 1);
                verdict = section.isPresent() && (startsSection || near)
                        ? " -> confere com o mapa (" + section.get().subject() + ")"
                        : " -> NAO coincide com o inicio de nenhuma faixa do mapa";
            }
            lines.add("  - antes da questao " + q.number() + ": \"" + q.sectionHeading() + "\"" + verdict);
        }
        return lines;
    }

    /** JSON de mapa de secoes montado a partir dos titulos detectados: cada titulo vale ate o proximo. */
    static String suggestedMap(ParseResult result, List<ParsedQuestion> headings) {
        int last = result.questions().stream().mapToInt(ParsedQuestion::number).max().orElse(0);
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < headings.size(); i++) {
            int from = headings.get(i).number();
            int to = i + 1 < headings.size() ? headings.get(i + 1).number() - 1 : last;
            entries.add("  {\"from\": " + from + ", \"to\": " + to + ", \"subject\": \""
                    + headings.get(i).sectionHeading().replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
        }
        return "[\n" + String.join(",\n", entries) + "\n]";
    }

    /** Divergencias entre as questoes importadas da prova e as linhas do gabarito. */
    public static List<String> gabaritoLines(GabaritoEntries entries, Collection<Integer> questionNumbers) {
        List<String> lines = new ArrayList<>();
        lines.add("Gabarito: " + entries.totalRead() + " linhas lidas"
                + (entries.declaredTotal() != null ? " (total declarado no PDF: " + entries.declaredTotal() + ")" : "")
                + " | questoes na prova: " + questionNumbers.size());
        Set<Integer> withoutQuestion = new TreeSet<>();
        withoutQuestion.addAll(entries.answers().keySet());
        withoutQuestion.addAll(entries.annulled());
        withoutQuestion.removeAll(questionNumbers);
        Set<Integer> withoutAnswer = new TreeSet<>(questionNumbers);
        withoutAnswer.removeAll(entries.answers().keySet());
        withoutAnswer.removeAll(entries.annulled());

        if (!withoutAnswer.isEmpty()) {
            lines.add("  - Questoes da prova sem linha no gabarito: " + withoutAnswer);
        }
        if (!withoutQuestion.isEmpty()) {
            lines.add("  - Linhas do gabarito sem questao na prova: " + withoutQuestion);
        }
        if (!entries.annulled().isEmpty()) {
            lines.add("  - Anuladas (contam ponto para todos): " + new TreeSet<>(entries.annulled()));
        }
        if (!entries.changed().isEmpty()) {
            lines.add("  - Gabarito alterado: " + new TreeSet<>(entries.changed()));
        }
        for (String warning : entries.warnings()) {
            lines.add("  - Aviso: " + warning);
        }
        if (withoutAnswer.isEmpty() && withoutQuestion.isEmpty() && entries.warnings().isEmpty()) {
            lines.add("  - Sem divergencias entre prova e gabarito.");
        }
        return lines;
    }
}
