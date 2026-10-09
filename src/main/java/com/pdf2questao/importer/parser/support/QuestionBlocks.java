package com.pdf2questao.importer.parser.support;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QuestionBlocks {

    /** "Texto II", "Text I": fronteira entre a ultima alternativa e um novo texto de apoio. */
    public static final Pattern PASSAGE_TITLE =
            Pattern.compile("^(?:TEXTO|Texto|TEXT|Text)\\s+(?:[IVX]+|\\d{1,2})\\s*$");

    private static final Pattern CUE_NEXT_N = Pattern.compile(
            "(?:next|following|pr[oó]xim[ao]s|seguintes)\\s+(\\p{L}+|\\d{1,2})\\s+(?:quest|item|itens)");
    private static final Pattern CUE_RANGE = Pattern.compile(
            "quest\\p{L}*\\s+(?:de\\s+)?(\\d{1,3})\\s+(?:a|à|ate|até|to|-|–)\\s+(\\d{1,3})");
    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
            Map.entry("nine", 9), Map.entry("ten", 10),
            Map.entry("uma", 1), Map.entry("duas", 2), Map.entry("dois", 2), Map.entry("tres", 3),
            Map.entry("quatro", 4), Map.entry("cinco", 5), Map.entry("seis", 6), Map.entry("sete", 7),
            Map.entry("oito", 8), Map.entry("nove", 9), Map.entry("dez", 10));

    public record Split(String statement, Map<String, String> alternatives) {
    }

    private QuestionBlocks() {
    }

    /** Decide sobre o texto visivel; o conteudo devolvido mantem a marcacao. */
    public static Split split(List<String> richLines, Pattern alternativeLine) {
        List<String> statementLines = new ArrayList<>();
        Map<String, List<String>> altLines = new LinkedHashMap<>();
        List<String> currentAlt = null;
        for (String rich : richLines) {
            Matcher m = alternativeLine.matcher(RichText.plain(rich));
            if (m.lookingAt()) {
                currentAlt = new ArrayList<>();
                // Letra repetida (ex.: "(A)" dentro de um texto de apoio) nao sobrescreve a primeira.
                altLines.putIfAbsent(m.group(1), currentAlt);
                currentAlt.add(RichText.fromVisible(rich, m.start(2)));
            } else if (currentAlt != null) {
                currentAlt.add(rich);
            } else {
                statementLines.add(rich);
            }
        }
        Map<String, String> alternatives = new LinkedHashMap<>();
        altLines.forEach((letter, lines) ->
                alternatives.put(letter, join(lines).replaceAll("\\s+", " ")));
        return new Split(join(statementLines), alternatives);
    }

    public static String join(List<String> richLines) {
        return RichText.trim(RichText.removePageTokens(String.join("\n", richLines)));
    }

    public static boolean hasVisibleText(List<String> richLines) {
        return richLines.stream().anyMatch(l -> !RichText.plain(l).isBlank() || l.contains("⟦img:"));
    }

    /** Quantas questoes a instrucao do texto de apoio diz cobrir, ou -1. */
    public static int cueCount(String plainLine) {
        String normalized = Normalizer.normalize(plainLine.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        Matcher range = CUE_RANGE.matcher(normalized);
        if (range.find()) {
            int from = Integer.parseInt(range.group(1));
            int to = Integer.parseInt(range.group(2));
            return to >= from ? to - from + 1 : -1;
        }
        Matcher next = CUE_NEXT_N.matcher(normalized);
        if (next.find()) {
            String n = next.group(1);
            if (n.chars().allMatch(Character::isDigit)) {
                return Integer.parseInt(n);
            }
            return NUMBER_WORDS.getOrDefault(n, -1);
        }
        return -1;
    }
}
