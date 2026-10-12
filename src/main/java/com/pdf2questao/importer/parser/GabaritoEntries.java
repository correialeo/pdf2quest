package com.pdf2questao.importer.parser;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Leitura completa de uma tabela de gabarito. {@code answers} so tem questoes com
 * letra valida; {@code annulled} lista as anuladas (que contam ponto para todos);
 * {@code declaredTotal} e o "Total de questoes" impresso no PDF, quando existe, e
 * {@code warnings} registra qualquer inconsistencia encontrada na leitura.
 */
public record GabaritoEntries(
        Map<Integer, String> answers,
        Set<Integer> annulled,
        Set<Integer> changed,
        Integer declaredTotal,
        List<String> warnings
) {

    public static GabaritoEntries ofAnswers(Map<Integer, String> answers) {
        return new GabaritoEntries(answers, Set.of(), Set.of(), null, List.of());
    }

    public int totalRead() {
        return answers.size() + annulled.size();
    }
}
