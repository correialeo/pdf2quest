package com.concursos.study.importer.parser;

import com.concursos.study.importer.Banca;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reune todo bean {@link GabaritoTableParser} do contexto Spring, indexado
 * por banca. Ver {@link ExamParserRegistry} para o mesmo padrao aplicado ao
 * parser de prova.
 */
@Component
public class GabaritoParserRegistry {

    private final Map<Banca, GabaritoTableParser> parsersByBanca;

    public GabaritoParserRegistry(List<GabaritoTableParser> parsers) {
        this.parsersByBanca = new EnumMap<>(Banca.class);
        for (GabaritoTableParser parser : parsers) {
            parsersByBanca.put(parser.banca(), parser);
        }
    }

    public Optional<GabaritoTableParser> find(Banca banca) {
        return Optional.ofNullable(parsersByBanca.get(banca));
    }

    public Set<Banca> supportedBancas() {
        return parsersByBanca.keySet();
    }
}
