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

    public byte[] read(ImportJob job) throws IOException {
        return Files.readAllBytes(Path.of(job.getPdfPath()));
    }

    public boolean exists(ImportJob job) {
        return job.getPdfPath() != null && Files.isRegularFile(Path.of(job.getPdfPath()));
    }
}
