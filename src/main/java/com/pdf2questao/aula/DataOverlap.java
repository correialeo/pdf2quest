package com.pdf2questao.aula;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Valores numericos de um texto, para conferir que a questao de fixacao nao reaproveita os dados do exemplo do
 * slide (quem acabou de ler o exemplo acerta por reconhecimento, nao por raciocinio). Ignora numeros triviais
 * (0, 1, 2, 100), datas (31/12/2025) e referencias normativas (art. 42, § 2º, Lei 4.320, LC 101, inciso 3, Sumula 7, Tema 1.234).
 */
public final class DataOverlap {

    /** Mais que isto de valores em comum entre o slide e a questao e recusado. */
    public static final int MAX_SHARED = 1;

    private static final Set<BigDecimal> TRIVIAL = Set.of(BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.valueOf(2),
            BigDecimal.valueOf(100));
    /** Numero em formato brasileiro (100.000 / 1.234,56 / 10,5) ou simples (2.5 / 1500). */
    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\d])(\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?|\\d+(?:[.,]\\d+)?)");
    private static final Pattern DATE = Pattern.compile("(?<!\\d)(?:\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?|\\d{1,2}/\\d{4})(?!\\d)");
    /** Palavra logo antes do numero que o torna referencia normativa, nao dado. */
    private static final Pattern LEGAL_BEFORE = Pattern.compile(
            "(?iu)(?:art(?:igo)?s?\\.?|§+|par[áa]grafo|lei|lc|decreto|inciso|al[íi]nea|s[úu]mula|tema|resolu[çc][ãa]o|"
                    + "portaria|ec|emenda|n[ºo°.]+|item|cap[íi]tulo|t[íi]tulo|se[çc][ãa]o)\\s*(?:complementar\\s*)?(?:n[ºo°.]*\\s*)?$");

    private DataOverlap() {
    }

    /** Valores de dado do texto (sem os triviais e as referencias normativas). */
    public static Set<BigDecimal> values(String text) {
        Set<BigDecimal> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        // Datas (31/12, 31/12/2025, 12/2025) situam o caso, nao sao dado da conta: saem antes de ler os numeros.
        text = DATE.matcher(text).replaceAll(" ");
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String before = text.substring(Math.max(0, m.start() - 30), m.start());
            if (LEGAL_BEFORE.matcher(before).find()) {
                continue;
            }
            BigDecimal v = parse(m.group(1));
            if (v != null && TRIVIAL.stream().noneMatch(t -> t.compareTo(v) == 0)) {
                out.add(v.stripTrailingZeros());
            }
        }
        return out;
    }

    /** Valores que aparecem nos dois textos. */
    public static Set<BigDecimal> shared(String example, String question) {
        Set<BigDecimal> a = values(example);
        Set<BigDecimal> out = new LinkedHashSet<>();
        for (BigDecimal v : values(question)) {
            if (a.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    static BigDecimal parse(String raw) {
        String s = raw;
        if (s.matches("\\d{1,3}(\\.\\d{3})+(,\\d+)?")) {
            s = s.replace(".", "").replace(',', '.');      // 100.000 / 1.234,56
        } else {
            s = s.replace(',', '.');                         // 10,5 / 2.5
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Valor como se escreve no Brasil, para a mensagem de erro. */
    public static String show(BigDecimal v) {
        return v.toPlainString().replace('.', ',');
    }
}
