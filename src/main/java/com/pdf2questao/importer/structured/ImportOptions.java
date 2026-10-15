package com.pdf2questao.importer.structured;

/**
 * Escolhas do usuario na tela de previa.
 *
 * @param title              nome da prova na lista de provas (em branco: titulo do arquivo ou nome do arquivo)
 * @param updateExisting     questoes com ID ja cadastrado: true atualiza o conteudo, false pula
 * @param includeDuplicates  questoes com enunciado igual a outra: true importa mesmo assim, false pula
 * @param acceptInvalid      o usuario viu que ha questoes com erro e quer importar so as validas
 */
public record ImportOptions(String title, boolean updateExisting, boolean includeDuplicates, boolean acceptInvalid) {
}
