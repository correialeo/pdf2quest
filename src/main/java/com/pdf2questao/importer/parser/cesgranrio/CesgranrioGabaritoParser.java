package com.pdf2questao.importer.parser.cesgranrio;

import com.pdf2questao.importer.Banca;
import com.pdf2questao.importer.parser.GabaritoTableParser;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser de gabaritos CESGRANRIO. Lida com questoes basicas em linhas simples
 * "1 - A ..." e com tabelas de conhecimentos especificos em varias colunas de
 * "Prova 1", "Prova 2", etc.
 */
@Component
public class CesgranrioGabaritoParser implements GabaritoTableParser {

    private static final Pattern ANSWER_ENTRY = Pattern.compile("(\\d{1,3})\\s*-\\s*([A-E])\\b");
    private static final Pattern PROVA_TOKEN = Pattern.compile("(?i)\\bprova\\s*(\\d{1,2})\\b");
    private static final Pattern DASH_VARIANTS = Pattern.compile("[\\u2010-\\u2015]");

    @Override
    public Banca banca() {
        return Banca.CESGRANRIO;
    }

    @Override
    public Map<Integer, String> parse(String rawText, String titulo) {
        int specificColumn = detectSpecificColumn(rawText, titulo);
        if (specificColumn < 0) {
            return Map.of();
        }

        Map<Integer, String> answers = new LinkedHashMap<>();
        boolean inSpecificSection = false;
        for (String rawLine : rawText.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1)) {
            String line = rawLine.trim();
            String upper = normalize(line);
            if (upper.equals("CONHECIMENTOS ESPECÍFICOS") || upper.equals("CONHECIMENTOS ESPECIFICOS")) {
                inSpecificSection = true;
                continue;
            }

            Matcher matcher = ANSWER_ENTRY.matcher(line);
            int entryIndex = 0;
            while (matcher.find()) {
                int number = Integer.parseInt(matcher.group(1));
                String letter = matcher.group(2).toUpperCase(Locale.ROOT);
                if (number < 36) {
                    answers.put(number, letter);
                } else if (inSpecificSection && entryIndex == specificColumn) {
                    answers.put(number, letter);
                }
                entryIndex++;
            }
        }
        return answers;
    }

    private int detectSpecificColumn(String rawText, String titulo) {
        Matcher provaMatcher = PROVA_TOKEN.matcher(titulo == null ? "" : titulo);
        if (provaMatcher.find()) {
            return Integer.parseInt(provaMatcher.group(1)) - 1;
        }

        String normalizedTitle = normalize(titulo);
        if (normalizedTitle.isBlank()) {
            return 0;
        }

        String[] lines = rawText.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String sectionLine = normalize(lines[i]);
            if (!sectionLine.equals("CONHECIMENTOS ESPECÍFICOS") && !sectionLine.equals("CONHECIMENTOS ESPECIFICOS")) {
                continue;
            }
            for (int j = i + 1; j < Math.min(lines.length, i + 18); j++) {
                String line = normalize(lines[j]);
                if (line.contains(normalizedTitle)) {
                    return countTitlesBeforeMatch(line, normalizedTitle);
                }
            }
        }
        return -1;
    }

    private int countTitlesBeforeMatch(String line, String title) {
        String before = line.substring(0, line.indexOf(title));
        Matcher matcher = Pattern.compile("\\bANALISTA\\b").matcher(before);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private String normalize(String s) {
        if (s == null) {
            return "";
        }
        return DASH_VARIANTS.matcher(s).replaceAll("-")
                .toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }
}
