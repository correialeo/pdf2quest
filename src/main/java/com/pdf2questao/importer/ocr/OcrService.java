package com.pdf2questao.importer.ocr;

import com.pdf2questao.importer.parser.support.PdfRichTextExtractor.ExtractedImage;
import com.pdf2questao.importer.parser.support.RichText;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * OCR de PDFs sem camada de texto (paginas que sao so imagem) usando o Tesseract
 * instalado na maquina. Cada pagina e renderizada em {@code dpi} (300 por padrao),
 * reconhecida em paralelo e guardada em cache em disco, indexada pelo hash do PDF (e pelo formato do texto) -
 * reimportar o mesmo arquivo nao roda o OCR de novo. O texto devolvido segue o mesmo
 * formato dos extratores de PDF: uma linha {@code ⟦p:N⟧} abre cada pagina, e o sublinhado do PDF volta como
 * {@code ⟦u⟧...⟦/u⟧} (ver {@link UnderlineDetector}).
 */
@Component
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    /** Media de caracteres por pagina abaixo da qual o PDF e tratado como imagem pura. */
    private static final int MIN_CHARS_PER_PAGE = 40;
    private static final long PAGE_TIMEOUT_SECONDS = 180;

    private final String tesseractPath;
    private final String language;
    private final int dpi;
    private final Path cacheDir;

    public OcrService(@Value("${pdf2questao.ocr.tesseract-path:tesseract}") String tesseractPath,
                      @Value("${pdf2questao.ocr.language:por}") String language,
                      @Value("${pdf2questao.ocr.dpi:300}") int dpi,
                      @Value("${pdf2questao.ocr.cache-dir:data/ocr-cache}") String cacheDir) {
        this.tesseractPath = tesseractPath;
        this.language = language;
        this.dpi = dpi;
        this.cacheDir = Path.of(cacheDir);
    }

    /** Progresso do OCR: paginas prontas ({@code done}) de {@code total}, e quantas vieram do cache. */
    public record Progress(int done, int total, int fromCache) {
    }

    /** True se o Tesseract roda e tem o idioma configurado instalado. */
    public boolean isAvailable() {
        try {
            Process process = new ProcessBuilder(tesseractPath, "--list-langs").redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(30, TimeUnit.SECONDS);
            return out.lines().anyMatch(l -> l.trim().equals(language));
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** PDF cuja camada de texto e (quase) vazia: tipicamente paginas escaneadas/geradas como imagem. */
    public boolean needsOcr(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int pages = document.getNumberOfPages();
            if (pages == 0) {
                return false;
            }
            String text = new PDFTextStripper().getText(document);
            int chars = text.replaceAll("\\s+", "").length();
            return chars < (long) MIN_CHARS_PER_PAGE * pages;
        }
    }

    /** Texto de todas as paginas e os recortes de questao ({@code ⟦img:N⟧} no texto aponta para eles). */
    public record Result(String text, List<ExtractedImage> images) {
    }

    private record Page(String text, List<byte[]> crops) {
    }

    public String recognize(byte[] pdf) throws IOException {
        return recognize(pdf, p -> { }).text();
    }

    public Result recognize(byte[] pdf, Consumer<Progress> onProgress) throws IOException {
        Path pdfCache = cacheDir.resolve(cacheKey(pdf));
        Files.createDirectories(pdfCache);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            int pages = document.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(document);
            int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
            AtomicInteger done = new AtomicInteger();
            AtomicInteger cached = new AtomicInteger();
            Semaphore inFlight = new Semaphore(threads * 2);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<Page>> results = new ArrayList<>();
            try {
                for (int page = 0; page < pages; page++) {
                    Path cacheFile = pdfCache.resolve(String.format("page-%03d.txt", page + 1));
                    if (Files.isRegularFile(cacheFile)) {
                        results.add(java.util.concurrent.CompletableFuture.completedFuture(readCached(cacheFile)));
                        reportProgress(onProgress, done.incrementAndGet(), pages, cached.incrementAndGet());
                        continue;
                    }
                    inFlight.acquireUninterruptibly();
                    // O PDFBox nao e thread-safe: renderiza na thread atual, so o Tesseract roda em paralelo.
                    BufferedImage image = renderer.renderImageWithDPI(page, dpi, ImageType.GRAY);
                    int pageNumber = page + 1;
                    results.add(pool.submit(() -> {
                        try {
                            Page result = runTesseract(image, pageNumber);
                            writeCache(cacheFile, result);
                            reportProgress(onProgress, done.incrementAndGet(), pages, cached.get());
                            return result;
                        } finally {
                            inFlight.release();
                        }
                    }));
                }

                StringBuilder out = new StringBuilder();
                List<ExtractedImage> images = new ArrayList<>();
                for (int i = 0; i < results.size(); i++) {
                    Page page = await(results.get(i));
                    // Os recortes de cada pagina sao numerados a partir de 1; aqui viram indices do documento.
                    int offset = images.size();
                    for (byte[] crop : page.crops()) {
                        images.add(new ExtractedImage(images.size() + 1, i + 1, crop));
                    }
                    out.append(RichText.page(i + 1)).append('\n')
                            .append(RichText.remapImages(page.text(), n -> offset + n)).append('\n');
                }
                return new Result(out.toString(), images);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private void reportProgress(Consumer<Progress> onProgress, int done, int total, int cached) {
        log.info("OCR: pagina {}/{}{}", done, total, cached > 0 ? " (" + cached + " do cache)" : "");
        onProgress.accept(new Progress(done, total, cached));
    }

    private Page await(Future<Page> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("OCR interrompido", e);
        } catch (ExecutionException e) {
            throw new IOException("Falha no OCR: " + e.getCause().getMessage(), e.getCause());
        }
    }

    private Page runTesseract(BufferedImage image, int pageNumber) throws IOException {
        Path png = Files.createTempFile("pdf2questao-ocr-" + pageNumber + "-", ".png");
        Path base = png.resolveSibling(png.getFileName().toString().replaceAll("\\.png$", ""));
        Path txt = Path.of(base + ".txt");
        Path tsv = Path.of(base + ".tsv");
        try {
            ImageIO.write(image, "png", png.toFile());
            // psm 4: coluna unica de texto de tamanhos variados - mantem alternativas e paragrafos separados.
            // txt + tsv na mesma execucao: o tsv traz as caixas das palavras para achar o sublinhado.
            ProcessBuilder builder = new ProcessBuilder(tesseractPath, png.toString(), base.toString(),
                    "-l", language, "--psm", "4", "--dpi", String.valueOf(dpi), "txt", "tsv");
            // Cada processo usa uma thread: o paralelismo vem das paginas, nao do OpenMP do Tesseract.
            builder.environment().put("OMP_THREAD_LIMIT", "1");
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();
            try {
                if (!process.waitFor(PAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("Tesseract excedeu o tempo na pagina " + pageNumber);
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("OCR interrompido", e);
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(txt)) {
                throw new IOException("Tesseract falhou na pagina " + pageNumber + " (codigo " + process.exitValue()
                        + "). O idioma '" + language + "' esta instalado?");
            }
            String text = Files.readString(txt, StandardCharsets.UTF_8);
            if (Files.isRegularFile(tsv)) {
                String tsvText = Files.readString(tsv, StandardCharsets.UTF_8);
                try {
                    text = UnderlineDetector.annotate(text, tsvText, image, dpi);
                } catch (RuntimeException e) {
                    // Sem sublinhado a pagina continua valida: so perde a marcacao.
                    log.warn("Sublinhado nao detectado na pagina {}: {}", pageNumber, e.toString());
                }
                try {
                    QuestionCropper.Annotated annotated = QuestionCropper.annotate(text, tsvText, image, dpi);
                    return new Page(annotated.text(), annotated.crops());
                } catch (RuntimeException e) {
                    // Sem recortes a pagina continua valida: so perde a imagem da formula/tabela.
                    log.warn("Recortes de questao nao gerados na pagina {}: {}", pageNumber, e.toString());
                }
            }
            return new Page(text, List.of());
        } finally {
            Files.deleteIfExists(png);
            Files.deleteIfExists(txt);
            Files.deleteIfExists(tsv);
        }
    }

    private Page readCached(Path cacheFile) throws IOException {
        List<byte[]> crops = new ArrayList<>();
        for (int n = 1; Files.isRegularFile(cropFile(cacheFile, n)); n++) {
            crops.add(Files.readAllBytes(cropFile(cacheFile, n)));
        }
        return new Page(Files.readString(cacheFile, StandardCharsets.UTF_8), crops);
    }

    private void writeCache(Path cacheFile, Page page) throws IOException {
        for (int n = 1; n <= page.crops().size(); n++) {
            Files.write(cropFile(cacheFile, n), page.crops().get(n - 1));
        }
        // O texto por ultimo: a pagina so conta como em cache quando os recortes ja estao no disco.
        Files.writeString(cacheFile, page.text(), StandardCharsets.UTF_8);
    }

    private static Path cropFile(Path cacheFile, int n) {
        return cacheFile.resolveSibling(cacheFile.getFileName().toString().replace(".txt", "-q" + n + ".png"));
    }

    private String cacheKey(byte[] pdf) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(pdf);
            // "-u4": formato com sublinhado e recortes de questao; caches antigos nao servem mais.
            return HexFormat.of().formatHex(hash, 0, 12) + "-" + language + "-" + dpi + "-u4";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
