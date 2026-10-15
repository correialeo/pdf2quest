package com.pdf2questao.importer.structured;

/**
 * Uma resposta do gabarito separado e o que acontece com ela.
 *
 * @param label          onde esta no arquivo ("Linha 4")
 * @param key            ID ou numero lido
 * @param rawAnswer      resposta como escrita no arquivo
 * @param questionId     questao encontrada (nulo = sem correspondencia)
 * @param questionLabel  descricao da questao para a tela
 * @param currentAnswer  gabarito atual da questao ("anulada" se anulada)
 * @param newAnswer      letra normalizada, ou nulo quando a questao sera anulada
 * @param annul          a resposta marca a questao como anulada
 * @param message        explicacao para o usuario (erros, conflitos)
 */
public record AnswerKeyLine(String label, String key, String rawAnswer, Long questionId, String questionLabel,
                            String currentAnswer, String newAnswer, boolean annul, String message) {

    String display() {
        return annul ? "anulada" : newAnswer;
    }
}
