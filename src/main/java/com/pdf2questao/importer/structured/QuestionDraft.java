package com.pdf2questao.importer.structured;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Questao escrita por outro programa (o Claude, pelo MCP) antes de virar o JSON de importacao. Sem alternativas =
 * certo/errado. {@code userAnswer}/{@code guess} so no treino (respostas do usuario).
 */
public record QuestionDraft(String statement, List<String> alternatives, String answer, String userAnswer,
                            Boolean guess, String subject, String topic, String explanation, String passage) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** JSON no formato de importacao (docs/importacao/formato-json.md), com ids "prefixo-1", "prefixo-2"... */
    public static byte[] toJson(String title, String banca, String idPrefix, String source, List<QuestionDraft> drafts) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            QuestionDraft q = drafts.get(i);
            boolean multiple = q.alternatives() != null && !q.alternatives().isEmpty();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", idPrefix + "-" + (i + 1));
            m.put("tipo", multiple ? "multipla_escolha" : "certo_errado");
            m.put("enunciado", q.statement());
            if (multiple) {
                m.put("alternativas", q.alternatives());
            }
            m.put("resposta", q.answer());
            m.put("suaResposta", q.userAnswer());
            m.put("chute", q.guess());
            m.put("disciplina", q.subject());
            m.put("assunto", q.topic());
            m.put("explicacao", q.explanation());
            m.put("textoBase", q.passage());
            m.put("fonte", source);
            list.add(m);
        }
        Map<String, Object> exam = new LinkedHashMap<>();
        exam.put("titulo", title);
        exam.put("banca", banca);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("formato", JsonQuestionReader.FORMAT);
        root.put("versao", JsonQuestionReader.VERSION);
        root.put("prova", exam);
        root.put("questoes", list);
        try {
            return MAPPER.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
