package com.pdf2questao.importer.parser;

import com.pdf2questao.importer.Banca;
import com.pdf2questao.importer.parser.fgv.FgvGabaritoParser;

import java.util.Map;

/**
 * Parser da tabela de respostas de um PDF de gabarito, especifico de uma
 * banca (o layout da tabela - blocos de 20, letra por linha, etc. - varia
 * de banca pra banca). Ver {@link FgvGabaritoParser} pra implementacao de
 * referencia e {@link GabaritoParserRegistry} pra saber quais bancas estao
 * disponiveis.
 */
public interface GabaritoTableParser {

    Banca banca();

    /**
     * @return numero da questao -> letra correta. Mapa vazio se o titulo do
     *         perfil nao for encontrado no texto.
     */
    Map<Integer, String> parse(String rawText, String titulo);
}
