package com.pdf2questao.session;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Resposta enviada a uma sessao ja finalizada: a nota dela esta congelada e nao muda mais. */
@ResponseStatus(HttpStatus.CONFLICT)
public class SessionFinishedException extends IllegalStateException {

    public SessionFinishedException() {
        super("Esta sessão já foi finalizada.");
    }
}
