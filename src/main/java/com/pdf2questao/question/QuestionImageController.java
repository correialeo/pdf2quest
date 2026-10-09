package com.pdf2questao.question;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.concurrent.TimeUnit;

@Controller
public class QuestionImageController {

    private final QuestionImageRepository questionImageRepository;

    public QuestionImageController(QuestionImageRepository questionImageRepository) {
        this.questionImageRepository = questionImageRepository;
    }

    @GetMapping("/imagens/{id}")
    public ResponseEntity<byte[]> image(@PathVariable Long id) {
        return questionImageRepository.findById(id)
                .map(img -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS))
                        .body(img.getData()))
                .orElse(ResponseEntity.notFound().build());
    }
}
