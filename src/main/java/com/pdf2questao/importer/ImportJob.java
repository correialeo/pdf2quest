package com.pdf2questao.importer;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class ImportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fileName;
    private int importedQuestions;
    private int failedQuestions;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @Lob
    private String errorLog;

    private String detectedOrganization;
    private Integer detectedYear;
    private String banca;

    private String pdfPath;
}
