package com.pdf2questao.session;

/** A sessao ficaria sem nenhuma questao (filtros que nao casam com nada, bloco vazio...). */
public class EmptySessionException extends IllegalStateException {

    public EmptySessionException(String message) {
        super(message);
    }
}
