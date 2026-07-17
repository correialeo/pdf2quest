package com.concursos.study.importer;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reune todo bean {@link ExamParser} do contexto Spring, indexado por banca.
 * Adicionar suporte a uma nova banca e so criar uma nova implementacao de
 * {@link ExamParser} anotada com {@code @Component} - ela aparece aqui
 * automaticamente, sem precisar mexer neste arquivo.
 */
@Component
public class ExamParserRegistry {

    private final Map<Banca, ExamParser> parsersByBanca;

    public ExamParserRegistry(List<ExamParser> parsers) {
        this.parsersByBanca = new EnumMap<>(Banca.class);
        for (ExamParser parser : parsers) {
            parsersByBanca.put(parser.banca(), parser);
        }
    }

    public Optional<ExamParser> find(Banca banca) {
        return Optional.ofNullable(parsersByBanca.get(banca));
    }

    public Set<Banca> supportedBancas() {
        return parsersByBanca.keySet();
    }
}
