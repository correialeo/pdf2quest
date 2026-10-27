package com.pdf2questao.importer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class PdfStorage {

    private final Path directory;

    public PdfStorage(@Value("${pdf2questao.pdf-dir:data/pdfs}") String directory) {
        this.directory = Path.of(directory);
    }

    public String store(Long importJobId, byte[] bytes) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve("prova-" + importJobId + ".pdf");
        Files.write(target, bytes);
        return target.toString();
    }

    /** Edital (PDF) anexado a um concurso: um por concurso, substitui o anterior. */
    public Path editalPath(Long concursoId) {
        return directory.resolve("editais").resolve("concurso-" + concursoId + ".pdf");
    }

    public void storeEdital(Long concursoId, byte[] bytes) throws IOException {
        if (bytes.length < 5 || !new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw new IllegalArgumentException("O edital precisa ser um PDF.");
        }
        Path target = editalPath(concursoId);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    public boolean hasEdital(Long concursoId) {
        return Files.isRegularFile(editalPath(concursoId));
    }

    public byte[] read(ImportJob job) throws IOException {
        return Files.readAllBytes(Path.of(job.getPdfPath()));
    }

    public boolean exists(ImportJob job) {
        return job.getPdfPath() != null && Files.isRegularFile(Path.of(job.getPdfPath()));
    }
}
