package com.pdf2questao.block;

import com.pdf2questao.Pdf2QuestaoApplication;
import com.pdf2questao.importer.ImportJob;
import com.pdf2questao.importer.ImportJobRepository;
import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionRepository;
import com.pdf2questao.scoring.ScoringCalculator.ScoreResult;
import com.pdf2questao.session.StudySession;
import com.pdf2questao.session.StudySessionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sobe a aplicacao num banco SQLite temporario (Flyway cria o schema), importa provas "de mentira"
 * com os nomes das FCC e confere os dois blocos de diagnostico, uma sessao de bloco e o perfil.
 */
@SpringBootTest(classes = Pdf2QuestaoApplication.class)
class BlockSessionIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("test.db"));
        registry.add("pdf2questao.pdf-dir", () -> tempDir.resolve("pdfs").toString());
    }

    @Autowired
    BlockService blockService;
    @Autowired
    StudySessionService sessionService;
    @Autowired
    QuestionRepository questionRepository;
    @Autowired
    ImportJobRepository importJobRepository;

    private Long job(String file, int count, String[][] subjectByRange, boolean annulFirst) {
        ImportJob job = new ImportJob();
        job.setFileName(file);
        job.setImportedQuestions(count);
        job.setStartedAt(LocalDateTime.now());
        job = importJobRepository.save(job);
        for (int n = 1; n <= count; n++) {
            Question q = new Question();
            q.setImportJobId(job.getId());
            q.setQuestionNumber(n);
            q.setStatement("Questao " + n);
            q.setCorrectAnswer("A");
            for (String[] range : subjectByRange) {
                if (n >= Integer.parseInt(range[0]) && n <= Integer.parseInt(range[1])) {
                    q.setSubject(range[2]);
                }
            }
            questionRepository.save(q);
        }
        return job.getId();
    }

    @Test
    void criaOsDoisBlocosDeDiagnosticoIniciaSessoesEPontuaComPerfil() {
        String prefix = "fcc-2026-sefaz-sp-auditor-fiscal-";
        Long p1 = job(prefix + "conhecimentos-gerais-p1-prova.pdf", 100, new String[][]{
                {"1", "31", "Língua Portuguesa"}, {"32", "46", "Matemática Financeira/Estatística"},
                {"47", "70", "Outras"}, {"71", "86", "Administração Geral e Pública"},
                {"87", "100", "Economia e Finanças Públicas"}}, false);
        Long p2 = job(prefix + "conhecimentos-basicos-p2-prova.pdf", 80, new String[][]{
                {"1", "26", "Direito (Constitucional, Administrativo, Civil, Empresarial, Financeiro e Penal)"},
                {"27", "56", "Direito Tributário"}, {"57", "71", "Contabilidade Geral"}, {"72", "80", "Auditoria"}}, false);
        job(prefix + "conhecimentos-especificos-p3-prova.pdf", 80, new String[][]{
                {"1", "20", "Direito Tributário Avançado"},
                {"21", "80", "Tecnologia da Informação e Comunicação"}}, false);

        assertEquals("Blocos de diagnóstico criados/atualizados.", blockService.createDiagnosticBlocks());
        // Idempotente: rodar de novo atualiza os mesmos blocos.
        blockService.createDiagnosticBlocks();
        List<StudyBlock> blocks = blockService.findAll().stream()
                .filter(b -> b.getName().startsWith("Diagnóstico APOFP")).toList();
        assertEquals(2, blocks.size());

        StudyBlock s1 = blocks.stream().filter(b -> b.getName().equals(BlockService.DIAG_1)).findFirst().orElseThrow();
        StudyBlock s2 = blocks.stream().filter(b -> b.getName().equals(BlockService.DIAG_2)).findFirst().orElseThrow();
        assertNotNull(s1.getScoringProfileId());

        // Sessao 1: P1 1-10, 32-46, 71-86, 87-100 = 10 + 15 + 16 + 14.
        BlockService.Preview preview1 = blockService.preview(s1.getId());
        assertEquals(55, preview1.total());
        assertEquals(10, preview1.bySubject().get("Língua Portuguesa"));
        assertEquals(15, preview1.bySubject().get("Matemática Financeira/Estatística"));
        assertEquals(16, preview1.bySubject().get("Administração Geral e Pública"));
        assertEquals(14, preview1.bySubject().get("Economia e Finanças Públicas"));
        assertTrue(preview1.unweightedSubjects().isEmpty(), preview1.unweightedSubjects().toString());

        // Sessao 2: P2 1-26 sem 10-17 e 22-25 (= 14), P2 57-71 (15), P3 21-40 (20).
        BlockService.Preview preview2 = blockService.preview(s2.getId());
        assertEquals(14 + 15 + 20, preview2.total());
        assertEquals(14, preview2.bySubject().get(
                "Direito (Constitucional, Administrativo, Civil, Empresarial, Financeiro e Penal)"));
        List<Question> p2Questions = questionRepository.findByImportJobId(p2);
        assertEquals(80, p2Questions.size(), "o banco nao perde nenhuma questao");
        List<Integer> direito = blockService.resolve(s2.getId()).questions().stream()
                .filter(q -> p2.equals(q.getImportJobId()) && q.getQuestionNumber() <= 26)
                .map(Question::getQuestionNumber).toList();
        assertFalse(direito.contains(10) || direito.contains(17) || direito.contains(22) || direito.contains(25));
        assertTrue(direito.contains(1) && direito.contains(9) && direito.contains(18) && direito.contains(21)
                && direito.contains(26));
        assertEquals(14, direito.size());

        // Sessao de bloco: guarda bloco e perfil; anulada sem resposta conta ponto; resultado por disciplina.
        Question toAnnul = blockService.resolve(s1.getId()).questions().stream()
                .filter(q -> q.getSubject().equals("Língua Portuguesa") && q.getQuestionNumber() == 10)
                .findFirst().orElseThrow();
        toAnnul.setAnnulled(true);
        toAnnul.setCorrectAnswer(null);
        questionRepository.save(toAnnul);

        StudySession session = sessionService.startBloco(s1.getId());
        assertEquals(s1.getId(), session.getBlockId());
        assertEquals(BlockService.DIAG_1, session.getBlockName());
        assertEquals(55, session.questionIds().size());

        List<Question> inSession = questionRepository.findAllById(session.questionIds());
        for (Question q : inSession) {
            if (q.getSubject().startsWith("Língua") && q.getQuestionNumber() <= 5) {
                sessionService.answer(session.getId(), q.getId(), "A", 1000L);   // acerta
            } else if (q.getSubject().startsWith("Economia")) {
                sessionService.answer(session.getId(), q.getId(), "B", 1000L);   // erra
            }
        }
        StudySession finished = sessionService.finish(session.getId());

        assertNull(finished.getNotaDataprev(), "sessao com perfil nao usa a nota Dataprev");
        ScoreResult score = sessionService.scoreResult(finished);
        assertNotNull(score);
        assertEquals(6, score.groups().get(0).rules().stream()
                .filter(r -> r.subject().equals("Português")).findFirst().orElseThrow().correct(),
                "5 acertos + 1 anulada sem resposta");
        assertEquals(6.0, finished.getScoreTotal());
        assertEquals(0, score.groups().get(0).rules().stream()
                .filter(r -> r.subject().equals("Economia")).findFirst().orElseThrow().correct());
        assertFalse(score.passed());
        assertEquals(6, finished.getCorrectCount());
        assertEquals(14, finished.getWrongCount());

        // O bloco pode ser apagado sem tocar nas questoes nem na sessao.
        blockService.delete(s2.getId());
        assertEquals(260, questionRepository.count());
    }
}
