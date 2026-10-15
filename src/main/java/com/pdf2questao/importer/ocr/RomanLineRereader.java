package com.pdf2questao.importer.ocr;

import com.pdf2questao.importer.parser.support.OcrLineRepair;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Alternativas so com numerais romanos ("(A) I, II, III e V.") vem em fonte monoespacada, e lidas junto
 * com a pagina o Tesseract as destroi ("III IIIev."): junta numerais, perde virgulas. Lida sozinha, como uma
 * linha (psm 7), a mesma imagem sai quase sempre certa ("I, II, III ev."). Aqui cada alternativa assim e
 * relida e, se a nova leitura forma uma lista de romanos valida, substitui a original no texto da pagina.
 */
final class RomanLineRereader {

    /** Le uma imagem de uma linha so. */
    interface LineReader {
        String read(BufferedImage line) throws IOException;
    }

    private static final Pattern MARKER = Pattern.compile("^[({\\[|]?[A-E0€@&48]{1,2}[)\\]}]$");
    /** Corpo da alternativa: numerais (com o ruido de sempre), separadores e no maximo ", apenas.". */
    private static final Pattern ROMAN_BODY = Pattern.compile(
            "^[IVXivxlLTWwMo|!1,e\\s\\-–—]+(?:,?\\s*apenas)?\\.?$");
    private static final int PAD = 12;
    private static final int MARGIN = 20;

    private RomanLineRereader() {
    }

    static String reread(String text, List<UnderlineDetector.Line> lines, BufferedImage image, LineReader reader)
            throws IOException {
        List<String> out = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        boolean changed = false;
        for (UnderlineDetector.Line line : lines) {
            List<UnderlineDetector.Word> words = line.words();
            if (words.size() < 2 || !MARKER.matcher(words.get(0).text()).matches()) {
                continue;
            }
            List<UnderlineDetector.Word> body = words.subList(1, words.size());
            String old = String.join(" ", body.stream().map(UnderlineDetector.Word::text).toList());
            if (!ROMAN_BODY.matcher(old).matches() || !old.matches(".*[IVX].*")) {
                continue;
            }
            String fresh = reader.read(crop(image, words.get(0), body)).strip();
            // So troca por uma leitura limpa: so numerais e separadores, formando uma lista valida.
            if (fresh.equals(old) || !ROMAN_BODY.matcher(fresh).matches() || OcrLineRepair.romanList(fresh) == null) {
                continue;
            }
            changed |= replace(out, words.get(0).text(), old, fresh);
        }
        return changed ? String.join("\n", out) : text;
    }

    private static boolean replace(List<String> out, String marker, String old, String fresh) {
        for (int i = 0; i < out.size(); i++) {
            String l = out.get(i).strip();
            if (l.startsWith(marker) && l.substring(marker.length()).strip().equals(old)) {
                out.set(i, marker + " " + fresh);
                return true;
            }
        }
        return false;
    }

    /** Do fim do marcador ao fim da linha: o OCR as vezes nem cria palavra para o comeco ("I, II,"). */
    private static BufferedImage crop(BufferedImage image, UnderlineDetector.Word marker,
                                      List<UnderlineDetector.Word> words) {
        int x1 = marker.right();
        int y1 = marker.top();
        int x2 = 0;
        int y2 = 0;
        for (UnderlineDetector.Word w : words) {
            y1 = Math.min(y1, w.top());
            x2 = Math.max(x2, w.right());
            y2 = Math.max(y2, w.bottom());
        }
        x1 = Math.min(image.getWidth() - 1, x1 + PAD);
        y1 = Math.max(0, y1 - PAD);
        x2 = Math.min(image.getWidth(), x2 + PAD);
        y2 = Math.min(image.getHeight(), y2 + PAD);
        // Margem branca em volta: o Tesseract le pior texto encostado na borda da imagem.
        BufferedImage framed = new BufferedImage(x2 - x1 + 2 * MARGIN, y2 - y1 + 2 * MARGIN, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = framed.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, framed.getWidth(), framed.getHeight());
            g.drawImage(image.getSubimage(x1, y1, x2 - x1, y2 - y1), MARGIN, MARGIN, null);
        } finally {
            g.dispose();
        }
        return framed;
    }
}
