package com.pdf2questao.importer.ocr;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnderlineDetectorTest {

    private static final String HEADER = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n";

    private static String word(int line, int n, int left, int top, int width, String text) {
        return "5\t1\t1\t1\t" + line + "\t" + n + "\t" + left + "\t" + top + "\t" + width + "\t35\t96\t" + text + "\n";
    }

    private static BufferedImage page() {
        BufferedImage img = new BufferedImage(1200, 500, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1200, 500);
        g.dispose();
        return img;
    }

    private static void hline(BufferedImage img, int x1, int x2, int y, int thickness) {
        for (int t = 0; t < thickness; t++) {
            for (int x = x1; x <= x2; x++) {
                img.setRGB(x, y + t, Color.BLACK.getRGB());
            }
        }
    }

    // Linha 1 (top=100): "o passado que teria sido" - sublinhado de "que teria".
    private static final String LINE1 = HEADER
            + word(1, 1, 100, 100, 20, "o") + word(1, 2, 140, 100, 140, "passado")
            + word(1, 3, 300, 100, 80, "que") + word(1, 4, 400, 100, 100, "teria")
            + word(1, 5, 520, 100, 80, "sido");

    @Test
    void marcaSoAsPalavrasCobertasPeloTraco() {
        BufferedImage img = page();
        hline(img, 300, 500, 138, 2);   // logo abaixo da linha de base (top 100 + altura 35 = 135)

        String out = UnderlineDetector.annotate("o passado que teria sido", LINE1, img, 300);

        assertEquals("o passado ⟦u⟧que teria⟦/u⟧ sido", out);
    }

    @Test
    void naoMarcaLinhaDeSeparacaoQueAtravessaAPaginaInteira() {
        BufferedImage img = page();
        hline(img, 100, 600, 138, 2);   // cobre o texto todo: igual a uma regua entre questoes

        String out = UnderlineDetector.annotate("o passado que teria sido", LINE1, img, 300);

        assertEquals("o passado que teria sido", out);
    }

    @Test
    void naoMarcaBordaDeTabelaQuePassaMuitoAlemDoTexto() {
        BufferedImage img = page();
        hline(img, 150, 900, 138, 2);   // so "passado" e "que"... cobertas, mas o traco vai muito alem

        String tsv = HEADER + word(1, 1, 400, 100, 140, "BANCOS") + word(2, 1, 100, 300, 40, "x");
        String out = UnderlineDetector.annotate("BANCOS\nx", tsv, img, 300);

        assertEquals("BANCOS\nx", out);
    }

    @Test
    void letraComBarraNaBaseNaoViraSublinhado() {
        BufferedImage img = page();
        hline(img, 100, 118, 128, 2);   // a barra de um "E" (curta, dentro da propria palavra)

        String out = UnderlineDetector.annotate("E passado", HEADER + word(1, 1, 100, 100, 20, "E")
                + word(1, 2, 140, 100, 140, "passado"), img, 300);

        assertEquals("E passado", out);
    }

    @Test
    void sublinhadoQueContinuaNaLinhaDeBaixoMarcaAsDuasLinhas() {
        BufferedImage img = page();
        hline(img, 300, 600, 138, 2);   // linha 1: "que teria sido"
        hline(img, 100, 260, 188, 2);   // linha 2: "certa pelo"
        String tsv = LINE1 + word(2, 1, 100, 150, 100, "certa") + word(2, 2, 220, 150, 40, "pelo");

        String out = UnderlineDetector.annotate("o passado que teria sido\ncerta pelo", tsv, img, 300);

        assertEquals("o passado ⟦u⟧que teria sido⟦/u⟧\n⟦u⟧certa pelo⟦/u⟧", out);
    }

    @Test
    void semTracosOuTsvVazioDevolveOTextoIntacto() {
        assertEquals("abc", UnderlineDetector.annotate("abc", "", page(), 300));
        assertEquals("o passado que teria sido", UnderlineDetector.annotate("o passado que teria sido", LINE1, page(), 300));
    }

    @Test
    void linhaDoTextoQueNaoBateComOTsvFicaComoEsta() {
        BufferedImage img = page();
        hline(img, 300, 500, 138, 2);

        String out = UnderlineDetector.annotate("texto diferente\no passado que teria sido", LINE1, img, 300);

        assertEquals("texto diferente\no passado ⟦u⟧que teria⟦/u⟧ sido", out);
    }

    @Test
    void naoMarcaCantoDeTabelaComTracoVerticalAbaixoDaPonta() {
        BufferedImage img = page();
        hline(img, 300, 500, 138, 2);
        for (int y = 140; y < 170; y++) {   // a borda vertical da celula continua para baixo
            img.setRGB(301, y, Color.BLACK.getRGB());
        }

        assertEquals("o passado que teria sido", UnderlineDetector.annotate("o passado que teria sido", LINE1, img, 300));
    }

    @Test
    void naoMarcaSimboloNumeroOuFormulaSoltos() {
        BufferedImage img = page();
        hline(img, 300, 500, 138, 2);
        String tsv = HEADER + word(1, 1, 300, 100, 80, "[e]") + word(1, 2, 400, 100, 100, "800.000)");

        assertEquals("[e] 800.000)", UnderlineDetector.annotate("[e] 800.000)", tsv, img, 300));
    }
}
