package com.concursos.study.importer.parser;

import com.concursos.study.importer.Banca;
import com.concursos.study.importer.ParseResult;
import com.concursos.study.importer.parser.fgv.FgvPdfParser;

/**
 * Parser de PDF de prova especifico de uma banca. Cada banca tem formatacao
 * propria de cabecalho de disciplina, numeracao de questao, rodape etc., por
 * isso o parser generico de heuristicas foi promovido a implementacao FGV
 * (ver {@link FgvPdfParser}) - novas bancas ganham sua propria implementacao
 * desta interface, registrada automaticamente via {@link ExamParserRegistry}.
 */
public interface ExamParser {

    Banca banca();

    ParseResult parse(String rawText);
}
