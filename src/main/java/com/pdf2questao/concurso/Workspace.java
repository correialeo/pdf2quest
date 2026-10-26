package com.pdf2questao.concurso;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Concurso ativo (workspace). Fica no banco, entao a tela e o MCP enxergam o mesmo. Uma chamada do MCP pode pedir
 * outro concurso so para ela com {@link #as}, sem trocar o que esta selecionado na tela.
 */
@Component
public class Workspace {

    static final String KEY = "concurso_ativo";

    /** Concurso pedido so para esta chamada; o array vazio de dentro = "Todos" explicito. */
    private final ThreadLocal<Long[]> override = new ThreadLocal<>();

    private final AppSettingRepository settings;
    private final ConcursoRepository concursos;
    private volatile Long cached;
    private volatile boolean loaded;

    public Workspace(AppSettingRepository settings, ConcursoRepository concursos) {
        this.settings = settings;
        this.concursos = concursos;
    }

    /** Concurso em vigor (o desta chamada ou o selecionado); nulo = todos. */
    public Long currentId() {
        Long[] forced = override.get();
        if (forced != null) {
            return forced.length == 0 ? null : forced[0];
        }
        return selectedId();
    }

    /** O selecionado na tela (ignora o pedido de uma chamada). */
    public Long selectedId() {
        if (!loaded) {
            cached = settings.findById(KEY).map(AppSetting::getValor).filter(v -> !v.isBlank())
                    .map(Long::valueOf).filter(concursos::existsById).orElse(null);
            loaded = true;
        }
        return cached;
    }

    /** Seleciona o concurso ativo (nulo = todos). */
    public void select(Long concursoId) {
        if (concursoId != null && !concursos.existsById(concursoId)) {
            throw new IllegalArgumentException("Concurso " + concursoId + " não existe.");
        }
        settings.save(new AppSetting(KEY, concursoId == null ? "" : concursoId.toString()));
        cached = concursoId;
        loaded = true;
    }

    /** Roda {@code work} como se {@code concursoId} estivesse ativo (nulo = todos). */
    public <T> T as(Long concursoId, Supplier<T> work) {
        Long[] previous = override.get();
        override.set(concursoId == null ? new Long[0] : new Long[]{concursoId});
        try {
            return work.get();
        } finally {
            if (previous == null) {
                override.remove();
            } else {
                override.set(previous);
            }
        }
    }

    /** Registro do concurso em vigor (ou tudo, em "Todos"). */
    public boolean matches(Long recordConcursoId) {
        Long current = currentId();
        return current == null || current.equals(recordConcursoId);
    }

    /** Como {@link #matches}, mas registro sem concurso aparece em qualquer workspace (paginas, provas). */
    public boolean matchesOrShared(Long recordConcursoId) {
        return recordConcursoId == null || matches(recordConcursoId);
    }

    /** Concurso para gravar num registro novo: o informado ou, sem ele, o em vigor. */
    public Long stamp(Long explicit) {
        return explicit != null ? explicit : currentId();
    }

    void forget(Long concursoId) {
        if (concursoId.equals(cached)) {
            select(null);
        }
    }
}
