package com.pdf2questao.importer.parser.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deteccao de metadados (banca organizadora, ano) a partir do texto bruto de
 * uma prova - reutilizavel por qualquer parser de banca, ja que a lista de
 * organizacoes conhecidas nao depende do layout especifico de nenhuma delas.
 */
public final class PdfMetadataDetector {

    public static final String[] KNOWN_ORGANIZATIONS = {
            "CEBRASPE", "CESPE", "CESGRANRIO", "FCC", "FGV", "VUNESP",
            "IBFC", "IADES", "INSTITUTO AOCP", "AOCP", "QUADRIX", "CONSULPLAN", "IDECAN",
            "IBADE", "CETRO", "SELECON", "IBGP", "LEGALLE", "OBJETIVA", "AVANÇA SP", "IUDS"
    };

    public static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");

    private PdfMetadataDetector() {
    }

    public static String detectOrganization(String text) {
        // Usa borda de palavra para nao confundir bancas com palavras comuns que
        // contenham o mesmo texto (ex.: "OBJETIVA" dentro de "questoes objetivas").
        String best = null;
        int bestIndex = Integer.MAX_VALUE;
        for (String org : KNOWN_ORGANIZATIONS) {
            Matcher m = Pattern.compile("\\b" + Pattern.quote(org) + "\\b", Pattern.CASE_INSENSITIVE).matcher(text);
            if (m.find() && m.start() < bestIndex) {
                bestIndex = m.start();
                best = org;
            }
        }
        return best;
    }

    public static Integer detectYear(String text) {
        Matcher m = YEAR.matcher(text);
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        while (m.find()) {
            int y = Integer.parseInt(m.group());
            counts.merge(y, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}
