package com.pdf2questao.aula;

import com.pdf2questao.runner.CodeRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Confere os blocos interativos antes de gravar a aula: cartao de formula completo, gerador do exercicio guiado que
 * fecha com as etapas, exemplo de codigo que roda e da a saida declarada, tarefa cuja solucao passa no teste e cujo
 * codigo inicial ainda nao passa. Qualquer falha recusa a aula, com a mensagem do que corrigir.
 */
@Component
public class LessonBlockValidator {

    private static final int SNIPPET = 600;

    private final CodeRunner runner;

    public LessonBlockValidator(CodeRunner runner) {
        this.runner = runner;
    }

    public void validate(String content) {
        LessonSlides.Parsed parsed = LessonSlides.parseAll(content);
        if (!parsed.problems().isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", parsed.problems()));
        }
        Map<String, LessonSlides.Concept> concepts = LessonSlides.concepts(content);
        List<CodeRunner.Request> requests = new ArrayList<>();
        List<Check> checks = new ArrayList<>();
        Random random = new Random();
        for (LessonBlocks.Block b : parsed.blocks()) {
            switch (b.kind()) {
                case FORMULA -> LessonBlocks.validate(LessonBlocks.formula(b.body()), b.slide());
                case CALCULO -> {
                    LessonBlocks.Calc c = LessonBlocks.calc(b.body());
                    LessonBlocks.validate(c, b.slide(), random);
                    if (c.conceito() != null && !concepts.containsKey(LessonService.conceptId(c.conceito()))) {
                        throw new IllegalArgumentException("Exercício guiado do slide " + b.slide() + ": o conceito "
                                + "{#" + c.conceito() + "} não está na aula (conceitos: " + String.join(", ",
                                concepts.keySet()) + ").");
                    }
                }
                case RUN -> {
                    requests.add(new CodeRunner.Request(b.lang(), b.body(), parsed.datasets(), null, b.expected()));
                    checks.add(new Check(b, Expect.PASS, "O exemplo de código do slide " + b.slide()));
                }
                case TAREFA -> {
                    LessonBlocks.Task t = LessonBlocks.task(b.body());
                    String where = "A tarefa de código do slide " + b.slide();
                    List<String> missing = new ArrayList<>();
                    if (t.enunciado() == null) missing.add("### enunciado");
                    if (t.codigo() == null) missing.add("### codigo (código inicial)");
                    if (t.dica() == null) missing.add("### dica");
                    if (t.solucao() == null) missing.add("### solucao");
                    if ("sql".equals(b.lang()) ? t.esperado() == null : t.teste() == null && t.esperado() == null) {
                        missing.add("sql".equals(b.lang()) ? "### esperado (resultado da consulta)"
                                : "### teste (asserts) ou ### esperado");
                    }
                    if (!missing.isEmpty()) {
                        throw new IllegalArgumentException(where + " precisa de " + String.join(", ", missing) + ".");
                    }
                    requests.add(new CodeRunner.Request(b.lang(), t.solucao(), parsed.datasets(), t.teste(), t.esperado()));
                    checks.add(new Check(b, Expect.PASS, where + " (solução)"));
                    requests.add(new CodeRunner.Request(b.lang(), t.codigo(), parsed.datasets(), t.teste(), t.esperado()));
                    checks.add(new Check(b, Expect.FAIL, where + " (código inicial)"));
                }
            }
        }
        if (requests.isEmpty()) {
            return;
        }
        List<CodeRunner.Result> results;
        try {
            results = runner.run(requests);
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        for (int i = 0; i < checks.size(); i++) {
            Check c = checks.get(i);
            CodeRunner.Result r = results.get(i);
            if (c.expect() == Expect.PASS && !r.ok()) {
                throw new IllegalArgumentException(c.label() + " não roda: " + r.error() + ". Corrija o código "
                        + "(as tabelas :::dados estão em dados/<nome>.csv no Python e como tabelas no SQL).");
            }
            if (c.expect() == Expect.PASS && Boolean.FALSE.equals(r.testPassed())) {
                throw new IllegalArgumentException(c.label() + " não passa no teste: " + r.error() + ".");
            }
            if (c.expect() == Expect.PASS && Boolean.FALSE.equals(r.matches())) {
                throw new IllegalArgumentException(c.label() + " não dá a saída declarada. Saída obtida:\n"
                        + clip(r.output()) + "\nAjuste o ```saida (ou ### esperado) para o que o código produz de fato, "
                        + "ou corrija o código.");
            }
            if (c.expect() == Expect.FAIL && r.passed()) {
                throw new IllegalArgumentException(c.label() + " já passa no teste: o aluno não teria o que fazer. "
                        + "Deixe no código inicial só o esqueleto (ex.: um TODO no lugar da linha que resolve).");
            }
        }
    }

    private static String clip(String s) {
        return s == null ? "" : s.length() > SNIPPET ? s.substring(0, SNIPPET) + "…" : s;
    }

    private enum Expect { PASS, FAIL }

    private record Check(LessonBlocks.Block block, Expect expect, String label) {
    }
}
