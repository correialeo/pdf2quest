package com.pdf2questao.study;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DocxImportTest {

    static final String NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"";

    public static byte[] docx(String body, String numbering) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<w:document " + NS + "><w:body>" + body + "</w:body></w:document>").getBytes(StandardCharsets.UTF_8));
            if (numbering != null) {
                zip.putNextEntry(new ZipEntry("word/numbering.xml"));
                zip.write(("<w:numbering " + NS + ">" + numbering + "</w:numbering>").getBytes(StandardCharsets.UTF_8));
            }
            zip.putNextEntry(new ZipEntry("word/_rels/document.xml.rels"));
            zip.write(("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId9\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" "
                    + "Target=\"https://www.planalto.gov.br/lei\" TargetMode=\"External\"/></Relationships>").getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    public static String p(String style, String text) {
        return "<w:p>" + (style == null ? "" : "<w:pPr><w:pStyle w:val=\"" + style + "\"/></w:pPr>")
                + "<w:r><w:t>" + text + "</w:t></w:r></w:p>";
    }

    public static String row(String... cells) {
        StringBuilder sb = new StringBuilder("<w:tr>");
        for (String c : cells) {
            sb.append("<w:tc><w:p><w:r><w:t>").append(c).append("</w:t></w:r></w:p></w:tc>");
        }
        return sb.append("</w:tr>").toString();
    }

    @Test
    void converteTitulosFormatacaoListasLinksETabelas() throws Exception {
        String body = p("Heading1", "Meu resumo") + p("Heading2", "Atos")
                + "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Negrito</w:t></w:r><w:r><w:rPr><w:i/></w:rPr><w:t xml:space=\"preserve\"> itálico &amp; &lt;x&gt;</w:t></w:r></w:p>"
                + "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr><w:r><w:t>um</w:t></w:r></w:p>"
                + "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr><w:r><w:t>dois</w:t></w:r></w:p>"
                + "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"2\"/></w:numPr></w:pPr><w:r><w:t>marcador</w:t></w:r></w:p>"
                + "<w:p><w:hyperlink r:id=\"rId9\"><w:r><w:t>Lei</w:t></w:r></w:hyperlink></w:p>"
                + "<w:tbl>" + row("A", "B") + row("1", "2") + "</w:tbl>";
        String numbering = "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"decimal\"/></w:lvl></w:abstractNum>"
                + "<w:abstractNum w:abstractNumId=\"1\"><w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"bullet\"/></w:lvl></w:abstractNum>"
                + "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num><w:num w:numId=\"2\"><w:abstractNumId w:val=\"1\"/></w:num>";
        DocxReader.Result r = DocxReader.read(docx(body, numbering));
        assertEquals("Meu resumo", r.title());
        assertEquals("<h2>Atos</h2><p><strong>Negrito</strong><em> itálico &amp; &lt;x&gt;</em></p>"
                + "<ol><li>um</li><li>dois</li></ol><ul><li>marcador</li></ul>"
                + "<p><a href=\"https://www.planalto.gov.br/lei\" target=\"_blank\" rel=\"noopener noreferrer\">Lei</a></p>"
                + "<table><tbody><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></tbody></table>", r.html());
        assertEquals(List.of(List.of(List.of("A", "B"), List.of("1", "2"))), r.tables());
    }

    @Test
    void arquivoQueNaoEDocxEDtdSaoRecusados() throws Exception {
        assertThrows(DocxReader.InvalidDocxException.class, () -> DocxReader.read("nao e zip".getBytes()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><w:document>&e;</w:document>".getBytes());
        }
        assertThrows(DocxReader.InvalidDocxException.class, () -> DocxReader.read(out.toByteArray()));
    }

    @Test
    void tabelaComDatasViraTarefas() throws Exception {
        String body = "<w:tbl>" + row("Dia", "A1 – P1 (60 min)", "B – TI (90 min)")
                + row("Seg 28/12", "AFO: princípios", "Scrum Guide 2020: papéis")
                + row("Ter 29/12", "Discursiva 1 (90 min): texto", "—")
                + row("Sex 01/01", "Simulado: 80 questões, 4 horas", "") + "</w:tbl>"
                + "<w:tbl>" + row("Bloco", "Tempo") + row("A1", "60 min") + "</w:tbl>";
        DocxReader.Result r = DocxReader.read(docx(body, null));
        List<ScheduleExtractor.Item> items = ScheduleExtractor.extract(r.tables(), 2026);
        assertEquals(4, items.size());
        assertEquals(new ScheduleExtractor.Item(LocalDate.of(2026, 12, 28), "AFO: princípios", "AFO", 60, "A1 – P1 (60 min)"), items.get(0));
        assertEquals("TI", items.get(1).subject(), "topico com ano no nome usa o bloco do cabecalho");
        assertEquals(90, items.get(2).minutes(), "tempo escrito na celula vale mais que o do cabecalho");
        assertEquals("Discursiva", items.get(2).subject());
        assertEquals(LocalDate.of(2027, 1, 1), items.get(3).date(), "virada de ano");
        assertEquals(240, items.get(3).minutes());
    }

    @Test
    void cabecalhoSemDisciplina() {
        assertNull(ScheduleExtractor.subject("Correção e lista final", "Foco"));
        assertEquals("ML", ScheduleExtractor.subject("ML III: árvores", "B – TI"));
        assertEquals(2025, ScheduleExtractor.guessYear("Plano 2025", LocalDate.of(2030, 1, 1)));
    }

    static boolean realFileExists() {
        return Files.exists(REAL);
    }

    static final Path REAL = Path.of(System.getProperty("user.home"), "Downloads", "cronograma-sefaz-apofp-revisado.docx");

    /** Arquivo real do usuario (fora do repositorio): so roda na maquina onde ele existe. */
    @Test
    @EnabledIf("realFileExists")
    void cronogramaSefazApofp() throws Exception {
        DocxReader.Result r = DocxReader.read(Files.readAllBytes(REAL));
        assertTrue(r.title().startsWith("Cronograma diário SEFAZ-SP APOFP"));
        List<ScheduleExtractor.Item> items = ScheduleExtractor.extract(r.tables(), ScheduleExtractor.guessYear(r.text(), LocalDate.now()));
        assertEquals(199, items.size());
        assertEquals(LocalDate.of(2026, 10, 14), items.get(0).date());
        assertEquals(LocalDate.of(2026, 12, 13), items.get(items.size() - 1).date());
        assertTrue(items.stream().anyMatch(i -> i.date().equals(LocalDate.of(2026, 10, 15))
                && i.title().startsWith("Scrum Guide 2020") && "TI".equals(i.subject()) && i.minutes() == 90));
    }
}
