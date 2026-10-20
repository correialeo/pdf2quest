package com.pdf2questao.importer.structured;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Questao lida de um arquivo JSON ou CSV, ainda sem validar. Os leitores so convertem o arquivo para este
 * formato comum; quem decide se a questao e valida e o {@link StructuredQuestionValidator}.
 * Texto vazio vira nulo; {@link #readErrors} guarda o que o leitor nao conseguiu converter (ex.: ano "dois mil").
 */
public class StructuredQuestion {

    /** Onde a questao esta no arquivo, para as mensagens: "Questão 3" (JSON) ou "Linha 5" (CSV). */
    String label;
    String externalId;
    Integer number;
    String type;
    String statement;
    String passage;
    /** Letra (A-E) -> texto, na ordem do arquivo. Letra nula = o arquivo nao informou (recebe A, B, C... ). */
    List<String[]> alternatives = new ArrayList<>();
    String answer;
    boolean annulled;
    /** Resposta que o usuario deu fora do sistema (ex.: treino no chat); vira uma sessao ja corrigida. */
    String userAnswer;
    /** "E chute?" da resposta do usuario; null = nao informado. */
    Boolean userGuess;
    String explanation;
    String organization;
    String exam;
    Integer year;
    String subject;
    String topic;
    List<String> subtopics = new ArrayList<>();
    List<String> tags = new ArrayList<>();
    String difficulty;
    String category;
    String source;
    List<String> references = new ArrayList<>();
    Map<String, Object> metadata = new LinkedHashMap<>();
    final List<String> readErrors = new ArrayList<>();
    final List<String> readWarnings = new ArrayList<>();

    public String getLabel() { return label; }
    public String getExternalId() { return externalId; }
    public Integer getNumber() { return number; }
    public String getStatement() { return statement; }
    public List<String[]> getAlternatives() { return alternatives; }
    public String getAnswer() { return answer; }
    public boolean isAnnulled() { return annulled; }
    public String getSubject() { return subject; }
    public String getTopic() { return topic; }
    public String getOrganization() { return organization; }
    public Integer getYear() { return year; }
}
