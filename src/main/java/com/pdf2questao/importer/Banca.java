package com.pdf2questao.importer;

/**
 * Bancas organizadoras suportadas (ou planejadas) pela importacao de PDF.
 * Cada parser de prova/gabarito e escrito para o formato de UMA banca
 * especifica - ver {@link ExamParser} e {@link GabaritoTableParser}. Nem
 * toda banca listada aqui tem parser implementado ainda; ver
 * {@link ExamParserRegistry}/{@link GabaritoParserRegistry} pra saber quais
 * estao de fato disponiveis.
 */
public enum Banca {

    FGV("FGV"),
    CESGRANRIO("Cesgranrio"),
    CEBRASPE("Cebraspe"),
    FCC("FCC"),
    VUNESP("Vunesp");

    private final String label;

    Banca(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
