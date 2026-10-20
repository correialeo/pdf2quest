package com.pdf2questao.simulado.review;

/**
 * Natureza do conteudo cobrado, que decide como a resposta precisa ser verificada. As que dependem de texto legal,
 * norma ou versao exigem fonte com dispositivo, data de consulta e vigencia confirmada.
 */
public enum ContentType {
    LEGISLACAO(true, true),
    JURISPRUDENCIA(true, true),
    NORMA_REGULAMENTAR(true, true),
    FINANCAS_PUBLICAS_CONTABILIDADE(true, true),
    NORMA_TECNICA(true, false),
    TECNOLOGIA_VERSIONADA(true, false),
    DADOS_ESTATISTICA_ML(false, false),
    METODOLOGIA_MODELO(false, false),
    CONCEITO_ESTAVEL(false, false);

    private final boolean needsSource;
    private final boolean needsPrimarySource;

    ContentType(boolean needsSource, boolean needsPrimarySource) {
        this.needsSource = needsSource;
        this.needsPrimarySource = needsPrimarySource;
    }

    /** Precisa de fonte consultada (regra que muda com o tempo ou com a versao). */
    public boolean needsSource() {
        return needsSource;
    }

    /** A fonte tem que ser primaria oficial (Planalto, DOU, tribunal, orgao regulador). */
    public boolean needsPrimarySource() {
        return needsPrimarySource;
    }
}
