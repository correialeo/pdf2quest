package com.pdf2questao.importer.parser.fgv;

import com.pdf2questao.importer.Banca;
import com.pdf2questao.importer.parser.GabaritoTableParser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser de PDFs de gabarito da banca FGV que trazem varias provas/perfis no
 * mesmo arquivo. Localiza o titulo do perfil buscado e le a tabela de
 * respostas logo em seguida: uma linha com os numeros das questoes (ex.:
 * "1 2 3 ... 20"), seguida pelas letras (A-E) correspondentes - que podem
 * vir todas numa unica linha ou uma por linha, dependendo de como o PDF foi
 * extraido - repetindo em blocos ate o fim da prova.
 */
@Component
public class FgvGabaritoParser implements GabaritoTableParser {

    private static final Pattern NUMBER_HEADER_LINE = Pattern.compile("^(\\d{1,3})(\\s+\\d{1,3})*$");
    private static final Pattern SINGLE_LETTER = Pattern.compile("^[A-E]$");
    private static final Pattern DASH_VARIANTS = Pattern.compile("[\\u2010-\\u2015]");

    @Override
    public Banca banca() {
        return Banca.FGV;
    }

    @Override
    public Map<Integer, String> parse(String rawText, String titulo) {
        String[] lines = rawText.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        int titleIndex = findTitleIndex(lines, titulo);
        if (titleIndex < 0) {
            return Map.of();
        }

        Map<Integer, String> answers = new LinkedHashMap<>();
        int i = titleIndex + 1;
        while (i < lines.length) {
            String line = lines[i].trim();
            if (line.isBlank()) {
                i++;
                continue;
            }
            Matcher headerMatch = NUMBER_HEADER_LINE.matcher(line);
            if (!headerMatch.matches()) {
                break;
            }
            List<Integer> numbers = new ArrayList<>();
            for (String token : line.split("\\s+")) {
                numbers.add(Integer.parseInt(token));
            }
            i++;

            List<String> letters = new ArrayList<>();
            while (i < lines.length && letters.size() < numbers.size()) {
                String candidate = lines[i].trim();
                if (candidate.isBlank()) {
                    i++;
                    continue;
                }
                String[] tokens = candidate.split("\\s+");
                boolean allLetters = true;
                for (String t : tokens) {
                    if (!SINGLE_LETTER.matcher(t).matches()) {
                        allLetters = false;
                        break;
                    }
                }
                if (!allLetters) {
                    break;
                }
                for (String t : tokens) {
                    if (letters.size() < numbers.size()) {
                        letters.add(t);
                    }
                }
                i++;
            }

            int pairs = Math.min(numbers.size(), letters.size());
            for (int k = 0; k < pairs; k++) {
                answers.put(numbers.get(k), letters.get(k));
            }
        }
        return answers;
    }

    private int findTitleIndex(String[] lines, String titulo) {
        String needle = normalize(titulo);
        for (int i = 0; i < lines.length; i++) {
            if (normalize(lines[i]).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private String normalize(String s) {
        String withoutDashVariants = DASH_VARIANTS.matcher(s).replaceAll("-");
        return withoutDashVariants.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
