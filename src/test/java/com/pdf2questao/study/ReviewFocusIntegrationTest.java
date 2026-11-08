package com.pdf2questao.study;

import com.pdf2questao.Pdf2QuestaoApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Revisão com foco: só as disciplinas mais fracas nos quizzes entram no dia, com os conceitos que mais erra. */
@SpringBootTest(classes = Pdf2QuestaoApplication.class)
class ReviewFocusIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("test.db"));
        registry.add("pdf2questao.pdf-dir", () -> tempDir.resolve("pdfs").toString());
    }

    @Autowired
    ReviewFocusService focusService;
    @Autowired
    SpacedRepetitionService spacedRepetition;
    @Autowired
    StudyTaskService taskService;
    @Autowired
    StudyTaskRepository taskRepository;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM quiz_attempt");
        jdbc.update("DELETE FROM quiz_question");
        jdbc.update("DELETE FROM quiz");
        jdbc.update("DELETE FROM lesson");
        jdbc.update("DELETE FROM study_task");
    }

    /** Uma segunda-feira próxima (dentro da janela de quizzes de agora). */
    private static LocalDate monday() {
        return LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    }

    private StudyTask due(String title, String subject, LocalDate dueOn) {
        StudyTask t = taskService.create(new StudyTaskService.TaskForm(title, null, subject, dueOn, null, null, null));
        taskService.setDone(t.getId(), true, null);
        StudyTask saved = taskRepository.findById(t.getId()).orElseThrow();
        saved.setNextReviewDate(dueOn);
        return taskRepository.save(saved);
    }

    /** Quiz com uma questão por resposta; a 1a tentativa conta (refazer certo depois não apaga o erro). */
    private void quiz(String quizSubject, String questionSubject, String concept, boolean... firstAttempts) {
        Long lesson = null;
        if (concept != null) {
            jdbc.update("INSERT INTO lesson(title, subject, content) VALUES (?, ?, '')", "Aula " + questionSubject,
                    questionSubject);
            lesson = jdbc.queryForObject("SELECT MAX(id) FROM lesson", Long.class);
        }
        jdbc.update("INSERT INTO quiz(title, subject, lesson_id) VALUES (?, ?, ?)", "Quiz", quizSubject, lesson);
        Long quiz = jdbc.queryForObject("SELECT MAX(id) FROM quiz", Long.class);
        long now = System.currentTimeMillis();
        for (boolean correct : firstAttempts) {
            jdbc.update("INSERT INTO quiz_question(quiz_id, lesson_id, subject, statement, alternatives, correct_answer,"
                    + " concepts) VALUES (?, ?, ?, 'x', '[]', 'A', ?)", quiz, lesson, questionSubject, concept);
            Long q = jdbc.queryForObject("SELECT MAX(id) FROM quiz_question", Long.class);
            jdbc.update("INSERT INTO quiz_attempt(quiz_question_id, selected_answer, correct, answered_at) VALUES (?, 'A', ?, ?)",
                    q, correct, now);
            if (!correct) {
                jdbc.update("INSERT INTO quiz_attempt(quiz_question_id, selected_answer, correct, answered_at) "
                        + "VALUES (?, 'A', 1, ?)", q, now + 1);
            }
        }
    }

    @Test
    void focoEscolheAsDisciplinasMaisFracasEDeixaOrestoAguardando() {
        LocalDate day = monday();
        // Português: 1 acerto em 4 (com o conceito pt-se errado 2 vezes); AFO: 4 em 4; Economia: sem quiz.
        quiz("Revisão", "Português", "pt-se", false, false);
        quiz("Português", "Português", null, true, false);
        quiz("AFO", "AFO", null, true, true, true, true);
        // Quiz misturado sem a matéria na questão: fora da medida.
        quiz("Misto", "Misto", null, false, false, false);
        StudyTask pt = due("Crase", "Português", day);
        StudyTask afo = due("Créditos adicionais", "AFO", day);
        StudyTask eco = due("Elasticidade", "Economia", day);

        var focus = focusService.choose(List.of(pt, afo, eco), day);
        assertEquals(List.of("Português", "Economia"), focus.foco().stream()
                .map(ReviewFocusService.FocusSubject::disciplina).toList());
        var portugues = focus.foco().get(0);
        assertEquals(25, portugues.acertoQuiz());
        assertEquals(4, portugues.respostasQuiz());
        assertEquals("pt-se", portugues.conceitosFracos().get(0).conceito());
        assertEquals(2, portugues.conceitosFracos().get(0).erros());
        assertNull(focus.foco().get(1).acertoQuiz(), "sem quiz, sem acerto");
        assertEquals(1, focus.aguardando().size());
        assertEquals("AFO", focus.aguardando().get(0).disciplina());
        assertEquals(100, focus.aguardando().get(0).acertoQuiz());
    }

    @Test
    void disciplinaForteMuitoAtrasadaVoltaAoFoco() {
        LocalDate day = monday();
        quiz("Português", "Português", null, false, false, true, true);
        quiz("AFO", "AFO", null, true, true, true, true);
        StudyTask pt = due("Crase", "Português", day);
        StudyTask afo = due("Créditos adicionais", "AFO", day.minusDays(28));
        StudyTask eco = due("Elasticidade", "Economia", day);

        var focus = focusService.choose(List.of(pt, afo, eco), day);
        assertTrue(focus.foco().stream().anyMatch(f -> f.disciplina().equals("AFO")),
                "4 semanas de atraso pesam mais que o acerto alto");
    }

    @Test
    void tarefaQueNaoEMateriaFicaAtrasDasMaterias() {
        LocalDate day = monday();
        quiz("AFO", "AFO", null, true, true, true, false);
        StudyTask semanal = due("Revisão semanal: erros", "Revisão semanal", day);
        StudyTask fcc = due("Questões FCC", "FCC", day);
        StudyTask afo = due("Créditos adicionais", "AFO", day);
        StudyTask eco = due("Elasticidade", "Economia", day);

        var focus = focusService.choose(List.of(semanal, fcc, afo, eco), day);
        assertEquals(java.util.Set.of("Economia", "AFO"), focus.foco().stream()
                .map(ReviewFocusService.FocusSubject::disciplina).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, focus.aguardando().size(), "revisão semanal e FCC esperam");
    }

    @Test
    void abreviacoesContamComoAMesmaDisciplina() {
        assertEquals(ReviewFocusService.key("Direito Financeiro"), ReviewFocusService.key("Dir. Financeiro"));
        assertEquals(ReviewFocusService.key("Matemática Financeira"), ReviewFocusService.key("Mat. Fin."));
        assertEquals("Contabilidade", ReviewFocusService.firstSpecific("Revisão", "Contabilidade", "Misto"));
    }

    @Test
    void tarefaDoDiaGuardaOFocoEConcluirAvancaSoOsEscolhidos() {
        LocalDate today = LocalDate.now();
        quiz("Português", "Português", null, false, false, false, true);
        quiz("Contabilidade", "Contabilidade", null, false, true);
        StudyTask pt = due("Crase", "Português", today);
        StudyTask ct = due("Estoques", "Contabilidade", today);
        StudyTask afo = due("Créditos adicionais", "AFO", today);
        StudyTask dir = due("Atos administrativos", "Dir. Adm.", today);
        quiz("AFO", "AFO", null, true, true, true, true, true, true);
        quiz("Dir. Adm.", "Dir. Adm.", null, true, true, true, true, true, true);

        spacedRepetition.syncReviewTask(today);
        StudyTask review = taskRepository.findAllByReviewDate(today).get(0);
        int limit = today.getDayOfWeek() == DayOfWeek.SUNDAY ? 3 : 2;
        assertEquals(limit, review.reviewItemIds().size());
        assertTrue(review.reviewItemIds().containsAll(List.of(pt.getId(), ct.getId())));
        assertTrue(review.getTitle().startsWith("Revisão · Foco: Português + Contabilidade"), review.getTitle());
        assertTrue(review.getDescription().contains("Foco Português — 25% de acerto nos quizzes"), review.getDescription());
        assertTrue(review.getDescription().contains("Aguardando:"), review.getDescription());

        // Novos erros durante o dia não trocam o foco já escolhido.
        quiz("AFO", "AFO", null, false, false, false, false, false, false, false, false, false, false);
        spacedRepetition.syncReviewTask(today);
        assertEquals(review.reviewItemIds(), taskRepository.findById(review.getId()).orElseThrow().reviewItemIds());

        // Marcar "difícil" tira do dia e repete o intervalo; concluir a tarefa avança só o que sobrou do foco.
        StudyTask hard = spacedRepetition.mark(pt.getId(), "dificil", today);
        assertEquals(today.plusDays(1), hard.getNextReviewDate());
        assertEquals(1, hard.getCurrentStep());
        assertFalse(spacedRepetition.todayItems(today).stream().anyMatch(t -> t.getId().equals(pt.getId())));
        taskService.setDone(review.getId(), true, null);
        assertEquals(today.plusDays(3), taskRepository.findById(ct.getId()).orElseThrow().getNextReviewDate());
        if (limit == 2) {
            assertEquals(today, taskRepository.findById(afo.getId()).orElseThrow().getNextReviewDate(),
                    "o que ficou aguardando não anda");
            assertEquals(today, taskRepository.findById(dir.getId()).orElseThrow().getNextReviewDate());
        }
    }
}
