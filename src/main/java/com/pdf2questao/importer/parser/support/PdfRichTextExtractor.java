package com.pdf2questao.importer.parser.support;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

import javax.imageio.ImageIO;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Texto do PDF no formato do {@link PDFTextStripper}, anotado com {@link RichText}.
 * Sublinhado nao e propriedade da fonte: sao tracos desenhados logo abaixo da linha de base.
 */
public final class PdfRichTextExtractor {

    // Imagens menores que isso (em pontos) sao icones/marcadores, nao figuras de questao.
    private static final float MIN_IMAGE_SIZE = 40f;
    // Imagem que se repete em mais paginas que isso e logotipo de cabecalho/rodape.
    private static final int MAX_IMAGE_REPEAT = 2;
    private static final float COLUMN_GAP = 120f;
    private static final float MIN_INDENT = 8f;

    public record ExtractedImage(int index, int page, byte[] png) {
    }

    public record Extraction(String text, List<ExtractedImage> images) {
    }

    private PdfRichTextExtractor() {
    }

    public static Extraction extract(PDDocument document) throws IOException {
        RichStripper stripper = new RichStripper();
        StringWriter out = new StringWriter();
        stripper.writeText(document, out);

        Map<Object, Integer> pagesPerImage = new HashMap<>();
        for (PlacedImage img : stripper.images) {
            pagesPerImage.merge(img.key(), 1, Integer::sum);
        }
        Set<Integer> dropped = new HashSet<>();
        List<ExtractedImage> images = new ArrayList<>();
        for (PlacedImage img : stripper.images) {
            if (img.png == null || pagesPerImage.get(img.key()) > MAX_IMAGE_REPEAT) {
                dropped.add(img.index);
            } else {
                images.add(new ExtractedImage(img.index, img.page, img.png));
            }
        }
        String text = RichText.remapImages(out.toString(), i -> dropped.contains(i) ? -1 : i);
        return new Extraction(text, images);
    }

    private record Segment(float x0, float x1, float y) {
    }

    private static final class PlacedImage {
        int index;
        int page;
        byte[] png;
        float x0, x1, top;

        // Pelo conteudo: o mesmo logotipo pode vir como um objeto diferente em cada pagina.
        Object key() {
            return png == null ? this : java.nio.ByteBuffer.wrap(png);
        }
    }

    private static final class Line {
        final StringBuilder text = new StringBuilder();
        String style = "";
        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
    }

    private static final class RichStripper extends PDFTextStripper {

        final List<PlacedImage> images = new ArrayList<>();
        private List<Segment> underlines = List.of();
        private List<PlacedImage> pageImages = List.of();
        private final List<Line> pageLines = new ArrayList<>();
        private Line current = new Line();

        @Override
        protected void startPage(PDPage page) throws IOException {
            // Pagina deitada (/Rotate) so sai legivel ordenada por posicao; nas demais isso
            // misturaria as colunas.
            setSortByPosition(page.getRotation() % 360 != 0);
            GraphicsCollector collector = new GraphicsCollector(page, getCurrentPageNo(), images.size());
            collector.processPage(page);
            underlines = collector.segments;
            pageImages = collector.images;
            images.addAll(collector.images);
            pageLines.clear();
            current = new Line();
            current.text.append(RichText.page(getCurrentPageNo()));
            super.startPage(page);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            boolean perChar = text.length() == positions.size();
            for (int i = 0; i < text.length(); i++) {
                TextPosition p = positions.isEmpty() ? null : positions.get(perChar ? i : 0);
                String style = p == null ? current.style : styleOf(p);
                switchStyle(style);
                current.text.append(text.charAt(i));
                if (p != null) {
                    current.x0 = Math.min(current.x0, p.getXDirAdj());
                    current.x1 = Math.max(current.x1, p.getXDirAdj() + p.getWidthDirAdj());
                    current.bottom = Math.max(current.bottom, p.getYDirAdj());
                }
            }
        }

        @Override
        protected void writeWordSeparator() {
            current.text.append(getWordSeparator());
        }

        @Override
        protected void writeLineSeparator() {
            endLine();
        }

        @Override
        protected void writePageEnd() throws IOException {
            endLine();
            markIndentedLines();
            placeImages();
            for (Line line : pageLines) {
                output.write(line.text.toString());
                output.write(getLineSeparator());
            }
            pageLines.clear();
        }

        private void endLine() {
            switchStyle("");
            pageLines.add(current);
            current = new Line();
        }

        private void switchStyle(String style) {
            if (style.equals(current.style)) {
                return;
            }
            for (int i = current.style.length() - 1; i >= 0; i--) {
                current.text.append(RichText.close(current.style.charAt(i)));
            }
            for (int i = 0; i < style.length(); i++) {
                current.text.append(RichText.open(style.charAt(i)));
            }
            current.style = style;
        }

        private String styleOf(TextPosition p) {
            if (p.getUnicode() == null || p.getUnicode().isBlank()) {
                return current.style;
            }
            StringBuilder sb = new StringBuilder();
            PDFont font = p.getFont();
            String name = font.getName() == null ? "" : font.getName();
            PDFontDescriptor fd = font.getFontDescriptor();
            if (name.contains("Bold") || name.contains("Black") || name.contains("Heavy")
                    || (fd != null && (fd.isForceBold() || fd.getFontWeight() >= 700))) {
                sb.append('b');
            }
            if (name.contains("Italic") || name.contains("Oblique")
                    || (fd != null && fd.getItalicAngle() != 0)) {
                sb.append('i');
            }
            if (isUnderlined(p)) {
                sb.append('u');
            }
            return sb.toString();
        }

        private boolean isUnderlined(TextPosition p) {
            float baseline = p.getYDirAdj();
            float maxGap = Math.max(3f, p.getHeightDir() * 0.45f);
            float center = p.getXDirAdj() + p.getWidthDirAdj() / 2;
            for (Segment s : underlines) {
                if (s.y >= baseline - 0.5f && s.y <= baseline + maxGap && center >= s.x0 && center <= s.x1) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Marca linhas recuadas em relacao a margem da propria coluna (ex.: continuacao de
         * um item da Cebraspe, cujo numero fica na margem).
         */
        private void markIndentedLines() {
            List<Float> xs = pageLines.stream().filter(l -> l.x0 != Float.MAX_VALUE)
                    .map(l -> l.x0).sorted().toList();
            List<Float> columnStarts = new ArrayList<>();
            for (float x : xs) {
                if (columnStarts.isEmpty() || x > columnStarts.get(columnStarts.size() - 1) + COLUMN_GAP) {
                    columnStarts.add(x);
                }
            }
            for (Line line : pageLines) {
                if (line.x0 == Float.MAX_VALUE) {
                    continue;
                }
                float start = columnStarts.get(0);
                for (float c : columnStarts) {
                    if (c <= line.x0 + 0.5f) {
                        start = c;
                    }
                }
                if (line.x0 - start >= MIN_INDENT) {
                    line.text.insert(0, RichText.INDENT);
                }
            }
        }

        /** Cada imagem entra logo depois da linha mais baixa acima dela na mesma coluna. */
        private void placeImages() {
            record Placement(int lineIdx, PlacedImage img) {
            }
            List<Placement> placements = new ArrayList<>();
            for (PlacedImage img : pageImages) {
                int bestIdx = 0;
                float bestBottom = -Float.MAX_VALUE;
                for (int i = 0; i < pageLines.size(); i++) {
                    Line l = pageLines.get(i);
                    boolean sameColumn = l.x1 > img.x0 && l.x0 < img.x1;
                    if (sameColumn && l.bottom <= img.top + 2 && l.bottom > bestBottom) {
                        bestBottom = l.bottom;
                        bestIdx = i + 1;
                    }
                }
                placements.add(new Placement(bestIdx, img));
            }
            // Insere de baixo para cima: imagens que caem na mesma linha ficam na ordem da pagina.
            placements.sort(Comparator.comparingInt(Placement::lineIdx)
                    .thenComparingDouble(pl -> pl.img().top).reversed());
            for (Placement pl : placements) {
                Line imageLine = new Line();
                imageLine.text.append(RichText.image(pl.img().index));
                pageLines.add(pl.lineIdx(), imageLine);
            }
        }
    }

    /** Coordenadas convertidas para as do {@link TextPosition} (origem no topo, y para baixo). */
    private static final class GraphicsCollector extends PDFGraphicsStreamEngine {

        final List<Segment> segments = new ArrayList<>();
        final List<PlacedImage> images = new ArrayList<>();
        private final int pageNumber;
        private int nextIndex;
        private final PDRectangle box;
        private final List<float[]> pathLines = new ArrayList<>();
        private final List<float[]> pathRects = new ArrayList<>();
        private Point2D.Float currentPoint = new Point2D.Float();

        GraphicsCollector(PDPage page, int pageNumber, int firstIndex) {
            super(page);
            this.box = page.getCropBox();
            this.pageNumber = pageNumber;
            this.nextIndex = firstIndex;
        }

        @Override
        public void drawImage(PDImage pdImage) throws IOException {
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            float[] r = displayBounds(ctm.transformPoint(0, 0), ctm.transformPoint(1, 1),
                    ctm.transformPoint(0, 1), ctm.transformPoint(1, 0));
            float x0 = r[0];
            float x1 = r[1];
            float yTop = r[2];
            float yBottom = r[3];
            if (x1 - x0 < MIN_IMAGE_SIZE || yBottom - yTop < MIN_IMAGE_SIZE) {
                return;
            }
            PlacedImage img = new PlacedImage();
            img.index = nextIndex++;
            img.page = pageNumber;
            img.x0 = x0;
            img.x1 = x1;
            img.top = yTop;
            try {
                BufferedImage bi = pdImage.getImage();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ImageIO.write(bi, "png", bytes);
                img.png = bytes.toByteArray();
            } catch (IOException | RuntimeException e) {
                img.png = null;
            }
            images.add(img);
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            pathRects.add(displayBounds(p0, p1, p2, p3));
        }

        @Override
        public void moveTo(float x, float y) {
            currentPoint = new Point2D.Float(x, y);
        }

        @Override
        public void lineTo(float x, float y) {
            Point2D.Float a = toDisplay(currentPoint.x, currentPoint.y);
            Point2D.Float b = toDisplay(x, y);
            pathLines.add(new float[]{a.x, a.y, b.x, b.y});
            currentPoint = new Point2D.Float(x, y);
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            currentPoint = new Point2D.Float(x3, y3);
        }

        @Override
        public Point2D getCurrentPoint() {
            return currentPoint;
        }

        @Override
        public void closePath() {
        }

        @Override
        public void endPath() {
            clearPath();
        }

        @Override
        public void strokePath() {
            commitPath();
        }

        @Override
        public void fillPath(int windingRule) {
            commitPath();
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
            commitPath();
        }

        @Override
        public void clip(int windingRule) {
        }

        @Override
        public void shadingFill(COSName shadingName) {
        }

        private void commitPath() {
            for (float[] r : pathRects) {
                if (r[3] - r[2] <= 2.5f && r[1] - r[0] >= 2f) {
                    segments.add(new Segment(r[0], r[1], (r[2] + r[3]) / 2));
                }
            }
            for (float[] l : pathLines) {
                if (Math.abs(l[1] - l[3]) <= 0.5f && Math.abs(l[0] - l[2]) >= 2f) {
                    segments.add(new Segment(Math.min(l[0], l[2]), Math.max(l[0], l[2]), l[1]));
                }
            }
            clearPath();
        }

        private Point2D.Float toDisplay(double px, double py) {
            return new Point2D.Float((float) px - box.getLowerLeftX(), box.getUpperRightY() - (float) py);
        }

        /** {minX, maxX, minY, maxY} dos pontos, em coordenadas de exibicao. */
        private float[] displayBounds(Point2D... points) {
            float[] r = {Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE};
            for (Point2D p : points) {
                Point2D.Float d = toDisplay(p.getX(), p.getY());
                r[0] = Math.min(r[0], d.x);
                r[1] = Math.max(r[1], d.x);
                r[2] = Math.min(r[2], d.y);
                r[3] = Math.max(r[3], d.y);
            }
            return r;
        }

        private void clearPath() {
            pathRects.clear();
            pathLines.clear();
        }
    }
}
