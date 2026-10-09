package com.pdf2questao.importer.parser.cebraspe;

import com.pdf2questao.importer.Banca;
import com.pdf2questao.importer.parser.GabaritoTableParser;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Gabarito CEBRASPE: uma pagina por cargo, com linhas de numeros de item seguidas
 * da linha de respostas (C/E) e o titulo ("CARGO 7: ...", "CONHECIMENTO GERAIS
 * PARA OS CARGOS DE NIVEL SUPERIOR") aparecendo depois da tabela. Como gerais e
 * especificos ficam em paginas separadas, o titulo aceita varios trechos
 * separados por ";" e junta as respostas de todas as paginas encontradas.
 */
@Component
public class CebraspeGabaritoParser implements GabaritoTableParser {

    private static final Pattern NUMBERS_ROW = Pattern.compile("^\\d{1,3}(\\s+\\d{1,3})+$");
    private static final Pattern ANSWERS_ROW = Pattern.compile("^[CEX*0](\\s+[CEX*0])+$");
    private static final Pattern PAGE_TITLE = Pattern.compile("^(CARGO\\s+\\d+|CONHECIMENTOS?\\s+GERAIS)\\b.*");

    @Override
    public Banca banca() {
        return Banca.CEBRASPE;
    }

    @Override
    public Map<Integer, String> parse(String rawText, String titulo) {
        List<String> wanted = Arrays.stream((titulo == null ? "" : titulo).split(";"))
                .map(CebraspeGabaritoParser::normalize)
                .filter(t -> !t.isBlank())
                .toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }

        Map<Integer, String> result = new LinkedHashMap<>();
        Map<Integer, String> page = new LinkedHashMap<>();
        String[] numbers = null;
        for (String rawLine : rawText.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1)) {
            String line = rawLine.trim();
            if (NUMBERS_ROW.matcher(line).matches()) {
                numbers = line.split("\\s+");
                continue;
            }
            if (numbers != null && ANSWERS_ROW.matcher(line).matches()) {
                String[] answers = line.split("\\s+");
                for (int i = 0; i < Math.min(numbers.length, answers.length); i++) {
                    int number = Integer.parseInt(numbers[i]);
                    if (number > 0 && (answers[i].equals("C") || answers[i].equals("E"))) {
                        page.put(number, answers[i]);
                    }
                }
                numbers = null;
                continue;
            }
            String normalized = normalize(line);
            if (PAGE_TITLE.matcher(normalized).matches()) {
                if (wanted.stream().anyMatch(normalized::contains)) {
                    result.putAll(page);
                }
                page = new LinkedHashMap<>();
            }
        }
        return result;
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[\\u2010-\\u2015]", "-")
                .toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }
}
