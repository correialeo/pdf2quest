package com.concursos.study.importer.parser.support;

import java.util.Locale;
import java.util.Set;

/**
 * Utilitarios de formatacao de texto reutilizaveis por qualquer parser de
 * banca - nao dependem de nenhuma heuristica especifica de layout de PDF.
 */
public final class PdfTextUtils {

    // Preposicoes/conectivos minusculos aceitos dentro de um titulo de disciplina
    // (ex.: "Legislacao Acerca de Seguranca da Informacao").
    public static final Set<String> LOWER_CONNECTORS =
            Set.of("de", "da", "do", "das", "dos", "e", "em", "a", "o", "as", "os", "ao", "aos");

    private PdfTextUtils() {
    }

    public static String toTitleCase(String line) {
        String[] words = line.toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder sb = new StringBuilder();
        boolean isFirstWord = true;
        for (String w : words) {
            if (w.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            // Preposicoes/conectivos ficam minusculos, exceto quando abrem o titulo
            // (ex.: "Legislacao Acerca de Seguranca da Informacao", nao "... De ...").
            if (!isFirstWord && LOWER_CONNECTORS.contains(w)) {
                sb.append(w);
            } else {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
            isFirstWord = false;
        }
        return sb.toString();
    }
}
