package com.pdf2questao.importer.parser.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conserta, linha a linha, defeitos previsiveis do OCR em listas e tabelas de prova:
 * <ul>
 *   <li>bolinha de lista ("▪") lida como {@code ' " “ * = n}: vira "• ";</li>
 *   <li>travessao de lista lido como "— —": vira "– ";</li>
 *   <li>rotulo romano lido errado ("Iv.", "v.", "T."): vira "IV.", "V.", "I.";</li>
 *   <li>linha com pontilhado ("Capital Social ....... 1.000.000"): o pontilhado vira lixo
 *       ("ici esierienease... TODODDO") e o valor sai com letras no lugar de digitos.</li>
 * </ul>
 * Tambem diz quais linhas abrem item de lista ({@link #isListItem}), para que nao sejam emendadas
 * no paragrafo anterior, e normaliza alternativas so com numerais romanos ({@link #romanList}).
 */
public final class OcrLineRepair {

    public static final String BULLET = "• ";
    public static final String DASH = "– ";

    /** Simbolo de bolinha seguido de espaco e de texto em maiuscula ("= 5" de uma conta nao e lista). */
    private static final Pattern BULLET_SPACED = Pattern.compile("^[\"'“”*=•▪■«»n]\\s+(?=\\p{Lu})");
    /** Aspas coladas ao texto: so e bolinha se a linha vizinha tambem e item de lista com bolinha. */
    private static final Pattern BULLET_GLUED = Pattern.compile("^[\"'“”](?=\\p{Lu})");
    private static final Pattern DASHES = Pattern.compile("^[—–-]+(?:\\s*[—–-]+)*\\s+(?=\\S)");
    private static final Pattern ROMAN_LABEL = Pattern.compile("^([IVXivxlLTW|]{1,5})\\s*([.)\\-–])\\s+(?=\\S)");
    private static final Pattern REST = Pattern.compile("^,?\\s*(\\p{L}+)?\\.?$");
    private static final String ROMAN_CHARS = "IVXivxlLTWw|!1";
    private static final String ROMAN_LIST_CHARS = "IVXivxlLTWw|!1, e-\u2013\u2014";
    private static final Pattern ROMAN = Pattern.compile("^(X|IX|IV|V?I{0,3})$");
    private static final Pattern LIST_ITEM = Pattern.compile(
            "^(•|–|[IVX]{1,4}\\s*[.)\\-–]|\\d{1,2}\\s*[.)]|[a-z]\\))\\s");
    private static final Pattern LEADER = Pattern.compile("^(.*?\\p{L}.*?)\\s*\\.{3,}.*\\s(\\S+)$");
    private static final Pattern LEADING_TOKENS = Pattern.compile("^(?:" + RichText.TOKEN.pattern() + ")*");

    private OcrLineRepair() {
    }

    /** Aplica os consertos a cada linha do texto (com marcas de estilo); linhas em branco e marcadores ficam. */
    public static String repair(String text) {
        String[] lines = text.split("\n", -1);
        boolean[] bullet = new boolean[lines.length];
        for (int i = 0; i < lines.length; i++) {
            bullet[i] = BULLET_SPACED.matcher(plainOf(lines[i])).find();
        }
        List<String> out = new ArrayList<>(lines.length);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String plain = plainOf(line);
            if (plain.isEmpty()) {
                out.add(line);
                continue;
            }
            Matcher m;
            if ((m = BULLET_SPACED.matcher(plain)).find()) {
                line = replacePrefix(line, m.end(), BULLET);
            } else if ((m = BULLET_GLUED.matcher(plain)).find() && !plain.substring(1).contains(plain.substring(0, 1))
                    && (neighborIs(bullet, lines, i, -1) || neighborIs(bullet, lines, i, 1))) {
                line = replacePrefix(line, m.end(), BULLET);
            } else if ((m = DASHES.matcher(plain)).find()) {
                line = replacePrefix(line, m.end(), DASH);
            } else if ((m = ROMAN_LABEL.matcher(plain)).find()) {
                String roman = romanNumeral(m.group(1));
                if (roman != null && !roman.equals(m.group(1))) {
                    line = replacePrefix(line, m.end(), roman + m.group(2) + " ");
                }
            }
            out.add(fixLeader(line));
        }
        return String.join("\n", out);
    }

    /** Linha (texto puro) que abre item de lista: bolinha, travessao, "I.", "1.", "a)". */
    public static boolean isListItem(String plainLine) {
        return LIST_ITEM.matcher(plainLine).find();
    }

    /**
     * "Capital Social ..... ici esierienease TODODDO" -> "Capital Social ..... 1.000.000". So mexe na linha
     * se o ultimo token, trocando as confusoes comuns (T/I por 1, O/D por 0, S por 5...), for um numero.
     */
    static String fixLeader(String rich) {
        String plain = RichText.plain(rich);
        Matcher m = LEADER.matcher(plain);
        if (!m.matches()) {
            return rich;
        }
        String value = asNumber(m.group(2));
        if (value == null) {
            return rich;
        }
        String trimmed = RichText.trim(rich);
        Matcher lead = LEADING_TOKENS.matcher(trimmed);
        lead.lookingAt();
        return lead.group() + m.group(1).trim() + " ..... " + value + trimmed.substring(trailingTokensStart(trimmed));
    }

    private static int trailingTokensStart(String rich) {
        int end = rich.length();
        while (end > 0 && rich.charAt(end - 1) == '⟧') {
            int open = rich.lastIndexOf('⟦', end - 1);
            if (open < 0) {
                break;
            }
            end = open;
        }
        return end;
    }

    private static String asNumber(String token) {
        StringBuilder sb = new StringBuilder();
        int digits = 0;
        for (char c : token.toCharArray()) {
            char d = switch (c) {
                case 'T', 'I', 'l', '|', '!' -> '1';
                case 'O', 'o', 'D', 'Q' -> '0';
                case 'S', 's' -> '5';
                case 'B' -> '8';
                case 'Z' -> '2';
                case 'G' -> '6';
                default -> c;
            };
            if (Character.isDigit(d)) {
                digits++;
            } else if (d != '.' && d != ',') {
                return null;
            }
            sb.append(d);
        }
        String number = sb.toString();
        if (digits < 2) {
            return null;
        }
        // Os pontos de milhar costumam sumir junto com o pontilhado: "1000000" volta a "1.000.000".
        if (number.matches("\\d{4,}")) {
            number = String.format(java.util.Locale.ROOT, "%,d", Long.parseLong(number)).replace(',', '.');
        }
        return number;
    }

    /**
     * Alternativa so com numerais romanos ("IeTIII,apenas.", "IIIleV, apenas.", "IVeVv, apenas.") vira
     * "I e III, apenas.". Nas provas da FCC os numerais vem em fonte monoespacada, cujo "I" com serifas o
     * OCR le como T ou l, e o "e" gruda neles. Sequencias com tracos ("III-V - IT -IV-IL") viram
     * "III - V - I - IV - II".
     * Devolve {@code null} se o texto nao comeca com uma lista de romanos legivel.
     */
    public static String romanList(String alternative) {
        int end = romanPrefixEnd(alternative);
        if (end < 0) {
            return null;
        }
        String prefix = alternative.substring(0, end);
        boolean sequence = prefix.matches(".*[-\u2013\u2014].*");
        List<List<String>> readings = new ArrayList<>();
        for (String piece : prefix.split(sequence ? "\\s*[-\u2013\u2014]+\\s*" : "\\s*(?:,|e|\\s)\\s*")) {
            if (piece.isBlank()) {
                continue;
            }
            List<String> options = romanReadings(piece.trim());
            if (options.isEmpty()) {
                return null;
            }
            readings.add(options);
        }
        List<String> numerals = choose(readings, new ArrayList<>(), sequence);
        if (numerals == null || numerals.isEmpty()) {
            return null;
        }
        String list = sequence ? String.join(" - ", numerals)
                : numerals.size() == 1 ? numerals.get(0)
                : String.join(", ", numerals.subList(0, numerals.size() - 1)) + " e " + numerals.get(numerals.size() - 1);
        String rest = alternative.substring(end).trim();
        // Depois dos numerais so cabe pontuacao e uma palavra ("apenas"); mais que isso e numeral ilegivel.
        if (!REST.matcher(rest).matches()) {
            return null;
        }
        if (rest.startsWith(",")) {
            rest = ", " + rest.substring(1).trim();
        } else if (!rest.isEmpty() && !rest.startsWith(".")) {
            rest = " " + rest;
        }
        return list + rest;
    }

    /**
     * Primeira combinacao de leituras (na ordem de preferencia de cada numeral) que faz sentido: numa lista
     * ("I, III e IV") os itens vem em ordem crescente; numa sequencia, cada numeral aparece uma vez so.
     */
    private static List<String> choose(List<List<String>> readings, List<String> chosen, boolean sequence) {
        if (chosen.size() == readings.size()) {
            return chosen;
        }
        for (String option : readings.get(chosen.size())) {
            boolean fits = sequence ? !chosen.contains(option)
                    : chosen.isEmpty() || value(option) > value(chosen.get(chosen.size() - 1));
            if (fits) {
                chosen.add(option);
                List<String> done = choose(readings, chosen, sequence);
                if (done != null) {
                    return done;
                }
                chosen.remove(chosen.size() - 1);
            }
        }
        return null;
    }

    /**
     * Os numerais do comeco da alternativa tem caracteres que podem ser tanto um "I" quanto ruido ("l", "T"...):
     * a leitura de {@link #romanList} e um palpite e precisa ser conferida no PDF.
     */
    public static boolean romanListIsGuess(String alternative) {
        int end = romanPrefixEnd(alternative);
        return end > 0 && alternative.substring(0, end).matches(".*[lLT|!1WwoM].*");
    }

    /** Fim do trecho inicial feito so de numerais romanos (com ruido) e separadores; -1 se nao houver. */
    private static int romanPrefixEnd(String alternative) {
        int i = 0;
        int lastRoman = -1;
        while (i < alternative.length()) {
            char c = alternative.charAt(i);
            // "o"/"M" so como ruido grudado num numeral ("ILo", "IVeMv"), nunca o artigo ("III, o que...").
            boolean gluedO = (c == 'o' || c == 'M') && i > 0
                    && (ROMAN_CHARS.indexOf(alternative.charAt(i - 1)) >= 0 || alternative.charAt(i - 1) == 'e');
            if (ROMAN_LIST_CHARS.indexOf(c) < 0 && !gluedO) {
                break;
            }
            if (", e-\u2013\u2014".indexOf(c) < 0) {
                lastRoman = i;
            }
            i++;
        }
        int end = lastRoman + 1;
        // Parou num digito no meio da lista ("I -II - 2IV"): e lixo, nao uma lista de romanos.
        boolean stoppedOnDigit = i < alternative.length() && Character.isDigit(alternative.charAt(i));
        if (lastRoman < 0 || stoppedOnDigit
                || (end < alternative.length() && Character.isLetterOrDigit(alternative.charAt(end)))) {
            return -1;
        }
        return end;
    }

    /** Le um numeral romano com ruido de OCR; {@code null} se nao houver leitura valida. */
    static String romanNumeral(String token) {
        List<String> readings = romanReadings(token);
        return readings.isEmpty() ? null : readings.get(0);
    }

    /**
     * Leituras validas de um numeral com ruido, da mais provavel para a menos: sem os T, trocando os
     * caracteres suspeitos por I/V, depois tirando cada um deles, depois pares deles.
     */
    private static List<String> romanReadings(String token) {
        // O traco do "e" italico grudado no numeral seguinte: "eTIII" e "elII" sao "e III" e "e II".
        String t = token.length() > 1 && (token.charAt(0) == 'T' || token.charAt(0) == 'l') ? token.substring(1) : token;
        List<Integer> noisy = new ArrayList<>();
        StringBuilder mapped = new StringBuilder();
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            if ("IVX".indexOf(c) < 0) {
                noisy.add(k);
            }
            mapped.append(switch (c) {
                case 'i', 'l', 'L', '|', '!', '1', 'T' -> 'I';
                case 'v', 'W', 'w' -> 'V';
                case 'x' -> 'X';
                // Ruido que nunca e numeral: so serve se for tirado.
                case 'o', 'M' -> '?';
                default -> c;
            });
        }
        Set<String> readings = new LinkedHashSet<>();
        if (mapped.toString().replaceAll("[IVX?]", "").length() > 0) {
            return List.of();
        }
        // Um T grudado costuma ser a serifa do I ("IT" e "I"); um L costuma ser o proprio I ("IL" e "II").
        if (t.length() > 1 && t.indexOf('T') >= 0) {
            addIfRoman(readings, t.replace("T", "").replace('l', 'I').replace('L', 'I').replace('v', 'V'));
        }
        addIfRoman(readings, mapped.toString());
        for (int a = noisy.size() - 1; a >= 0; a--) {
            addIfRoman(readings, without(mapped, noisy.get(a), -1));
        }
        for (int a = 0; a < noisy.size(); a++) {
            for (int b = a + 1; b < noisy.size(); b++) {
                addIfRoman(readings, without(mapped, noisy.get(a), noisy.get(b)));
            }
        }
        return new ArrayList<>(readings);
    }

    private static void addIfRoman(Set<String> readings, String candidate) {
        if (!candidate.isEmpty() && ROMAN.matcher(candidate).matches()) {
            readings.add(candidate);
        }
    }

    private static String without(CharSequence s, int a, int b) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < s.length(); k++) {
            if (k != a && k != b) {
                sb.append(s.charAt(k));
            }
        }
        return sb.toString();
    }

    private static int value(String roman) {
        int total = 0;
        for (int k = 0; k < roman.length(); k++) {
            int v = roman.charAt(k) == 'X' ? 10 : roman.charAt(k) == 'V' ? 5 : 1;
            int next = k + 1 < roman.length() ? (roman.charAt(k + 1) == 'X' ? 10 : roman.charAt(k + 1) == 'V' ? 5 : 1) : 0;
            total += v < next ? -v : v;
        }
        return total;
    }

    private static boolean neighborIs(boolean[] flags, String[] lines, int i, int step) {
        for (int k = i + step; k >= 0 && k < lines.length; k += step) {
            if (!plainOf(lines[k]).isEmpty()) {
                return flags[k];
            }
        }
        return false;
    }

    private static String plainOf(String rich) {
        return RichText.plain(RichText.removeLayoutTokens(rich)).trim();
    }

    /** Troca os {@code visibleLength} primeiros caracteres visiveis por {@code prefix}, mantendo os estilos abertos. */
    private static String replacePrefix(String rich, int visibleLength, String prefix) {
        String trimmed = RichText.trim(rich);
        String rest = RichText.fromVisible(trimmed, visibleLength);
        Matcher lead = LEADING_TOKENS.matcher(rest);
        lead.lookingAt();
        return lead.group() + prefix + rest.substring(lead.end());
    }
}
