package com.pdf2questao.importer.parser.fcc;

import com.pdf2questao.importer.Banca;
import com.pdf2questao.importer.parser.GabaritoEntries;
import com.pdf2questao.importer.parser.GabaritoTableParser;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gabarito FCC (publicacao preliminar impressa do navegador): uma tabela
 * "Questao Resposta Situacao" com uma linha por questao, repetida a cada pagina,
 * terminada por "Total de questoes: N". A coluna Situacao diz se o gabarito ficou
 * sem alteracao, foi alterado (a letra da linha ja e a nova) ou se a questao foi
 * anulada - nesse caso ela entra em {@link GabaritoEntries#annulled()}, nunca e
 * descartada. Ruido do navegador entre paginas ("3/3/26, 10:23 PM about:blank",
 * "about:blank 6/13") nao casa com o formato de linha e e ignorado. Um PDF tem
 * uma unica tabela, entao o {@code titulo} nao e usado.
 */
@Component
public class FccGabaritoParser implements GabaritoTableParser {

    private static final Pattern ROW = Pattern.compile("^(\\d{1,3})\\s+(\\S+)(?:\\s+(.*))?$");
    private static final Pattern LETTER = Pattern.compile("^[A-E]$");
    private static final Pattern TOTAL = Pattern.compile("(?i)total\\s+de\\s+quest\\S*\\s*:?\\s*(\\d+)");
    private static final Pattern ANNULLED = Pattern.compile("(?i)anulad");
    private static final Pattern CHANGED = Pattern.compile("(?i)altera");
    private static final Pattern HEADER = Pattern.compile("(?i)^quest\\S*\\s+resposta");

    @Override
    public Banca banca() {
        return Banca.FCC;
    }

    @Override
    public Map<Integer, String> parse(String rawText, String titulo) {
        return parseEntries(rawText, titulo).answers();
    }

    @Override
    public GabaritoEntries parseEntries(String rawText, String titulo) {
        Map<Integer, String> answers = new LinkedHashMap<>();
        Set<Integer> annulled = new LinkedHashSet<>();
        Set<Integer> changed = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        Integer declaredTotal = null;

        for (String rawLine : rawText.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1)) {
            String line = rawLine.replace(' ', ' ').trim();
            if (line.isEmpty() || HEADER.matcher(line).find()) {
                continue;
            }
            Matcher total = TOTAL.matcher(line);
            if (total.find()) {
                declaredTotal = Integer.parseInt(total.group(1));
                continue;
            }
            Matcher row = ROW.matcher(line);
            if (!row.matches()) {
                continue;
            }
            int number = Integer.parseInt(row.group(1));
            String answerToken = row.group(2).toUpperCase(Locale.ROOT);
            String situation = row.group(3) == null ? "" : normalize(row.group(3));
            if (!isSituation(answerToken, situation)) {
                continue;
            }
            if (answers.containsKey(number) || annulled.contains(number)) {
                warnings.add("Questao " + number + " aparece mais de uma vez no gabarito.");
                continue;
            }
            if (ANNULLED.matcher(answerToken).find() || ANNULLED.matcher(situation).find()) {
                annulled.add(number);
            } else if (LETTER.matcher(answerToken).matches()) {
                answers.put(number, answerToken);
                if (CHANGED.matcher(situation).find() && !situation.contains("SEM ALTERA")) {
                    changed.add(number);
                }
            } else {
                warnings.add("Questao " + number + ": resposta '" + row.group(2) + "' nao reconhecida.");
            }
        }

        if (declaredTotal == null) {
            warnings.add("O PDF nao traz 'Total de questoes'; nao foi possivel validar o total lido.");
        } else if (declaredTotal != answers.size() + annulled.size()) {
            warnings.add("Total lido (" + (answers.size() + annulled.size()) + ") difere do 'Total de questoes: "
                    + declaredTotal + "' do PDF.");
        }
        return new GabaritoEntries(answers, annulled, changed, declaredTotal, warnings);
    }

    /** Linha de tabela de verdade: letra ou "anulada" na resposta, ou uma situacao conhecida. */
    private boolean isSituation(String answerToken, String situation) {
        return LETTER.matcher(answerToken).matches()
                || ANNULLED.matcher(answerToken).find()
                || ANNULLED.matcher(situation).find()
                || situation.startsWith("GABARITO");
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
