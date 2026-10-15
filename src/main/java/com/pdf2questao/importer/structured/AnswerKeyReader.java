package com.pdf2questao.importer.structured;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Le gabaritos separados: JSON ("pdf2questao-gabarito", versao 1) ou texto/CSV com uma resposta por linha
 * ("fcc-2024-q1;B", "12,C", "12 - E", "12 B"). Cabecalho ("id;resposta") e linhas em branco sao ignorados.
 */
public class AnswerKeyReader {

    public static final String FORMAT = "pdf2questao-gabarito";

    /** Chave e resposta separadas por ; , tab | = ou : (a chave pode ter hifens, como os IDs). */
    private static final Pattern SEPARATED = Pattern.compile("^\\s*\"?([^;,\\t|=:\"]+?)\"?\\s*[;,\\t|=:]\\s*\"?([^;,\\t|\"]*?)\"?\\s*(?:[;,\\t|].*)?$");
    /** Numero e resposta: "12 - B", "12) B", "12. B", "12 B". */
    private static final Pattern NUMBERED = Pattern.compile("^\\s*(\\d+)\\s*[-).]?\\s+(\\S+)\\s*$|^\\s*(\\d+)\\s*[-).]\\s*(\\S+)\\s*$");

    record Entry(String label, String key, String answer) {
    }

    List<Entry> read(byte[] content, List<String> errors) {
        String text = TextDecoding.decode(content).strip();
        if (text.startsWith("{")) {
            return json(text, errors);
        }
        List<Entry> entries = new ArrayList<>();
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            String label = "Linha " + (i + 1);
            Matcher m = NUMBERED.matcher(line);
            if (m.matches()) {
                entries.add(new Entry(label, m.group(1) != null ? m.group(1) : m.group(3),
                        m.group(2) != null ? m.group(2) : m.group(4)));
                continue;
            }
            m = SEPARATED.matcher(line);
            if (m.matches() && !m.group(2).isBlank()) {
                if (entries.isEmpty() && isHeader(m.group(1), m.group(2))) {
                    continue;
                }
                entries.add(new Entry(label, m.group(1).trim(), m.group(2).trim()));
                continue;
            }
            errors.add(label + ": não entendi \"" + line.strip() + "\". Use uma resposta por linha, como \"12;B\".");
        }
        if (entries.isEmpty() && errors.isEmpty()) {
            errors.add("O arquivo não tem nenhuma resposta.");
        }
        return entries;
    }

    private static boolean isHeader(String key, String answer) {
        String k = CsvMapping.normalize(key);
        String a = CsvMapping.normalize(answer);
        return (k.equals("id") || k.equals("numero") || k.equals("questao") || k.equals("codigo"))
                && (a.equals("resposta") || a.equals("gabarito"));
    }

    private List<Entry> json(String text, List<String> errors) {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(text);
        } catch (JsonProcessingException e) {
            errors.add("O arquivo não é um JSON válido (linha " + e.getLocation().getLineNr() + ").");
            return List.of();
        }
        if (root.has("formato") && !FORMAT.equals(root.get("formato").asText())) {
            errors.add("Formato \"" + root.get("formato").asText() + "\" desconhecido. Use \"formato\": \"" + FORMAT + "\".");
            return List.of();
        }
        if (!root.has("versao") || root.get("versao").asInt() != 1) {
            errors.add("Informe \"versao\": 1 no gabarito.");
            return List.of();
        }
        JsonNode answers = root.get("respostas");
        if (answers == null || !answers.isArray()) {
            errors.add("O campo \"respostas\" deve ser uma lista.");
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        int i = 0;
        for (JsonNode node : answers) {
            i++;
            String label = "Resposta " + i;
            JsonNode key = node.has("id") ? node.get("id") : node.get("numero");
            boolean annulled = node.path("anulada").asBoolean(false);
            JsonNode answer = node.get("resposta");
            if (key == null || key.isNull() || (!annulled && (answer == null || !answer.isTextual()))) {
                errors.add(label + ": informe \"id\" (ou \"numero\") e \"resposta\".");
                continue;
            }
            entries.add(new Entry(label, key.asText(), annulled ? "ANULADA" : answer.asText()));
        }
        return entries;
    }
}
