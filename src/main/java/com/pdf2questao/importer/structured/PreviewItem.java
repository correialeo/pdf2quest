package com.pdf2questao.importer.structured;

import com.pdf2questao.question.QuestionType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Uma questao do arquivo depois da validacao: o que vai acontecer com ela se o usuario confirmar. */
public class PreviewItem {

    public enum Status {
        /** Questao nova, sera criada. */
        NOVA,
        /** Mesmo identificador de uma questao ja cadastrada: pula ou atualiza, conforme a escolha do usuario. */
        JA_EXISTE,
        /** Sem identificador em comum, mas o enunciado e igual ao de outra questao: so entra se o usuario pedir. */
        POSSIVEL_DUPLICATA,
        /** Tem erro: nunca e gravada. */
        INVALIDA
    }

    private final StructuredQuestion source;
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private Status status = Status.NOVA;
    private QuestionType type;
    /** Letra A-E -> texto, ja normalizado (Certo/Errado vira C/E). */
    private Map<String, String> alternatives = new LinkedHashMap<>();
    /** Letra correta normalizada; nula se o arquivo nao trouxe gabarito ou a questao e anulada. */
    private String answer;
    /** Resposta do usuario ja normalizada para a letra (null = nao respondeu ou resposta invalida). */
    private String userAnswer;
    /** Questao cadastrada que motivou JA_EXISTE ou POSSIVEL_DUPLICATA. */
    private Long existingQuestionId;
    private String duplicateOf;

    PreviewItem(StructuredQuestion source) {
        this.source = source;
    }

    public StructuredQuestion getSource() { return source; }
    public List<String> getErrors() { return errors; }
    public List<String> getWarnings() { return warnings; }
    public Status getStatus() { return status; }
    void setStatus(Status status) { this.status = status; }
    public QuestionType getType() { return type; }
    void setType(QuestionType type) { this.type = type; }
    public Map<String, String> getAlternatives() { return alternatives; }
    void setAlternatives(Map<String, String> alternatives) { this.alternatives = alternatives; }
    public String getAnswer() { return answer; }
    public String getUserAnswer() { return userAnswer; }
    void setUserAnswer(String userAnswer) { this.userAnswer = userAnswer; }
    void setAnswer(String answer) { this.answer = answer; }
    public Long getExistingQuestionId() { return existingQuestionId; }
    void setExistingQuestionId(Long id) { this.existingQuestionId = id; }
    /** Descricao de quem e a possivel duplicata ("Questão 2 deste arquivo" ou "questão #15 do banco"). */
    public String getDuplicateOf() { return duplicateOf; }
    void setDuplicateOf(String duplicateOf) { this.duplicateOf = duplicateOf; }

    void error(String message) {
        errors.add(message);
        status = Status.INVALIDA;
    }
}
