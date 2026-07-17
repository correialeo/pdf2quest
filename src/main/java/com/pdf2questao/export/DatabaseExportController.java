package com.pdf2questao.export;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDate;

@Controller
public class DatabaseExportController {

    private final DatabaseExportService exportService;

    public DatabaseExportController(DatabaseExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/export/database.sql")
    public ResponseEntity<byte[]> exportDatabase() {
        byte[] dump = exportService.exportSqlDump();
        String filename = "concursos-" + LocalDate.now() + ".sql";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.parseMediaType("application/sql"))
                .body(dump);
    }
}
