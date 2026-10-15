package com.pdf2questao.importer.structured;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Le o formato JSON nativo ("pdf2questao-questoes", versao 1; ver docs/importacao/formato-json.md).
 * Confere o tipo de cada campo e preenche {@link StructuredQuestion}; regras de conteudo (alternativas,
 * resposta, duplicatas) ficam no {@link StructuredQuestionValidator}.
 */
public class JsonQuestionReader {

    public static final String FORMAT = "pdf2questao-questoes";
    public static final int VERSION = 1;

    private static final Set<String> ROOT_FIELDS = Set.of("formato", "versao", "prova", "questoes");
    private static final Set<String> EXAM_FIELDS = Set.of("titulo", "banca", "concurso", "ano", "disciplina");
    private static final Set<String> QUESTION_FIELDS = Set.of("id", "numero", "tipo", "enunciado", "textoBase",
            "alternativas", "resposta", "anulada", "explicacao", "banca", "concurso", "ano", "disciplina",
            "assunto", "subtopicos", "tags", "dificuldade", "categoria", "fonte", "referencias", "metadados");

    private final ObjectMapper mapper = new ObjectMapper();

    /** Preenche o preview com as questoes lidas ou com o erro que impediu a leitura. */
    void read(byte[] content, ImportPreview preview) {
        JsonNode root;
        try {
            root = mapper.readTree(TextDecoding.decode(content));
        } catch (JsonProcessingException e) {
            preview.getFileErrors().add("O arquivo não é um JSON válido (linha " + e.getLocation().getLineNr()
                    + ", coluna " + e.getLocation().getColumnNr() + "). Confira vírgulas, aspas e chaves.");
            return;
        }
        if (root == null || !root.isObject()) {
            preview.getFileErrors().add("O JSON deve começar com { e ter o campo \"questoes\".");
            return;
        }
        JsonNode format = root.get("formato");
        if (format == null) {
            preview.getFileWarnings().add("Campo \"formato\" ausente; o arquivo foi lido como \"" + FORMAT + "\".");
        } else if (!FORMAT.equals(format.asText())) {
            preview.getFileErrors().add("Formato \"" + format.asText() + "\" desconhecido. Use \"formato\": \""
                    + FORMAT + "\".");
            return;
        }
        JsonNode version = root.get("versao");
        if (version == null) {
            preview.getFileErrors().add("Informe a versão do formato: \"versao\": " + VERSION + ".");
            return;
        }
        if (!version.canConvertToInt() || version.asInt() != VERSION) {
            preview.getFileErrors().add("Versão " + version.asText() + " do formato não é suportada. "
                    + "Esta versão do PDF2Questão lê a versão " + VERSION + ".");
            return;
        }
        unknownFields(root, ROOT_FIELDS, "", preview.getFileWarnings());

        StructuredQuestion defaults = new StructuredQuestion();
        JsonNode exam = root.get("prova");
        if (exam != null && !exam.isNull()) {
            if (!exam.isObject()) {
                preview.getFileErrors().add("\"prova\" deve ser um objeto: { \"titulo\": ..., \"banca\": ... }.");
                return;
            }
            unknownFields(exam, EXAM_FIELDS, "prova.", preview.getFileWarnings());
            preview.setTitle(text(exam, "titulo", defaults));
            defaults.organization = text(exam, "banca", defaults);
            defaults.exam = text(exam, "concurso", defaults);
            defaults.year = integer(exam, "ano", defaults);
            defaults.subject = text(exam, "disciplina", defaults);
            defaults.readErrors.forEach(e -> preview.getFileErrors().add("Em \"prova\": " + e));
            preview.setBanca(defaults.organization);
        }

        JsonNode questions = root.get("questoes");
        if (questions == null || !questions.isArray()) {
            preview.getFileErrors().add("O campo \"questoes\" deve ser uma lista: \"questoes\": [ ... ].");
            return;
        }
        if (questions.isEmpty()) {
            preview.getFileErrors().add("A lista \"questoes\" está vazia.");
            return;
        }
        List<StructuredQuestion> result = new ArrayList<>();
        int index = 0;
        for (JsonNode node : questions) {
            index++;
            result.add(question(node, "Questão " + index, defaults));
        }
        preview.setQuestions(result);
    }

    private StructuredQuestion question(JsonNode node, String label, StructuredQuestion defaults) {
        StructuredQuestion q = new StructuredQuestion();
        q.label = label;
        if (!node.isObject()) {
            q.readErrors.add("Cada item de \"questoes\" deve ser um objeto { ... }.");
            return q;
        }
        unknownFields(node, QUESTION_FIELDS, "", q.readWarnings);
        q.externalId = text(node, "id", q);
        q.number = integer(node, "numero", q);
        q.type = text(node, "tipo", q);
        q.statement = text(node, "enunciado", q);
        q.passage = text(node, "textoBase", q);
        q.explanation = text(node, "explicacao", q);
        q.organization = orDefault(text(node, "banca", q), defaults.organization);
        q.exam = orDefault(text(node, "concurso", q), defaults.exam);
        Integer year = integer(node, "ano", q);
        q.year = year != null ? year : defaults.year;
        q.subject = orDefault(text(node, "disciplina", q), defaults.subject);
        q.topic = text(node, "assunto", q);
        q.subtopics = textList(node, "subtopicos", q);
        q.tags = textList(node, "tags", q);
        q.difficulty = text(node, "dificuldade", q);
        q.category = text(node, "categoria", q);
        q.source = text(node, "fonte", q);
        q.references = textList(node, "referencias", q);

        JsonNode annulled = node.get("anulada");
        if (annulled != null && !annulled.isNull()) {
            if (annulled.isBoolean()) {
                q.annulled = annulled.booleanValue();
            } else {
                q.readErrors.add("\"anulada\" deve ser true ou false.");
            }
        }
        answer(node.get("resposta"), q);
        alternatives(node.get("alternativas"), q);

        JsonNode metadata = node.get("metadados");
        if (metadata != null && !metadata.isNull()) {
            if (metadata.isObject()) {
                q.metadata = mapper.convertValue(metadata, Map.class);
            } else {
                q.readErrors.add("\"metadados\" deve ser um objeto { \"chave\": \"valor\" }.");
            }
        }
        return q;
    }

    private void answer(JsonNode node, StructuredQuestion q) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            if (node.size() > 1) {
                q.readErrors.add("Mais de uma resposta correta ainda não é suportada; informe só uma em \"resposta\".");
                return;
            }
            node = node.isEmpty() ? null : node.get(0);
            if (node == null) {
                return;
            }
        }
        if (!node.isTextual()) {
            q.readErrors.add("\"resposta\" deve ser um texto, como \"B\" ou \"Certo\".");
            return;
        }
        q.answer = blankToNull(node.asText());
    }

    private void alternatives(JsonNode node, StructuredQuestion q) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isArray()) {
            q.readErrors.add("\"alternativas\" deve ser uma lista.");
            return;
        }
        for (JsonNode alt : node) {
            if (alt.isTextual()) {
                q.alternatives.add(new String[]{null, alt.asText()});
            } else if (alt.isObject()) {
                JsonNode letter = alt.get("letra");
                JsonNode text = alt.get("texto");
                if (text == null || !text.isTextual()) {
                    q.readErrors.add("Toda alternativa precisa de \"texto\".");
                    continue;
                }
                q.alternatives.add(new String[]{letter == null || letter.isNull() ? null : letter.asText(), text.asText()});
            } else {
                q.readErrors.add("Cada alternativa deve ser um texto ou { \"letra\": \"A\", \"texto\": \"...\" }.");
            }
        }
    }

    private static String text(JsonNode node, String field, StructuredQuestion q) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual() || value.isNumber()) {
            return blankToNull(value.asText());
        }
        q.readErrors.add("\"" + field + "\" deve ser um texto.");
        return null;
    }

    private static Integer integer(JsonNode node, String field, StructuredQuestion q) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            return value.asInt();
        }
        if (value.isTextual() && value.asText().trim().matches("\\d{1,9}")) {
            return Integer.parseInt(value.asText().trim());
        }
        if (value.isTextual() && value.asText().isBlank()) {
            return null;
        }
        q.readErrors.add("\"" + field + "\" deve ser um número inteiro (recebido: " + value + ").");
        return null;
    }

    private static List<String> textList(JsonNode node, String field, StructuredQuestion q) {
        JsonNode value = node.get(field);
        List<String> list = new ArrayList<>();
        if (value == null || value.isNull()) {
            return list;
        }
        if (value.isTextual()) {
            String text = blankToNull(value.asText());
            if (text != null) {
                list.add(text);
            }
            return list;
        }
        if (!value.isArray()) {
            q.readErrors.add("\"" + field + "\" deve ser uma lista de textos.");
            return list;
        }
        for (JsonNode item : value) {
            if (item.isTextual() || item.isNumber()) {
                String text = blankToNull(item.asText());
                if (text != null) {
                    list.add(text);
                }
            } else {
                q.readErrors.add("\"" + field + "\" deve ter apenas textos.");
            }
        }
        return list;
    }

    private static void unknownFields(JsonNode node, Set<String> known, String prefix, List<String> warnings) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!known.contains(name)) {
                warnings.add("Campo \"" + prefix + name + "\" desconhecido foi ignorado.");
            }
        }
    }

    private static String orDefault(String value, String fallback) {
        return value != null ? value : fallback;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
