package com.pdf2questao.importer.structured;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Resultado da leitura + validacao de um arquivo, guardado em memoria ate o usuario confirmar ou desistir.
 * Nada daqui foi gravado no banco.
 */
public class ImportPreview {

    private final String token;
    private final String format;
    private final String fileName;
    private final LocalDateTime createdAt = LocalDateTime.now();
    /** Problemas que impedem ler o arquivo inteiro (JSON quebrado, versao desconhecida...). */
    private final List<String> fileErrors = new ArrayList<>();
    private final List<String> fileWarnings = new ArrayList<>();
    private List<StructuredQuestion> questions = new ArrayList<>();
    private List<PreviewItem> items = new ArrayList<>();
    private String title;
    private String banca;

    ImportPreview(String token, String format, String fileName) {
        this.token = token;
        this.format = format;
        this.fileName = fileName;
    }

    public String getToken() { return token; }
    public String getFormat() { return format; }
    public String getFileName() { return fileName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public List<String> getFileErrors() { return fileErrors; }
    public List<String> getFileWarnings() { return fileWarnings; }
    public List<StructuredQuestion> getQuestions() { return questions; }
    void setQuestions(List<StructuredQuestion> questions) { this.questions = questions; }
    public List<PreviewItem> getItems() { return items; }
    void setItems(List<PreviewItem> items) { this.items = items; }
    public String getTitle() { return title; }
    void setTitle(String title) { this.title = title; }
    public String getBanca() { return banca; }
    void setBanca(String banca) { this.banca = banca; }

    public long count(PreviewItem.Status status) {
        return items.stream().filter(i -> i.getStatus() == status).count();
    }

    public long getNewCount() { return count(PreviewItem.Status.NOVA); }
    public long getExistingCount() { return count(PreviewItem.Status.JA_EXISTE); }
    public long getDuplicateCount() { return count(PreviewItem.Status.POSSIVEL_DUPLICATA); }
    public long getInvalidCount() { return count(PreviewItem.Status.INVALIDA); }

    public long getWithoutAnswerCount() {
        return items.stream().filter(i -> i.getStatus() != PreviewItem.Status.INVALIDA
                && i.getAnswer() == null && !i.getSource().isAnnulled()).count();
    }

    /** Ha algo que poderia ser gravado? */
    public boolean isImportable() {
        return fileErrors.isEmpty() && items.stream().anyMatch(i -> i.getStatus() != PreviewItem.Status.INVALIDA);
    }
}
