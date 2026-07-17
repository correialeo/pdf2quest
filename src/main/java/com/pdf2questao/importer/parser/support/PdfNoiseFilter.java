package com.pdf2questao.importer.parser.support;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Filtro de ruido de pagina (rodape, marca d'agua, numeracao) reutilizavel
 * por qualquer parser de banca - nenhuma das heuristicas aqui depende do
 * layout especifico de uma banca.
 */
public final class PdfNoiseFilter {

    // Ruido de rodape/marca d'agua que sites como pciconcursos.com.br injetam em
    // toda pagina do PDF. Quando a ultima alternativa de uma questao termina bem
    // no fim de uma pagina, essas linhas caem no meio do bloco e poluem o texto.
    public static final Pattern PAGE_WATERMARK_LINE = Pattern.compile("(?i)^pcimarkpci\\b");
    public static final Pattern PAGE_URL_LINE = Pattern.compile("(?i)^www\\.\\S+$");
    public static final Pattern PAGE_NUMBER_FOOTER = Pattern.compile("(?i)P[\\u00c1A]GINA\\s+\\d{1,4}\\s*$");

    // Numero minimo de repeticoes identicas para uma linha em CAIXA ALTA (ex.:
    // nome da instituicao/prova repetido em todo rodape) ser considerada ruido
    // de pagina generico, sem precisar hardcodar o nome de nenhuma banca.
    public static final int BRANDING_LINE_MIN_LENGTH = 15;
    public static final int BRANDING_LINE_MIN_OCCURRENCES = 3;

    private PdfNoiseFilter() {
    }

    public static boolean isPageNoise(String line, Set<String> repeatedBrandingLines) {
        if (line.isBlank()) {
            return false;
        }
        return PAGE_WATERMARK_LINE.matcher(line).find()
                || PAGE_URL_LINE.matcher(line).matches()
                || PAGE_NUMBER_FOOTER.matcher(line).find()
                || repeatedBrandingLines.contains(line);
    }

    /**
     * Encontra linhas em CAIXA ALTA que se repetem identicas varias vezes no
     * documento - tipicamente o nome da instituicao/prova reimpresso em todo
     * rodape de pagina. Generico o bastante pra nao depender do nome de
     * nenhuma banca especifica.
     */
    public static Set<String> detectRepeatedBrandingLines(String text) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.trim();
            if (line.length() < BRANDING_LINE_MIN_LENGTH || !isAllUpperCaseLetters(line)) {
                continue;
            }
            counts.merge(line, 1, Integer::sum);
        }
        Set<String> branding = new HashSet<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() >= BRANDING_LINE_MIN_OCCURRENCES) {
                branding.add(e.getKey());
            }
        }
        return branding;
    }

    public static boolean isAllUpperCaseLetters(String line) {
        boolean hasLetter = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (Character.isLowerCase(c)) {
                    return false;
                }
            }
        }
        return hasLetter;
    }
}
