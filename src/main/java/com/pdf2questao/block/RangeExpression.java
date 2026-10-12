package com.pdf2questao.block;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Expressao de faixas de numeros de questao, ex.: {@code "1-10, 32-46, 71-100"} ou {@code "5; 8"}.
 * Aceita espacos em qualquer lugar e "," ou ";" como separador. Faixas que se sobrepoem ou se
 * repetem sao unidas (cada numero conta uma vez). Expressao em branco e valida e fica vazia -
 * quem usa decide se isso significa "todas". Entrada invalida (texto, 0, faixa invertida, item
 * vazio entre separadores) lanca {@link IllegalArgumentException} com mensagem pronta para a tela.
 */
public final class RangeExpression {

    public static final int MAX_NUMBER = 9999;

    public record Range(int from, int to) {
    }

    private static final RangeExpression EMPTY = new RangeExpression(List.of());

    private final List<Range> ranges;

    private RangeExpression(List<Range> ranges) {
        this.ranges = ranges;
    }

    public static RangeExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            return EMPTY;
        }
        List<Range> parsed = new ArrayList<>();
        String[] parts = expression.split("[,;]", -1);
        for (String raw : parts) {
            String part = raw.replaceAll("\\s+", "");
            if (part.isEmpty()) {
                throw new IllegalArgumentException("Faixa vazia em '" + expression.trim() + "' (virgula sobrando?).");
            }
            String[] bounds = part.split("[-–—]", -1);
            if (bounds.length > 2) {
                throw new IllegalArgumentException("Faixa invalida '" + part + "': use o formato 1-10.");
            }
            int from = number(bounds[0], part);
            int to = bounds.length == 2 ? number(bounds[1], part) : from;
            if (from > to) {
                throw new IllegalArgumentException("Faixa invertida '" + part + "': o inicio passa do fim.");
            }
            parsed.add(new Range(from, to));
        }
        return new RangeExpression(merge(parsed));
    }

    private static int number(String token, String part) {
        if (!token.matches("\\d{1,4}")) {
            throw new IllegalArgumentException("Faixa invalida '" + part + "': '" + token + "' nao e um numero de questao.");
        }
        int value = Integer.parseInt(token);
        if (value < 1 || value > MAX_NUMBER) {
            throw new IllegalArgumentException("Numero de questao fora do limite (1-" + MAX_NUMBER + ") em '" + part + "'.");
        }
        return value;
    }

    private static List<Range> merge(List<Range> parsed) {
        List<Range> sorted = new ArrayList<>(parsed);
        sorted.sort((a, b) -> Integer.compare(a.from(), b.from()));
        List<Range> merged = new ArrayList<>();
        for (Range r : sorted) {
            if (!merged.isEmpty() && r.from() <= merged.get(merged.size() - 1).to() + 1) {
                Range last = merged.remove(merged.size() - 1);
                merged.add(new Range(last.from(), Math.max(last.to(), r.to())));
            } else {
                merged.add(r);
            }
        }
        return List.copyOf(merged);
    }

    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    public boolean contains(int number) {
        return ranges.stream().anyMatch(r -> number >= r.from() && number <= r.to());
    }

    public List<Range> ranges() {
        return ranges;
    }

    public TreeSet<Integer> numbers() {
        TreeSet<Integer> all = new TreeSet<>();
        for (Range r : ranges) {
            for (int n = r.from(); n <= r.to(); n++) {
                all.add(n);
            }
        }
        return all;
    }

    /** Forma canonica: faixas unidas e ordenadas, ex.: {@code "1-10, 32-46"}. */
    @Override
    public String toString() {
        return String.join(", ", ranges.stream()
                .map(r -> r.from() == r.to() ? String.valueOf(r.from()) : r.from() + "-" + r.to()).toList());
    }
}
