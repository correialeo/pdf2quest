package com.pdf2questao.mcp;

import com.pdf2questao.concurso.Concurso;
import com.pdf2questao.concurso.ConcursoService;
import com.pdf2questao.concurso.Workspace;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Parametro "concurso" das ferramentas: o usuario escreve no chat ("do concurso sefaz") e a chamada roda naquele
 * concurso, sem trocar o concurso ativo da tela. Vazio = o concurso ativo (ou todos, se nenhum estiver ativo).
 */
@Component
public class McpConcurso {

    /** Descricao comum do parametro. */
    public static final String PARAM = "Concurso (id ou parte do nome, ex.: \"sefaz\"; veja listar_concursos). "
            + "Vazio = o concurso ativo.";

    private final ConcursoService concursoService;
    private final Workspace workspace;

    public McpConcurso(ConcursoService concursoService, Workspace workspace) {
        this.concursoService = concursoService;
        this.workspace = workspace;
    }

    /** Roda {@code work} no concurso pedido (vazio = o ativo). */
    public <T> T in(String concurso, Supplier<T> work) {
        Concurso c = concursoService.resolve(concurso);
        return c == null ? work.get() : workspace.as(c.getId(), work);
    }

    /** O concurso pedido ou, vazio, o ativo; nulo se nenhum. */
    public Concurso resolveOrCurrent(String concurso) {
        Concurso c = concursoService.resolve(concurso);
        return c != null ? c : concursoService.current().orElse(null);
    }

    /** Id do concurso pedido ou, vazio, do em vigor; nulo = todos. */
    public Long id(String concurso) {
        Concurso c = concursoService.resolve(concurso);
        return c != null ? c.getId() : workspace.currentId();
    }

    /** Nome do concurso (para devolver ao Claude junto com o que foi criado). */
    public String name(Long concursoId) {
        return concursoService.find(concursoId).map(Concurso::getNome).orElse(null);
    }
}
