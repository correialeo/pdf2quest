package com.pdf2questao.discursiva;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Discursivas: o Claude cria (enunciado, criterios, tempo, linhas), o usuario escreve na tela com o tempo correndo
 * e entrega; o Claude le e grava a correcao. Texto entregue nao muda mais.
 */
@Service
public class EssayService {

    /** Mais de 3 h num envio so e aba esquecida aberta, nao escrita. */
    private static final long MAX_DELTA = 3 * 3_600_000L;

    private final EssayRepository repository;

    public EssayService(EssayRepository repository) {
        this.repository = repository;
    }

    public List<Essay> all() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public Essay find(Long id) {
        return repository.findById(id).orElseThrow();
    }

    @Transactional
    public Essay create(String title, String prompt, String banca, String criteria, Integer timeLimit,
                        Integer maxLines, Double maxScore) {
        if (title == null || title.isBlank() || prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("A discursiva precisa de título e enunciado.");
        }
        Essay essay = new Essay();
        essay.setTitle(title.strip());
        essay.setPrompt(prompt.strip());
        essay.setBanca(banca);
        essay.setCriteria(criteria == null ? null : criteria.strip());
        essay.setTimeLimitMinutes(timeLimit);
        essay.setMaxLines(maxLines);
        essay.setMaxScore(maxScore);
        return repository.save(essay);
    }

    /** Salvamento automatico do rascunho; {@code millis} = tempo escrevendo desde o ultimo envio. */
    @Transactional
    public Essay saveDraft(Long id, String text, Long millis) {
        Essay essay = find(id);
        if (essay.isSubmitted()) {
            throw new IllegalStateException("Esta discursiva já foi entregue.");
        }
        if (essay.getStartedAt() == null) {
            essay.setStartedAt(LocalDateTime.now());
        }
        essay.setText(text);
        long add = millis == null ? 0 : Math.max(0, Math.min(millis, MAX_DELTA));
        essay.setActiveMillis((essay.getActiveMillis() == null ? 0 : essay.getActiveMillis()) + add);
        essay.setUpdatedAt(LocalDateTime.now());
        return repository.save(essay);
    }

    @Transactional
    public Essay submit(Long id, String text, Long millis) {
        Essay essay = saveDraft(id, text, millis);
        essay.setSubmittedAt(LocalDateTime.now());
        return repository.save(essay);
    }

    /** Correcao do Claude: nota e comentarios (Markdown). So depois de entregue. */
    @Transactional
    public Essay correct(Long id, double score, String feedback) {
        Essay essay = find(id);
        if (!essay.isSubmitted()) {
            throw new IllegalStateException("A discursiva ainda não foi entregue; peça ao usuário para entregar antes.");
        }
        if (score < 0 || essay.getMaxScore() != null && score > essay.getMaxScore()) {
            throw new IllegalArgumentException("Nota fora da escala (0 a " + essay.getMaxScore() + ").");
        }
        essay.setScore(score);
        essay.setFeedback(feedback);
        essay.setCorrectedAt(LocalDateTime.now());
        return repository.save(essay);
    }

    /**
     * Linhas que o texto ocuparia na folha de respostas: cada paragrafo quebra a cada {@code charsPerLine}
     * caracteres (estimativa; a folha manuscrita da FCC tem cerca de 70 caracteres por linha).
     */
    public static int lines(String text, int charsPerLine) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int lines = 0;
        for (String paragraph : text.strip().split("\\R")) {
            lines += Math.max(1, (int) Math.ceil(paragraph.strip().length() / (double) charsPerLine));
        }
        return lines;
    }
}
