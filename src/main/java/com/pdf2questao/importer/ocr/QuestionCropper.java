package com.pdf2questao.importer.ocr;

import com.pdf2questao.importer.parser.support.RichText;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * O Tesseract nao le fracao nem tabela: o resultado e texto embaralhado. Para as questoes cujo ENUNCIADO
 * tem isso, recorta o enunciado da pagina (do numero ate a alternativa (A)) e deixa um {@code ⟦img:N⟧}
 * logo depois do numero; as alternativas continuam em texto. O texto lido do enunciado fica entre
 * {@code ⟦h⟧...⟦/h⟧}: segue no banco (busca, disciplina) mas nao aparece, para nao repetir a imagem.
 * Tambem conserta os ordinais mais previsiveis ("ao 2 ano" vira "ao 2º ano") e tira do texto o fragmento
 * solto que o OCR faz de uma fracao (o numerador cai fora do enunciado).
 *
 * <p>Nada e alterado em questoes sem formula ou tabela no enunciado. Se a pagina nao tem as linhas
 * separadoras esperadas, ela volta como veio.
 */
public final class QuestionCropper {

    private static final int DARK = 140;
    /** Uma linha cobrindo ao menos esta fracao da largura do texto separa questoes. */
    private static final double SEPARATOR_WIDTH = 0.8;
    /** Bordas de tabela: linhas finas mais curtas que um separador, mas longas demais para um sublinhado. */
    private static final double TABLE_RULE_MIN_WIDTH = 0.12;
    private static final int TABLE_RULES = 3;

    private static final Pattern NUMBER_TOKEN = Pattern.compile("^\\S{1,3}\\.$");
    private static final Pattern ALTERNATIVE_START = Pattern.compile("^[({\\[|]?[A-E0€@&]{1,2}[)\\]}].*");
    private static final Pattern MATH = Pattern.compile(
            "[=√∑∫±≤≥÷×λμσπΣ^]"
                    + "|\\b[PEV]\\s*\\(|\\bVar\\b|(?<![\\d/])\\d{1,3}\\s*/\\s*\\d{1,3}(?![\\d/])"
                    + "|\\b(log|ln|sen|cos)\\b|\\bi\\.i\\.d\\b");
    private static final String MASC = "ano|dia|mês|mes|semestre|trimestre|bimestre|quadrimestre|lugar|grau|"
            + "período|periodo|termo|quartil|decil|mandato|século|seculo|turno|nível|nivel";
    private static final String FEM = "parcela|prestação|prestacao|etapa|fase|série|serie|turma|vez|"
            + "edição|edicao|semana|linha|coluna|posição|posicao|questão|questao|"
            + "alternativa|classe|camada|forma\\s+normal";
    private static final Pattern ORDINAL_M = Pattern.compile(
            "(?i)\\b(o|no|do|ao|pelo|até o|ate o)\\s+(\\d{1,2})[°o]?\\s+(" + MASC + ")\\b");
    private static final Pattern ORDINAL_F = Pattern.compile(
            "(?i)\\b(a|na|da|à|pela|até a|ate a)\\s+(\\d{1,2})[°o]?\\s+(" + FEM + ")\\b");

    private QuestionCropper() {
    }

    /** Pagina com o texto ajustado e os recortes ({@code ⟦img:1⟧}, {@code ⟦img:2⟧}... referem-se a esta lista). */
    public record Annotated(String text, List<byte[]> crops) {
    }

    /** Questao na pagina: {@code stem} sao as linhas do enunciado, {@code stemBottom} a altura da alternativa (A). */
    private record Region(int top, int bottom, UnderlineDetector.Word number, List<UnderlineDetector.Line> lines,
                          List<UnderlineDetector.Line> stem, int stemBottom) {
    }

    public static Annotated annotate(String text, String tsv, BufferedImage image, int dpi) {
        String fixedText = fixOrdinals(text);
        List<UnderlineDetector.Line> lines = UnderlineDetector.parseLines(tsv);
        if (lines.isEmpty()) {
            return new Annotated(fixedText, List.of());
        }

        int minX = Integer.MAX_VALUE;
        int maxX = 0;
        for (UnderlineDetector.Line line : lines) {
            for (UnderlineDetector.Word w : line.words()) {
                minX = Math.min(minX, w.left());
                maxX = Math.max(maxX, w.right());
            }
        }
        int contentWidth = Math.max(1, maxX - minX);

        int[] runLength = new int[image.getHeight()];
        measureRuns(image, runLength);

        List<int[]> separators = clusters(runLength, (int) (contentWidth * SEPARATOR_WIDTH), Integer.MAX_VALUE);
        List<Region> regions = regions(lines, separators, minX, image.getHeight());
        if (regions.isEmpty()) {
            return new Annotated(fixedText, List.of());
        }

        List<String> out = new ArrayList<>(Arrays.asList(fixedText.split("\n", -1)));
        List<byte[]> crops = new ArrayList<>();
        int cursor = 0;
        for (Region region : regions) {
            String number = region.number().text();
            int at = lineStartingWith(out, number, cursor);
            if (at < 0) {
                continue;
            }
            cursor = at + 1;

            String stemText = linesText(region.stem());
            boolean table = clusters(slice(runLength, region.top(), region.stemBottom()),
                    (int) (contentWidth * TABLE_RULE_MIN_WIDTH), (int) (contentWidth * SEPARATOR_WIDTH)).size()
                    >= TABLE_RULES;
            boolean formula = MATH.matcher(stemText).find() || overlappingBlocks(region.stem());
            if (!table && !formula) {
                continue;
            }
            byte[] crop = crop(image, region, minX, maxX);
            if (crop == null) {
                continue;
            }
            crops.add(crop);
            tagStem(out, at, number, crops.size());
            cursor -= dropStrayFragments(out, at, region);
        }
        return new Annotated(String.join("\n", out), crops);
    }

    // ---- ordinais ----

    static String fixOrdinals(String text) {
        String out = ORDINAL_M.matcher(text).replaceAll("$1 $2º $3");
        return ORDINAL_F.matcher(out).replaceAll("$1 $2ª $3");
    }

    // ---- regioes ----

    /**
     * Uma regiao vai de uma linha separadora ate a proxima e so existe se o numero da questao vem logo
     * abaixo da separadora (o que as distingue de uma tabela larga, que tem linhas parecidas) e se ha
     * uma alternativa (A) depois do enunciado.
     */
    private static List<Region> regions(List<UnderlineDetector.Line> lines, List<int[]> separators, int minX,
                                        int pageHeight) {
        List<Region> regions = new ArrayList<>();
        int footerTop = (int) (pageHeight * 0.94);
        for (int s = 0; s < separators.size(); s++) {
            int top = separators.get(s)[1] + 1;
            int limit = s + 1 < separators.size() ? separators.get(s + 1)[0] : footerTop;
            List<UnderlineDetector.Line> inside = new ArrayList<>();
            for (UnderlineDetector.Line line : lines) {
                UnderlineDetector.Word first = line.words().get(0);
                int center = first.top() + first.height() / 2;
                if (center >= top && center < limit) {
                    inside.add(line);
                }
            }
            UnderlineDetector.Word number = null;
            for (UnderlineDetector.Line line : inside) {
                UnderlineDetector.Word first = line.words().get(0);
                if (first.top() - top > 200) {
                    break;
                }
                if (first.left() - minX < 60 && NUMBER_TOKEN.matcher(first.text()).matches()) {
                    number = first;
                    break;
                }
            }
            if (number == null) {
                continue;
            }
            List<UnderlineDetector.Line> stem = new ArrayList<>();
            int stemBottom = -1;
            for (UnderlineDetector.Line line : inside) {
                UnderlineDetector.Word first = line.words().get(0);
                if (first.top() > number.top() + number.height() && ALTERNATIVE_START.matcher(first.text()).matches()) {
                    stemBottom = first.top() - 4;
                    break;
                }
                stem.add(line);
            }
            if (stemBottom > top) {
                regions.add(new Region(top, limit, number, inside, stem, stemBottom));
            }
        }
        return regions;
    }

    private static String linesText(List<UnderlineDetector.Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (UnderlineDetector.Line line : lines) {
            sb.append(line.text()).append('\n');
        }
        return sb.toString();
    }

    /** Fracao: o OCR a le como um bloco a parte, que se sobrepoe ao bloco da linha onde ela esta. */
    private static boolean overlappingBlocks(List<UnderlineDetector.Line> ls) {
        for (int i = 0; i < ls.size(); i++) {
            for (int j = i + 1; j < ls.size(); j++) {
                if (stacked(ls.get(i), ls.get(j))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean stacked(UnderlineDetector.Line a, UnderlineDetector.Line b) {
        int[] x = extent(a);
        int[] y = extent(b);
        boolean xOverlap = Math.min(x[2], y[2]) - Math.max(x[0], y[0]) > 3;
        boolean yOverlap = Math.min(x[3], y[3]) - Math.max(x[1], y[1]) > 3;
        return xOverlap && yOverlap;
    }

    /** {@code [esquerda, topo, direita, base]} da linha. */
    private static int[] extent(UnderlineDetector.Line line) {
        int[] e = {Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0};
        for (UnderlineDetector.Word w : line.words()) {
            e[0] = Math.min(e[0], w.left());
            e[1] = Math.min(e[1], w.top());
            e[2] = Math.max(e[2], w.right());
            e[3] = Math.max(e[3], w.bottom());
        }
        return e;
    }

    // ---- imagem ----

    /** Maior traco escuro continuo de cada linha de pixels. */
    private static void measureRuns(BufferedImage image, int[] length) {
        Raster raster = image.getRaster();
        int width = image.getWidth();
        int[] row = new int[width];
        for (int y = 0; y < image.getHeight(); y++) {
            raster.getSamples(0, y, width, 1, 0, row);
            int runStart = -1;
            for (int x = 0; x <= width; x++) {
                boolean dark = x < width && row[x] < DARK;
                if (dark && runStart < 0) {
                    runStart = x;
                } else if (!dark && runStart >= 0) {
                    length[y] = Math.max(length[y], x - runStart);
                    runStart = -1;
                }
            }
        }
    }

    private static int[] slice(int[] values, int from, int to) {
        int lo = Math.max(0, from);
        int hi = Math.min(values.length, to);
        int[] out = new int[Math.max(0, hi - lo)];
        System.arraycopy(values, lo, out, 0, out.length);
        return out;
    }

    /** Faixas de linhas consecutivas cujo maior traco escuro mede entre min e max: {@code [primeira, ultima]}. */
    private static List<int[]> clusters(int[] runLength, int min, int max) {
        List<int[]> out = new ArrayList<>();
        int first = -1;
        for (int y = 0; y <= runLength.length; y++) {
            boolean on = y < runLength.length && runLength[y] >= min && runLength[y] <= max;
            if (on && first < 0) {
                first = y;
            } else if (!on && first >= 0) {
                out.add(new int[]{first, y - 1});
                first = -1;
            }
        }
        return out;
    }

    /** Recorta do numero da questao ate logo acima da alternativa (A), na largura do texto. */
    private static byte[] crop(BufferedImage image, Region region, int minX, int maxX) {
        int pad = 18;
        int x1 = Math.max(0, minX - pad);
        int x2 = Math.min(image.getWidth(), maxX + pad);
        int y1 = Math.max(region.top(), region.number().top() - pad);
        // Termina logo abaixo da ultima linha do enunciado, sem o branco que separa da alternativa (A).
        int lastLine = region.number().bottom();
        for (UnderlineDetector.Line line : region.stem()) {
            lastLine = Math.max(lastLine, extent(line)[3]);
        }
        int y2 = Math.min(image.getHeight(), Math.min(region.stemBottom(), lastLine + 10));
        if (y2 - y1 < 20 || x2 - x1 < 20) {
            return null;
        }
        BufferedImage copy = new BufferedImage(x2 - x1, y2 - y1, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(image.getSubimage(x1, y1, x2 - x1, y2 - y1), 0, 0, null);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(copy, "png", bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    // ---- texto ----

    private static int lineStartingWith(List<String> lines, String number, int from) {
        for (int i = from; i < lines.size(); i++) {
            if (lines.get(i).stripLeading().startsWith(number)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * "41. Um auditor..." vira "41. ⟦img:1⟧⟦h⟧Um auditor...⟦/h⟧", e cada linha seguinte do enunciado (ate a
     * alternativa (A)) e escondida do mesmo jeito. A marca e por linha porque o parser junta o enunciado linha a linha.
     */
    private static void tagStem(List<String> lines, int index, String number, int imageIndex) {
        String first = lines.get(index);
        int at = first.indexOf(number) + number.length();
        while (at < first.length() && Character.isWhitespace(first.charAt(at))) {
            at++;
        }
        lines.set(index, first.substring(0, at) + RichText.image(imageIndex) + hide(first.substring(at)));
        for (int i = index + 1; i < lines.size() && !ALTERNATIVE_START.matcher(lines.get(i).strip()).matches(); i++) {
            lines.set(i, hide(lines.get(i)));
        }
    }

    private static String hide(String line) {
        return line.isBlank() ? line : RichText.open('h') + line + RichText.close('h');
    }

    /**
     * O numerador de uma fracao ao lado do numero da questao vira uma linha solta logo antes dela
     * ("P(X=m+1)"), que o parser tomaria por texto de apoio. So sai uma linha curta que se sobrepoe
     * verticalmente ao numero e que o OCR escreveu inteira; paragrafos de verdade ficam.
     *
     * @return quantas linhas sairam (o chamador ajusta a posicao de busca)
     */
    private static int dropStrayFragments(List<String> lines, int numberLine, Region region) {
        int[] number = extent(new UnderlineDetector.Line(List.of(region.number())));
        Set<String> stray = new LinkedHashSet<>();
        for (UnderlineDetector.Line line : region.lines()) {
            int[] e = extent(line);
            boolean small = e[2] - e[0] < 400 && e[3] - e[1] < 60;
            if (small && e[1] < number[1] - 4 && e[3] > number[1] && !line.words().contains(region.number())) {
                stray.add(line.text());
            }
        }
        int removed = 0;
        for (String text : stray) {
            for (int i = numberLine - 1 - removed; i >= Math.max(0, numberLine - 4 - removed); i--) {
                if (lines.get(i).trim().equals(text)) {
                    lines.remove(i);
                    removed++;
                    break;
                }
            }
        }
        return removed;
    }
}
