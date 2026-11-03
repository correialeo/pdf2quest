package com.pdf2questao.aula;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Citacoes de artigo com a norma ("art. 42 da LRF", "LRF, art. 42", "art. 165, § 8º, da CF"). LRF, Lei 4.320 e CF
 * tem artigos com o mesmo numero: o trecho literal gravado tem de ser da mesma norma E do mesmo artigo citado.
 */
public final class NormCitations {

    /** Norma reconhecida: chave para comparar (lc101, lei4320, cf...) e o nome para as mensagens. */
    public record Norm(String key, String label) {
    }

    /** Artigo de uma norma; norma nula = a citacao nao diz qual (e o contexto tambem nao). */
    public record Citation(Norm norm, String article) {
        public String key() {
            return (norm == null ? "?" : norm.key()) + ":" + article;
        }

        public String label() {
            return "art. " + article + (norm == null ? "" : " da " + norm.label());
        }
    }

    private record Alias(Pattern pattern, java.util.function.Function<Matcher, Norm> norm) {
    }

    private static final String NUM = "(?:n[º°o.]*\\s*)?(\\d{1,2}\\.\\d{3}|\\d{1,5})";

    /** Ordem importa so no empate de posicao; "Lei Complementar 101" nao casa com "Lei N" (vem palavra antes). */
    private static final List<Alias> ALIASES = List.of(
            new Alias(Pattern.compile("(?iu)\\bLRF\\b|lei de responsabilidade fiscal"), m -> lc("101")),
            new Alias(Pattern.compile("(?iu)\\b(?:LC|lei\\s+complementar)\\s*(?:federal\\s*)?" + NUM), m -> lc(m.group(1))),
            new Alias(Pattern.compile("(?iu)\\blei\\s*(?:federal\\s*|estadual\\s*)?" + NUM), m -> {
                String n = m.group(1).replace(".", "");
                return new Norm("lei" + n, "Lei " + m.group(1));
            }),
            new Alias(Pattern.compile("(?iu)\\bADCT\\b|ato das disposi[çc][õo]es constitucionais transit[óo]rias"),
                    m -> new Norm("adct", "ADCT")),
            new Alias(Pattern.compile("(?iu)constitui[çc][ãa]o\\s+(?:do\\s+)?estad|\\bCE(?:/89)?\\b|constitui[çc][ãa]o\\s+paulista"),
                    m -> new Norm("ce", "Constituição Estadual")),
            new Alias(Pattern.compile("(?u)\\bCF(?:/88)?\\b|(?iu)\\bconstitui[çc][ãa]o(?:\\s+federal)?\\b|\\bCRFB\\b"),
                    m -> new Norm("cf", "CF")),
            new Alias(Pattern.compile("(?iu)\\bdecreto(?:-lei)?\\s*(?:federal\\s*|estadual\\s*)?" + NUM), m -> {
                String n = m.group(1).replace(".", "");
                return new Norm("decreto" + n, "Decreto " + m.group(1));
            }));

    private static Norm lc(String n) {
        String num = n.replace(".", "");
        return new Norm("lc" + num, num.equals("101") ? "LRF (LC 101)" : "LC " + n);
    }

    /** Citacao de artigo no texto corrido (minuscula ou maiuscula). */
    static final Pattern ARTICLE = Pattern.compile("(?iu)\\bart(?:igo)?s?\\.?\\s*(\\d{1,4})(?:\\.\\d{3})?\\s*(?:º|°|o\\b)?");
    /** Cabecalho de artigo no texto da lei ("Art. 42.", "Art. 1º -"): so isso prova que o trecho e o texto DO artigo. */
    static final Pattern ARTICLE_HEADING = Pattern.compile("(?u)\\bArt\\.\\s*(\\d{1,4})(?:\\.\\d{3})?\\s*(?:º|°|o)?\\s*[.\\-–—]");
    /** Entre o artigo e a norma: ", § 8º", ", inciso II", ", caput", ", parágrafo único", ", I", e "da"/"do". */
    private static final Pattern BETWEEN = Pattern.compile("(?iu)(?:\\s*,?\\s*(?:§+\\s*\\d+\\s*[º°o]?|par[áa]grafo\\s+[úu]nico"
            + "|caput|inciso\\s+[IVXLC]+|al[íi]nea\\s+[\"“]?[a-z][\"”]?|[IVXLC]+\\b))*\\s*,?\\s*(?:d[aoe]s?\\s+)?");

    private NormCitations() {
    }

    /** Primeira norma que aparece no texto (nulo = nenhuma). */
    public static Norm first(String text) {
        if (text == null) {
            return null;
        }
        Norm best = null;
        int at = Integer.MAX_VALUE;
        for (Alias a : ALIASES) {
            Matcher m = a.pattern().matcher(text);
            if (m.find() && m.start() < at) {
                at = m.start();
                best = a.norm().apply(m);
            }
        }
        return best;
    }

    /** Norma escrita logo depois do artigo ("art. 42 da LRF") ou logo antes ("LRF, art. 42"); nulo se nao houver. */
    static Norm around(String text, int start, int end) {
        Matcher between = BETWEEN.matcher(text).region(end, text.length());
        int after = between.lookingAt() ? between.end() : end;
        for (Alias a : ALIASES) {
            Matcher m = a.pattern().matcher(text).region(after, text.length());
            if (m.lookingAt()) {
                return a.norm().apply(m);
            }
        }
        String before = text.substring(Math.max(0, start - 40), start);
        for (Alias a : ALIASES) {
            Matcher m = a.pattern().matcher(before);
            while (m.find()) {
                if (before.substring(m.end()).matches("(?s)[\\s,:\\-–—/(]*")) {
                    return a.norm().apply(m);
                }
            }
        }
        return null;
    }

    /** Artigos citados no texto, com a norma de cada um (ou {@code fallback} quando a citacao nao a diz). */
    public static List<Citation> cited(String text, Norm fallback) {
        List<Citation> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = ARTICLE.matcher(text);
        while (m.find()) {
            Norm n = around(text, m.start(), m.end());
            out.add(new Citation(n != null ? n : fallback, m.group(1)));
        }
        return out;
    }

    /**
     * O que um trecho literal comprova: os artigos cujo TEXTO esta nele (cabecalho "Art. N." no trecho, ou o artigo
     * do proprio dispositivo), todos da norma do dispositivo. Mencao de passagem ("referido no art. 20") nao conta.
     */
    public static Set<String> proven(SourceExcerpt e) {
        Set<String> out = new LinkedHashSet<>();
        Norm norm = first(e.getDispositivo());
        if (norm == null) {
            return out;
        }
        Matcher h = ARTICLE_HEADING.matcher(e.getTexto());
        while (h.find()) {
            out.add(norm.key() + ":" + h.group(1));
        }
        Matcher d = ARTICLE.matcher(e.getDispositivo());
        while (d.find()) {
            out.add(norm.key() + ":" + d.group(1));
        }
        return out;
    }

    /** O texto do trecho tem cabecalho de artigo (entao o dispositivo precisa dizer a norma). */
    public static boolean hasArticle(String text) {
        return text != null && ARTICLE_HEADING.matcher(text).find();
    }
}
