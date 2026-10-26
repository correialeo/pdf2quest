package com.pdf2questao.concurso;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Preferencia chave/valor guardada no banco (vale para a tela e para o MCP). */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AppSetting {

    @Id
    private String chave;

    private String valor;
}
