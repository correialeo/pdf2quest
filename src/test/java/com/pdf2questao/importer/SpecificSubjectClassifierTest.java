package com.pdf2questao.importer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpecificSubjectClassifierTest {

    private final SpecificSubjectClassifier classifier = new SpecificSubjectClassifier();

    @Test
    void sugereDisciplinaPelasPalavrasChave() {
        assertEquals("Banco de Dados", classifier.classify("Sobre bancos de dados NoSQL, assinale a opção correta."));
        assertEquals("Gestão de Projetos e Métodos Ágeis",
                classifier.classify("Uma equipe está trabalhando em um projeto usando Scrum. Durante a Sprint..."));
        assertEquals("Segurança da Informação",
                classifier.classify("A OWASP Top 10 é uma lista das vulnerabilidades mais críticas."));
    }

    @Test
    void semPalavraChaveCaiNoValorPadrao() {
        assertEquals(SpecificSubjectClassifier.FALLBACK, classifier.classify("Assinale a opção correta."));
    }
}
