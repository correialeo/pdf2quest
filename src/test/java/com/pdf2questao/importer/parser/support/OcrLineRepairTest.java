package com.pdf2questao.importer.parser.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrLineRepairTest {

    @Test
    void bolinhasLidasComoSimbolosViramBullet() {
        String text = """
                ' Capital Social
                “ Reserva Legal
                " Retenção de Lucros: saldo remanescente.
                * Ajustes acumulados
                = Matéria-prima: R$ 30 por unidade
                n Mão de obra direta: R$ 10 por unidade""";
        for (String line : OcrLineRepair.repair(text).split("\n")) {
            assertTrue(line.startsWith("• "), line);
        }
    }

    @Test
    void aspasColadasSoViramBulletAoLadoDeOutraBolinha() {
        assertEquals("• Lucro líquido: R$ 800.000\n• Ajustes",
                OcrLineRepair.repair("\"Lucro líquido: R$ 800.000\n* Ajustes"));
        assertEquals("\"Citacao solta.", OcrLineRepair.repair("\"Citacao solta."));
    }

    @Test
    void contaComIgualNaoViraBullet() {
        assertEquals("= 5.000 x 2", OcrLineRepair.repair("= 5.000 x 2"));
    }

    @Test
    void travessoesDuplicadosViramUmSo() {
        assertEquals("– Classificação Risco Fiscal", OcrLineRepair.repair("— — Classificação Risco Fiscal"));
        assertEquals("– Município Domicílio", OcrLineRepair.repair("— Município Domicílio"));
    }

    @Test
    void rotulosRomanosLidosErrado() {
        assertEquals("IV. Pagou dividendos.", OcrLineRepair.repair("Iv. Pagou dividendos."));
        assertEquals("V. Apurou prejuízo.", OcrLineRepair.repair("v.  Apurou prejuízo."));
        assertEquals("I. Recebeu.", OcrLineRepair.repair("T. Recebeu."));
        assertEquals("II. Vendeu.", OcrLineRepair.repair("II. Vendeu."));
        assertEquals("V. A constatação.", OcrLineRepair.repair("W. A constatação."));
    }

    @Test
    void pontilhadoComValorIlegivel() {
        assertEquals("• Capital Social ..... 1.000.000", OcrLineRepair.repair(
                "' Capital Social ..... ici esierieneasereneneemcaieiecen nessa naseonecase secs ceseccnooo TODODDO"));
        assertEquals("• Reserva Legal ..... 150.000",
                OcrLineRepair.repair("“ Reserva Legal ........ seen meneeneeeecrcecao TSDDDO"));
        assertEquals("• Reserva Estatutária ..... 30.000",
                OcrLineRepair.repair("' Reserva Estatutária ................ cemarerasenana 30.000"));
    }

    @Test
    void pontilhadoMantemEstilos() {
        assertEquals("⟦h⟧• Depreciação Acumulada ..... 480.000⟦/h⟧",
                OcrLineRepair.repair("⟦h⟧\" Depreciação Acumulada ............ sena eserenecaeeeesceecarceo 480000⟦/h⟧"));
    }

    @Test
    void reticenciasNoTextoNaoMexem() {
        String line = "O autor disse... e saiu correndo";
        assertEquals(line, OcrLineRepair.repair(line));
    }

    @Test
    void alternativasDeRomanos() {
        assertEquals("I e III, apenas.", OcrLineRepair.romanList("IeTIII,apenas."));
        assertEquals("I e II, apenas.", OcrLineRepair.romanList("IeTII,apenas."));
        assertEquals("II e III, apenas.", OcrLineRepair.romanList("IIeTIII,apenas."));
        assertEquals("III e V, apenas.", OcrLineRepair.romanList("IIIleV, apenas."));
        assertEquals("III, IV, V e VI, apenas.", OcrLineRepair.romanList("III, IV,VevVI, apenas."));
        assertEquals("I, III, IV e V, apenas.", OcrLineRepair.romanList("I, III, IVeVv, apenas."));
        assertEquals("I, II, III, IV, V e VI.", OcrLineRepair.romanList("I, II, III,IV,VevI."));
        assertEquals("I e IV, apenas.", OcrLineRepair.romanList("IelIVv, apenas."));
        assertEquals("I e II.", OcrLineRepair.romanList("IelII."));
        assertEquals("III, IV e V.", OcrLineRepair.romanList("III, IVeMv."));
        assertEquals("I e III.", OcrLineRepair.romanList("IelIII."));
        assertEquals("II e III, apenas.", OcrLineRepair.romanList("Ile III, apenas."));
        assertEquals("I, II, III e IV.", OcrLineRepair.romanList("I, II, IIIe Iv."));
        assertEquals("III, apenas.", OcrLineRepair.romanList("III,apenas."));
    }

    @Test
    void sequenciasDeRomanosComTracos() {
        assertEquals("III - V - IV - I - II", OcrLineRepair.romanList("III -V -IV-I -IL"));
        // Numa sequencia cada numeral aparece uma vez: o "IT" so pode ser o I.
        assertEquals("III - V - I - IV - II", OcrLineRepair.romanList("III-V - IT -IV-IL"));
        assertEquals("I - V - IV - II - III", OcrLineRepair.romanList("Io - VW - IV-II-III"));
        assertEquals("I - IV - V - II - III", OcrLineRepair.romanList("ILo- IV- vw -II-III"));
        assertEquals("II - V - IV - I - III", OcrLineRepair.romanList("II — VW - IV-I - III"));
    }

    @Test
    void romanosComRuidoSaoMarcadosComoPalpite() {
        assertTrue(OcrLineRepair.romanListIsGuess("IelII, apenas."));
        assertTrue(OcrLineRepair.romanListIsGuess("III -V - IT -IV-IL"));
        assertFalse(OcrLineRepair.romanListIsGuess("I, II e III."));
        assertFalse(OcrLineRepair.romanListIsGuess("IVeVv, apenas."));
    }

    @Test
    void alternativaComTextoNaoEListaDeRomanos() {
        assertNull(OcrLineRepair.romanList("Isso depende do caso."));
        assertNull(OcrLineRepair.romanList("deve aplicar-se apenas."));
        assertNull(OcrLineRepair.romanList("III - V - I o - IV - II."));
        // Texto demais depois dos numerais: a alternativa fica como veio.
        assertNull(OcrLineRepair.romanList("I, II e III, o que basta."));
        assertNull(OcrLineRepair.romanList("I -II - 2IV - 31 0 — 4TII."));
    }

    @Test
    void itensDeLista() {
        assertTrue(OcrLineRepair.isListItem("• Reserva Legal"));
        assertTrue(OcrLineRepair.isListItem("– Município"));
        assertTrue(OcrLineRepair.isListItem("3. Selecionar técnicas"));
        assertTrue(OcrLineRepair.isListItem("III. Reconheceu variação"));
        assertFalse(OcrLineRepair.isListItem("completude e qualidade"));
        assertFalse(OcrLineRepair.isListItem("2026. Ano"));
    }
}
