package com.pdf2questao.importer.structured;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Gabarito separado lido e cruzado com as questoes do banco, aguardando a confirmacao do usuario. */
public class AnswerKeyPreview {

    public enum Mode {
        /** Pelo ID estavel das questoes importadas por JSON/CSV. */
        ID,
        /** Pelo numero da questao numa prova escolhida pelo usuario. Exige confirmacao explicita. */
        NUMERO
    }

    private final String token;
    private final Mode mode;
    private final Long importJobId;
    private final String fileName;
    private final LocalDateTime createdAt = LocalDateTime.now();
    private final List<String> fileErrors = new ArrayList<>();
    final List<AnswerKeyLine> toApply = new ArrayList<>();
    final List<AnswerKeyLine> unchanged = new ArrayList<>();
    final List<AnswerKeyLine> conflicts = new ArrayList<>();
    final List<AnswerKeyLine> invalid = new ArrayList<>();
    final List<AnswerKeyLine> unmatched = new ArrayList<>();
    final List<String> questionsWithoutAnswer = new ArrayList<>();

    AnswerKeyPreview(String token, Mode mode, Long importJobId, String fileName) {
        this.token = token;
        this.mode = mode;
        this.importJobId = importJobId;
        this.fileName = fileName;
    }

    public String getToken() { return token; }
    public Mode getMode() { return mode; }
    public Long getImportJobId() { return importJobId; }
    public String getFileName() { return fileName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public List<String> getFileErrors() { return fileErrors; }
    /** Questoes sem gabarito hoje que vao receber resposta. */
    public List<AnswerKeyLine> getToApply() { return toApply; }
    /** Questoes que ja tem exatamente esta resposta. */
    public List<AnswerKeyLine> getUnchanged() { return unchanged; }
    /** Questoes que ja tem OUTRA resposta: so mudam se o usuario pedir. */
    public List<AnswerKeyLine> getConflicts() { return conflicts; }
    /** Respostas que nao servem para a questao ou repetidas no arquivo com valores diferentes: nunca aplicadas. */
    public List<AnswerKeyLine> getInvalid() { return invalid; }
    /** Respostas sem questao correspondente. */
    public List<AnswerKeyLine> getUnmatched() { return unmatched; }
    /** Questoes (da prova escolhida ou das provas atingidas) que continuam sem resposta. */
    public List<String> getQuestionsWithoutAnswer() { return questionsWithoutAnswer; }

    public boolean isApplicable() {
        return fileErrors.isEmpty() && (!toApply.isEmpty() || !conflicts.isEmpty());
    }
}
