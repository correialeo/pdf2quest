package com.pdf2questao.session;

public enum SessionMode {
    PRATICA("Sessão rápida"),
    SIMULADO("Simulado"),
    REFAZER("Refazer questões"),
    /** Prova importada inteira, na ordem ("Resolver a prova"). */
    PROVA("Prova completa");

    private final String label;

    SessionMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
