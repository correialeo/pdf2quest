package com.pdf2questao.importer.ocr;

import com.pdf2questao.importer.parser.support.RichText;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.util.ArrayList;
import java.util.List;

/**
 * Recupera o sublinhado de uma pagina lida por OCR (o Tesseract devolve so texto). Procura na imagem
 * tracos horizontais finos logo abaixo de palavras e marca essas palavras com {@code ⟦u⟧...⟦/u⟧}.
 *
 * <p>Um traco so conta como sublinhado se: e fino, e mais curto que a largura do texto (descarta as
 * linhas que separam as questoes), fica na faixa logo abaixo da linha de base das palavras que cobre e
 * comeca/termina junto de uma palavra (bordas de tabela passam muito alem do texto). Nada aqui altera o
 * texto reconhecido: as palavras so ganham marcas.
 */
public final class UnderlineDetector {

    private static final int DARK = 140;

    private UnderlineDetector() {
    }

    record Word(String text, int left, int top, int width, int height) {
        int right() {
            return left + width;
        }

        int bottom() {
            return top + height;
        }
    }

    record Line(List<Word> words) {
        String text() {
            return String.join(" ", words.stream().map(Word::text).toList());
        }
    }

    record Segment(int y, int x1, int x2, int thickness) {
        int length() {
            return x2 - x1 + 1;
        }
    }

    /**
     * @param text texto da pagina como o Tesseract o escreveu ({@code txt})
     * @param tsv  saida {@code tsv} da MESMA execucao (mesmas palavras, com caixas)
     * @return o mesmo texto com as marcas de sublinhado; igual ao original se nada for achado
     */
    public static String annotate(String text, String tsv, BufferedImage image, int dpi) {
        List<Line> lines = parseLines(tsv);
        if (lines.isEmpty()) {
            return text;
        }
        List<Segment> segments = findSegments(image, lines, dpi);
        if (segments.isEmpty()) {
            return text;
        }
        List<String> marked = new ArrayList<>();
        boolean any = false;
        for (Line line : lines) {
            String m = mark(line, segments, dpi);
            any |= !m.equals(line.text());
            marked.add(m);
        }
        return any ? merge(text, lines, marked) : text;
    }

    // ---- TSV ----

    static List<Line> parseLines(String tsv) {
        List<Line> lines = new ArrayList<>();
        List<Word> current = new ArrayList<>();
        String currentKey = null;
        for (String row : tsv.split("\n")) {
            String[] c = row.split("\t", -1);
            if (c.length < 12 || !"5".equals(c[0])) {
                continue;
            }
            String text = c[11].trim();
            if (text.isEmpty()) {
                continue;
            }
            String key = c[2] + "." + c[3] + "." + c[4];
            if (!key.equals(currentKey) && !current.isEmpty()) {
                lines.add(new Line(current));
                current = new ArrayList<>();
            }
            currentKey = key;
            try {
                current.add(new Word(text, Integer.parseInt(c[6]), Integer.parseInt(c[7]),
                        Integer.parseInt(c[8]), Integer.parseInt(c[9])));
            } catch (NumberFormatException e) {
                // linha de cabecalho ou lixo: ignora
            }
        }
        if (!current.isEmpty()) {
            lines.add(new Line(current));
        }
        return lines;
    }

    // ---- imagem ----

    static List<Segment> findSegments(BufferedImage image, List<Line> lines, int dpi) {
        int minLength = Math.max(12, (int) Math.round(dpi * 0.10));
        int maxThickness = Math.max(3, (int) Math.round(dpi * 0.022));
        int minX = Integer.MAX_VALUE;
        int maxX = 0;
        for (Line l : lines) {
            for (Word w : l.words()) {
                minX = Math.min(minX, w.left());
                maxX = Math.max(maxX, w.right());
            }
        }
        int contentWidth = Math.max(1, maxX - minX);

        Raster raster = image.getRaster();
        int width = image.getWidth();
        int height = image.getHeight();
        int[] row = new int[width];
        List<Segment> open = new ArrayList<>();
        List<int[]> openRows = new ArrayList<>();   // [first y, last y] por segmento aberto
        List<Segment> done = new ArrayList<>();

        for (int y = 0; y < height; y++) {
            raster.getSamples(0, y, width, 1, 0, row);
            List<int[]> runs = new ArrayList<>();
            int start = -1;
            for (int x = 0; x <= width; x++) {
                boolean dark = x < width && row[x] < DARK;
                if (dark && start < 0) {
                    start = x;
                } else if (!dark && start >= 0) {
                    if (x - start >= minLength) {
                        runs.add(new int[]{start, x - 1});
                    }
                    start = -1;
                }
            }
            // Estende segmentos da linha anterior que continuam nesta; fecha os demais.
            List<Segment> nextOpen = new ArrayList<>();
            List<int[]> nextRows = new ArrayList<>();
            boolean[] used = new boolean[runs.size()];
            for (int i = 0; i < open.size(); i++) {
                Segment s = open.get(i);
                int match = -1;
                for (int r = 0; r < runs.size(); r++) {
                    if (!used[r] && runs.get(r)[0] <= s.x2() && runs.get(r)[1] >= s.x1()) {
                        match = r;
                        break;
                    }
                }
                if (match >= 0) {
                    used[match] = true;
                    int[] run = runs.get(match);
                    int[] rows = openRows.get(i);
                    nextOpen.add(new Segment(s.y(), Math.min(s.x1(), run[0]), Math.max(s.x2(), run[1]),
                            y - rows[0] + 1));
                    nextRows.add(new int[]{rows[0], y});
                } else {
                    done.add(centered(s, openRows.get(i)));
                }
            }
            for (int r = 0; r < runs.size(); r++) {
                if (!used[r]) {
                    nextOpen.add(new Segment(y, runs.get(r)[0], runs.get(r)[1], 1));
                    nextRows.add(new int[]{y, y});
                }
            }
            open = nextOpen;
            openRows = nextRows;
        }
        for (int i = 0; i < open.size(); i++) {
            done.add(centered(open.get(i), openRows.get(i)));
        }

        List<Segment> result = new ArrayList<>();
        for (Segment s : done) {
            if (s.thickness() <= maxThickness && s.length() < contentWidth * 0.9 && !continuesDownward(raster, s)) {
                result.add(s);
            }
        }
        return result;
    }

    /**
     * Canto ou juncao de tabela/caixa: ha tracos escuros logo abaixo de uma das pontas. O fim de um
     * sublinhado nunca tem nada embaixo (a proxima linha de texto comeca bem mais longe).
     */
    private static boolean continuesDownward(Raster raster, Segment s) {
        int below = s.y() + s.thickness();
        for (int end : new int[]{s.x1() + 1, s.x2() - 1}) {
            int dark = 0;
            for (int y = below + 2; y <= below + 9 && y < raster.getHeight(); y++) {
                if (raster.getSample(Math.max(0, Math.min(raster.getWidth() - 1, end)), y, 0) < DARK) {
                    dark++;
                }
            }
            if (dark >= 6) {
                return true;
            }
        }
        return false;
    }

    private static Segment centered(Segment s, int[] rows) {
        return new Segment((rows[0] + rows[1]) / 2, s.x1(), s.x2(), rows[1] - rows[0] + 1);
    }

    // ---- palavras ----

    private static String mark(Line line, List<Segment> segments, int dpi) {
        int tolerance = Math.max(8, (int) Math.round(dpi * 0.06));
        List<Word> words = line.words();
        boolean[] underlined = new boolean[words.size()];
        for (Segment s : segments) {
            List<Integer> covered = new ArrayList<>();
            for (int i = 0; i < words.size(); i++) {
                if (isWordLike(words.get(i).text()) && underlines(s, words.get(i))) {
                    covered.add(i);
                }
            }
            if (covered.isEmpty()) {
                continue;
            }
            Word first = words.get(covered.get(0));
            Word last = words.get(covered.get(covered.size() - 1));
            // O traco tem que acabar junto das palavras: borda de tabela passa muito alem do texto.
            boolean startsAtWord = s.x1() >= first.left() - tolerance;
            boolean endsAtWord = s.x2() <= last.right() + tolerance;
            // E nao pode ter folga grande tampando palavras so por cima (precisa cobrir uma sequencia).
            if (startsAtWord && endsAtWord) {
                covered.forEach(i -> underlined[i] = true);
            }
        }
        StringBuilder sb = new StringBuilder();
        boolean open = false;
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            if (underlined[i] && !open) {
                sb.append(RichText.open('u'));
                open = true;
            } else if (!underlined[i] && open) {
                // fecha antes do espaco que ja foi escrito
                sb.setLength(sb.length() - 1);
                sb.append(RichText.close('u')).append(' ');
                open = false;
            }
            sb.append(words.get(i).text());
        }
        if (open) {
            sb.append(RichText.close('u'));
        }
        return sb.toString();
    }

    /** Palavra de verdade, nao simbolo/numero solto que o OCR tirou de uma caixa, formula ou tabela. */
    private static boolean isWordLike(String text) {
        long letters = text.chars().filter(Character::isLetter).count();
        return letters >= 1 && letters >= text.length() * 0.6;
    }

    /** O traco cobre a palavra (>= 70% da largura) e esta logo abaixo da sua linha de base. */
    private static boolean underlines(Segment s, Word w) {
        int overlap = Math.min(s.x2(), w.right()) - Math.max(s.x1(), w.left());
        if (overlap < w.width() * 0.7) {
            return false;
        }
        double top = w.top() + 0.60 * w.height();
        double bottom = w.bottom() + 0.45 * w.height();
        return s.y() >= top && s.y() <= bottom;
    }

    // ---- reencaixe no texto ----

    /** Troca, em ordem, cada linha do texto pela sua versao marcada (so quando as palavras batem). */
    private static String merge(String text, List<Line> lines, List<String> marked) {
        String[] textLines = text.split("\n", -1);
        int next = 0;
        for (int i = 0; i < textLines.length; i++) {
            String t = textLines[i].trim();
            if (t.isEmpty()) {
                continue;
            }
            String normalized = t.replaceAll("\\s+", " ");
            for (int j = next; j < Math.min(lines.size(), next + 4); j++) {
                if (lines.get(j).text().replaceAll("\\s+", " ").equals(normalized)) {
                    textLines[i] = marked.get(j);
                    next = j + 1;
                    break;
                }
            }
        }
        return String.join("\n", textLines);
    }
}
