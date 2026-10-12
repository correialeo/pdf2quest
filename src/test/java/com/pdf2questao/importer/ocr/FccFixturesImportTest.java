package com.pdf2questao.importer.ocr;

import com.pdf2questao.importer.ImportReport;
import com.pdf2questao.importer.ParseResult;
import com.pdf2questao.importer.ParsedQuestion;
import com.pdf2questao.importer.SectionMap;
import com.pdf2questao.importer.parser.GabaritoEntries;
import com.pdf2questao.importer.parser.fcc.FccGabaritoParser;
import com.pdf2questao.importer.parser.fcc.FccPdfParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Roda o pipeline FCC (OCR + parser de prova + parser de gabarito + mapa de secoes) contra os PDFs
 * reais de {@code fixtures/fcc/}. O OCR de ~77 paginas leva alguns minutos na primeira vez e fica em
 * cache em {@code data/ocr-cache}. Pula sozinho se os PDFs ou o Tesseract (idioma por) nao existirem.
 * O relatorio completo e gravado em {@code target/fcc-import-report.txt}.
 */
class FccFixturesImportTest {

    private static final Path DIR = Path.of("fixtures/fcc/sefaz-sp-2026-afre-tic");
    private static final String PREFIX = "fcc-2026-sefaz-sp-auditor-fiscal-da-receita-estadual-afre-"
            + "tecnologia-da-informacao-e-comunicacao-";

    private static final OcrService OCR = new OcrService("tesseract", "por", 300, "data/ocr-cache");
    private static final FccPdfParser EXAM_PARSER = new FccPdfParser();
    private static final FccGabaritoParser GABARITO_PARSER = new FccGabaritoParser();

    private record Prova(String name, String slug, int total) {
    }

    private static final List<Prova> PROVAS = List.of(
            new Prova("P1", "conhecimentos-gerais-p1", 100),
            new Prova("P2", "conhecimentos-basicos-p2", 80),
            new Prova("P3", "conhecimentos-especificos-p3", 80));

    @BeforeAll
    static void requisitos() {
        for (Prova p : PROVAS) {
            Assumptions.assumeTrue(Files.isRegularFile(provaPdf(p)) && Files.isRegularFile(gabaritoPdf(p)),
                    "PDFs de fixtures/fcc ausentes");
        }
        Assumptions.assumeTrue(OCR.isAvailable(), "Tesseract com idioma 'por' nao esta instalado");
    }

    private static Path provaPdf(Prova p) {
        return DIR.resolve(PREFIX + p.slug() + "-prova.pdf");
    }

    private static Path gabaritoPdf(Prova p) {
        return DIR.resolve(PREFIX + p.slug() + "-gabarito.pdf");
    }

    private static String gabaritoText(Prova p) throws IOException {
        try (PDDocument document = Loader.loadPDF(Files.readAllBytes(gabaritoPdf(p)))) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static SectionMap sectionMap(Prova p) throws IOException {
        return SectionMap.parse(Files.readString(DIR.resolve("section-map-" + p.name().toLowerCase() + ".json")));
    }

    @Test
    void gabaritosImportam100e80e80RespostasSemRuido() throws IOException {
        for (Prova p : PROVAS) {
            GabaritoEntries entries = GABARITO_PARSER.parseEntries(gabaritoText(p), "");

            assertEquals(p.total(), entries.answers().size() + entries.annulled().size(), p.name());
            assertEquals(p.total(), entries.declaredTotal(), p.name());
            assertTrue(entries.warnings().isEmpty(), p.name() + ": " + entries.warnings());
            for (int n = 1; n <= p.total(); n++) {
                assertTrue(entries.answers().containsKey(n) || entries.annulled().contains(n),
                        p.name() + " sem questao " + n);
            }
            assertTrue(entries.answers().values().stream().allMatch(l -> l.matches("[A-E]")), p.name());
        }
    }

    @Test
    void provasImportamOMesmoNumeroDeQuestoesDosGabaritosComEnunciadoCincoAlternativasEDisciplina() throws IOException {
        StringBuilder report = new StringBuilder();
        List<String> problems = new ArrayList<>();

        for (Prova p : PROVAS) {
            String text = OCR.recognize(Files.readAllBytes(provaPdf(p)));
            ParseResult result = EXAM_PARSER.parse(text);
            SectionMap map = sectionMap(p);
            GabaritoEntries gabarito = GABARITO_PARSER.parseEntries(gabaritoText(p), "");

            assertTrue(result.failures().isEmpty(), p.name() + ": " + result.failures());
            assertEquals(gabarito.totalRead(), result.questions().size(), p.name() + " questoes x gabarito");

            Set<Integer> numbers = result.questions().stream().map(ParsedQuestion::number).collect(Collectors.toSet());
            for (int n = 1; n <= p.total(); n++) {
                assertTrue(numbers.contains(n), p.name() + " sem a questao " + n);
            }
            for (ParsedQuestion q : result.questions()) {
                String id = p.name() + " Q" + q.number();
                if (q.statement() == null || q.statement().isBlank()) {
                    problems.add(id + ": enunciado vazio");
                }
                for (String alt : new String[]{q.alternativeA(), q.alternativeB(), q.alternativeC(),
                        q.alternativeD(), q.alternativeE()}) {
                    if (alt == null || alt.isBlank()) {
                        problems.add(id + ": alternativa faltando");
                        break;
                    }
                }
                assertTrue(map.find(q.number()).isPresent(), id + " fora do mapa de secoes");
            }

            report.append("===== ").append(p.name()).append(" =====\n")
                    .append(ImportReport.forExam(provaPdf(p).getFileName().toString(), result, map, true)).append('\n')
                    .append(String.join("\n", ImportReport.gabaritoLines(gabarito, numbers))).append("\n\n");
        }

        Files.writeString(Path.of("target/fcc-import-report.txt"), report.toString(), StandardCharsets.UTF_8);
        assertTrue(problems.isEmpty(), "Questoes sem enunciado/5 alternativas: " + problems);
        assertFalse(report.isEmpty());
    }
}
