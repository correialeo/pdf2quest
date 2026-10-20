package com.pdf2questao.simulado.review;

/**
 * Estado de uma questao gerada. Cada revisao (fidelidade e correcao material) tem o seu; o estado geral junta os
 * dois: so APROVADA quando as duas aprovaram o conteudo atual da questao.
 */
public enum ReviewStatus {
    GERADA("gerada, sem revisão"),
    EM_REVISAO("em revisão: falta informação ou ajuste"),
    APROVADA_FIDELIDADE("aprovada em fidelidade; falta a correção material"),
    APROVADA_CORRECAO("aprovada na correção material; falta a fidelidade"),
    APROVADA("aprovada nas duas revisões"),
    REPROVADA("reprovada: precisa ser corrigida ou reescrita"),
    PENDENTE_FONTE("pendente de fonte: não foi possível confirmar a regra");

    private final String label;

    ReviewStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
