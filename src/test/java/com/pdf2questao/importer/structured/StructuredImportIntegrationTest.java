package com.pdf2questao.importer.structured;

import com.pdf2questao.Pdf2QuestaoApplication;
import com.pdf2questao.importer.ImportJob;
import com.pdf2questao.importer.ImportJobRepository;
import com.pdf2questao.question.PassageRepository;
import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionRepository;
import com.pdf2questao.question.QuestionType;
import com.pdf2questao.session.StudySession;
import com.pdf2questao.session.StudySessionService;
import com.pdf2questao.session.AttemptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Importacao JSON/CSV e gabarito separado gravando num SQLite temporario (schema criado pelo Flyway). */
@SpringBootTest(classes = Pdf2QuestaoApplication.class)
class StructuredImportIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("test.db"));
        registry.add("pdf2questao.pdf-dir", () -> tempDir.resolve("pdfs").toString());
    }

    @Autowired
    StructuredImportService importService;
    @Autowired
    AnswerKeyService answerKeyService;
    @Autowired
    QuestionRepository questionRepository;
    @Autowired
    ImportJobRepository importJobRepository;
    @Autowired
    PassageRepository passageRepository;
    @Autowired
    StudySessionService sessionService;
    @Autowired
    AttemptRepository attemptRepository;
    @Autowired
    JdbcTemplate jdbc;

    private static final ImportOptions DEFAULTS = new ImportOptions("", false, false, false);

    private ImportPreview json(String prefix, String questions) {
        String json = "{\"formato\":\"pdf2questao-questoes\",\"versao\":1,"
                + "\"prova\":{\"titulo\":\"Prova " + prefix + "\",\"banca\":\"Banca X\",\"ano\":2023},"
                + "\"questoes\":[" + questions.replace("@", prefix) + "]}";
        return importService.previewJson(prefix + ".json", json.getBytes(StandardCharsets.UTF_8));
    }

    private static final String THREE = """
            {"id": "@-1", "enunciado": "@ primeira", "alternativas": ["x","y","z"], "resposta": "B",
             "textoBase": "Texto comum @", "tags": ["a","b"], "subtopicos": ["s1"], "referencias": ["Lei 1"],
             "metadados": {"k": "v"}, "categoria": "geral"},
            {"id": "@-2", "enunciado": "@ segunda", "textoBase": "Texto comum @", "resposta": "Errado"},
            {"id": "@-3", "numero": 10, "enunciado": "@ terceira", "alternativas": ["x","y"], "anulada": true}
            """;

    @Test
    void treinoComAsRespostasDoUsuarioViraSessaoCorrigida() {
        ImportPreview preview = json("treino", """
                {"id": "@-1", "enunciado": "@ um", "alternativas": ["x","y","z"], "resposta": "B", "suaResposta": "b"},
                {"id": "@-2", "enunciado": "@ dois", "resposta": "Certo", "suaResposta": "Errado", "chute": true},
                {"id": "@-3", "enunciado": "@ tres", "alternativas": ["x","y"], "resposta": "A", "suaResposta": "Q"},
                {"id": "@-4", "enunciado": "@ quatro", "alternativas": ["x","y"], "resposta": "A"}
                """);
        assertTrue(preview.getItems().get(2).getWarnings().stream().anyMatch(w -> w.contains("Sua resposta")),
                "resposta do usuario invalida so avisa");

        ImportOutcome outcome = importService.confirm(preview.getToken(), DEFAULTS);
        assertNull(outcome.error());
        assertEquals(4, outcome.created());

        var session = sessionService.getSession(outcome.trainingSessionId());
        assertEquals(com.pdf2questao.session.SessionMode.TREINO, session.getMode());
        assertNotNull(session.getFinishedAt());
        assertEquals("Prova treino", session.getBlockName());
        assertEquals(2, session.questionIds().size(), "so as questoes que o usuario respondeu");
        assertEquals(1, session.getCorrectCount());
        assertEquals(1, session.getWrongCount());
        var wrong = attemptRepository.findBySessionIdAndQuestionId(session.getId(), session.questionIds().get(1))
                .orElseThrow();
        assertEquals("E", wrong.getSelectedAnswer());
        assertTrue(wrong.getGuessed());

        // Reimportar o mesmo treino nao duplica as questoes, mas registra as respostas de novo.
        ImportOutcome again = importService.confirm(json("treino", """
                {"id": "@-1", "enunciado": "@ um", "alternativas": ["x","y","z"], "resposta": "B", "suaResposta": "A"}
                """).getToken(), DEFAULTS);
        assertEquals(0, again.created());
        assertEquals(0, sessionService.getSession(again.trainingSessionId()).getCorrectCount());
    }

    @Test
    void jsonValidoGravaQuestoesComOrigemEMetadados() {
        ImportPreview preview = json("grava", THREE);
        assertEquals(3, preview.getNewCount());

        ImportOutcome outcome = importService.confirm(preview.getToken(), DEFAULTS);
        assertNull(outcome.error());
        assertEquals(3, outcome.created());

        ImportJob job = importJobRepository.findById(outcome.importJobId()).orElseThrow();
        assertEquals("JSON", job.getSourceFormat());
        assertFalse(job.isFromPdf());
        assertEquals("Prova grava", job.getDisplayName());
        assertEquals(3, job.getImportedQuestions());
        assertEquals(2023, job.getDetectedYear());

        List<Question> questions = questionRepository.findByImportJobId(job.getId()).stream()
                .sorted(java.util.Comparator.comparing(Question::getQuestionNumber)).toList();
        Question first = questions.get(0);
        assertEquals("grava-1", first.getExternalId());
        assertEquals(1, first.getQuestionNumber());
        assertEquals("B", first.getCorrectAnswer());
        assertEquals("y", first.getAlternativeB());
        assertNull(first.getAlternativeD());
        assertEquals("a, b", first.getTags());
        assertEquals("Banca X", first.getOrganization());
        assertTrue(first.getExtraMetadata().contains("\"referencias\":[\"Lei 1\"]"));
        assertEquals(QuestionType.MULTIPLA_ESCOLHA, first.getQuestionType());

        Question second = questions.get(1);
        assertEquals("E", second.getCorrectAnswer());
        assertEquals("Certo", second.getAlternativeC());
        assertEquals("Errado", second.getAlternativeE());
        // Mesmo texto base no arquivo = um texto base so.
        assertNotNull(first.getPassageId());
        assertEquals(first.getPassageId(), second.getPassageId());
        assertEquals("Texto comum grava", passageRepository.findById(first.getPassageId()).orElseThrow().getContent());

        Question third = questions.get(2);
        assertEquals(10, third.getQuestionNumber());
        assertTrue(third.isAnnulled());
        assertNull(third.getCorrectAnswer());

        // A previa confirmada some: confirmar de novo nao duplica.
        assertNotNull(importService.confirm(preview.getToken(), DEFAULTS).error());
    }

    @Test
    void reimportacaoPulaOuAtualizaSemPerderHistorico() {
        Long jobId = importService.confirm(json("reimp", THREE).getToken(), DEFAULTS).importJobId();
        Question original = questionRepository.findByImportJobId(jobId).stream()
                .filter(q -> "reimp-1".equals(q.getExternalId())).findFirst().orElseThrow();

        // Responde a questao numa sessao: o historico nao pode mudar com a reimportacao.
        StudySession session = sessionService.startRefazer(List.of(original.getId()));
        sessionService.answer(session.getId(), original.getId(), "B", 1000L, false);
        long attemptsBefore = attemptRepository.count();

        ImportPreview again = json("reimp", THREE.replace("\"resposta\": \"B\"", "\"resposta\": \"C\""));
        assertEquals(3, again.getExistingCount());
        assertEquals(0, again.getNewCount());
        ImportOutcome skipped = importService.confirm(again.getToken(), DEFAULTS);
        assertEquals(0, skipped.created());
        assertEquals(3, skipped.skipped());
        assertEquals("B", questionRepository.findById(original.getId()).orElseThrow().getCorrectAnswer());

        ImportPreview update = json("reimp", THREE.replace("\"resposta\": \"B\"", "\"resposta\": \"C\""));
        ImportOutcome updated = importService.confirm(update.getToken(), new ImportOptions("", true, false, false));
        assertEquals(3, updated.updated());
        assertEquals(0, updated.created());
        Question after = questionRepository.findById(original.getId()).orElseThrow();
        assertEquals("C", after.getCorrectAnswer());
        assertEquals(jobId, after.getImportJobId());
        assertEquals(attemptsBefore, attemptRepository.count());
        assertTrue(attemptRepository.findAll().stream()
                .anyMatch(a -> a.getQuestionId().equals(original.getId()) && Boolean.TRUE.equals(a.getCorrect())));
    }

    @Test
    void questaoDePdfComMesmoEnunciadoViraPossivelDuplicata() {
        Question fromPdf = new Question();
        fromPdf.setStatement("Enunciado que veio do ⟦b⟧PDF⟦/b⟧ dup");
        fromPdf.setCorrectAnswer("A");
        fromPdf = questionRepository.save(fromPdf);

        ImportPreview preview = json("dup", "{\"enunciado\": \"enunciado que veio do PDF   dup\", \"resposta\": \"C\"}");
        PreviewItem item = preview.getItems().get(0);
        assertEquals(PreviewItem.Status.POSSIVEL_DUPLICATA, item.getStatus());
        assertEquals(fromPdf.getId(), item.getExistingQuestionId());
        assertEquals(0, importService.confirm(preview.getToken(), DEFAULTS).created());

        ImportPreview again = json("dup", "{\"enunciado\": \"enunciado que veio do PDF   dup\", \"resposta\": \"C\"}");
        assertEquals(1, importService.confirm(again.getToken(), new ImportOptions("", false, true, false)).created());
    }

    @Test
    void questoesComErroExigemConfirmacaoENuncaSaoGravadas() {
        long before = questionRepository.count();
        ImportPreview preview = json("erro", """
                {"id": "@-ok", "enunciado": "@ valida", "resposta": "C"},
                {"id": "@-ruim", "enunciado": "@ invalida", "alternativas": ["x"]}
                """);
        ImportOutcome refused = importService.confirm(preview.getToken(), DEFAULTS);
        assertNotNull(refused.error());
        assertEquals(before, questionRepository.count());

        ImportOutcome partial = importService.confirm(preview.getToken(), new ImportOptions("", false, false, true));
        assertEquals(1, partial.created());
        assertEquals(1, partial.skipped());
        assertTrue(questionRepository.findByExternalIdIn(List.of("erro-ruim")).isEmpty());
        ImportJob job = importJobRepository.findById(partial.importJobId()).orElseThrow();
        assertEquals(1, job.getFailedQuestions());
        assertTrue(job.getReport().contains("Questão 2: não importada"));
    }

    @Test
    void falhaNaGravacaoDesfazAImportacaoInteira() {
        long questions = questionRepository.count();
        long jobs = importJobRepository.count();
        long passages = passageRepository.count();
        jdbc.execute("CREATE TRIGGER falha_simulada BEFORE INSERT ON question WHEN NEW.statement = 'falha terceira' "
                + "BEGIN SELECT RAISE(ABORT, 'falha simulada'); END");
        try {
            ImportPreview preview = json("falha", THREE);
            ImportOutcome outcome = importService.confirm(preview.getToken(), DEFAULTS);
            assertNotNull(outcome.error());
            assertTrue(outcome.error().contains("nada foi alterado"));
        } finally {
            jdbc.execute("DROP TRIGGER falha_simulada");
        }
        assertEquals(questions, questionRepository.count());
        assertEquals(jobs, importJobRepository.count());
        assertEquals(passages, passageRepository.count());
    }

    @Test
    void csvComMapeamentoGravaComPadroesDaTela() {
        String csv = "codigo,pergunta,a,b,gabarito\ncsv-1,Pergunta csv um,x,y,B\ncsv-2,Pergunta csv dois,x,y,A\n";
        CsvUpload upload = importService.uploadCsv("planilha.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(',', upload.mapping().getDelimiter());
        ImportPreview preview = importService.previewCsv(upload.token(), upload.mapping(), "FGV", "TJ", 2022, "Lógica")
                .orElseThrow();
        assertEquals(2, preview.getNewCount());
        ImportOutcome outcome = importService.confirm(preview.getToken(), DEFAULTS);
        Question q = questionRepository.findByExternalIdIn(List.of("csv-1")).get(0);
        assertEquals("B", q.getCorrectAnswer());
        assertEquals("FGV", q.getOrganization());
        assertEquals(2022, q.getYear());
        assertEquals("Lógica", q.getSubject());
        assertEquals("CSV", importJobRepository.findById(outcome.importJobId()).orElseThrow().getSourceFormat());
    }

    // ---- gabarito separado ----

    private Long questionsWithoutAnswers(String prefix) {
        ImportPreview preview = json(prefix, """
                {"id": "@-1", "enunciado": "@ um", "alternativas": ["x","y","z"]},
                {"id": "@-2", "enunciado": "@ dois"},
                {"id": "@-3", "enunciado": "@ tres", "alternativas": ["x","y"], "resposta": "A"},
                {"id": "@-4", "enunciado": "@ quatro", "alternativas": ["x","y"]}
                """);
        return importService.confirm(preview.getToken(), DEFAULTS).importJobId();
    }

    private Question byExternalId(String id) {
        return questionRepository.findByExternalIdIn(List.of(id)).get(0);
    }

    @Test
    void gabaritoPorIdMostraTudoAntesDeGravar() {
        questionsWithoutAnswers("gid");
        String text = "id;resposta\ngid-1;c\ngid-2;Certo\ngid-3;B\ngid-x;A\ngid-4;D\n";
        AnswerKeyPreview preview = answerKeyService.preview("g.csv", text.getBytes(StandardCharsets.UTF_8),
                AnswerKeyPreview.Mode.ID, null);
        assertTrue(preview.getFileErrors().isEmpty(), preview.getFileErrors().toString());
        assertEquals(2, preview.getToApply().size());
        assertEquals(1, preview.getConflicts().size());
        assertEquals(1, preview.getUnmatched().size());
        assertEquals("gid-x", preview.getUnmatched().get(0).key());
        assertEquals(1, preview.getInvalid().size());
        assertEquals(List.of("Questão nº 4 (gid-4)"), preview.getQuestionsWithoutAnswer());
        // Nada gravado ainda.
        assertNull(byExternalId("gid-1").getCorrectAnswer());

        ImportOutcome outcome = answerKeyService.apply(preview.getToken(), false, false);
        assertNull(outcome.error());
        assertEquals("C", byExternalId("gid-1").getCorrectAnswer());
        assertEquals("C", byExternalId("gid-2").getCorrectAnswer());
        assertEquals("A", byExternalId("gid-3").getCorrectAnswer(), "conflito sem permissao nao muda");
        assertNull(byExternalId("gid-4").getCorrectAnswer());
    }

    @Test
    void conflitoSoMudaComPermissaoERespostaRepetidaDiferenteNaoEAplicada() {
        questionsWithoutAnswers("gcf");
        String text = "gcf-3;B\ngcf-1;A\ngcf-1;B\n";
        AnswerKeyPreview preview = answerKeyService.preview("g.txt", text.getBytes(StandardCharsets.UTF_8),
                AnswerKeyPreview.Mode.ID, null);
        assertEquals(1, preview.getConflicts().size());
        assertEquals(1, preview.getInvalid().size());
        assertTrue(preview.getInvalid().get(0).message().contains("respostas diferentes"));
        answerKeyService.apply(preview.getToken(), true, false);
        assertEquals("B", byExternalId("gcf-3").getCorrectAnswer());
        assertNull(byExternalId("gcf-1").getCorrectAnswer());
    }

    @Test
    void gabaritoPorNumeroExigeProvaEvidenciaEConfirmacao() {
        Long jobId = questionsWithoutAnswers("gnum");
        byte[] text = "1 - B\n2 - E\n4 - X\n".getBytes(StandardCharsets.UTF_8);

        assertFalse(answerKeyService.preview("g", text, AnswerKeyPreview.Mode.NUMERO, null).getFileErrors().isEmpty());

        AnswerKeyPreview preview = answerKeyService.preview("g", text, AnswerKeyPreview.Mode.NUMERO, jobId);
        assertEquals(3, preview.getToApply().size());
        assertNotNull(answerKeyService.apply(preview.getToken(), false, false).error());
        assertNull(byExternalId("gnum-1").getCorrectAnswer());

        assertNull(answerKeyService.apply(preview.getToken(), false, true).error());
        assertEquals("B", byExternalId("gnum-1").getCorrectAnswer());
        assertEquals("E", byExternalId("gnum-2").getCorrectAnswer());
        assertTrue(byExternalId("gnum-4").isAnnulled());

        // Numeracao que nao bate com a prova: nada e associado.
        AnswerKeyPreview wrong = answerKeyService.preview("g", "50 A\n51 B\n52 C\n1 A\n".getBytes(StandardCharsets.UTF_8),
                AnswerKeyPreview.Mode.NUMERO, jobId);
        assertTrue(wrong.getFileErrors().get(0).contains("Só 1 de 4"));
        assertFalse(wrong.isApplicable());
    }

    @Test
    void numeroRepetidoNaProvaEAmbiguo() {
        ImportJob job = new ImportJob();
        job.setFileName("ambigua.pdf");
        job = importJobRepository.save(job);
        for (int i = 0; i < 2; i++) {
            Question q = new Question();
            q.setImportJobId(job.getId());
            q.setQuestionNumber(7);
            q.setStatement("ambigua " + i);
            q.setAlternativeA("x");
            q.setAlternativeB("y");
            questionRepository.save(q);
        }
        AnswerKeyPreview preview = answerKeyService.preview("g", "7;A\n".getBytes(StandardCharsets.UTF_8),
                AnswerKeyPreview.Mode.NUMERO, job.getId());
        assertEquals(1, preview.getInvalid().size());
        assertTrue(preview.getToApply().isEmpty());
    }

    @Test
    void gabaritoJsonEChavesNumericasNoModoId() {
        questionsWithoutAnswers("gjs");
        String json = "{\"formato\":\"pdf2questao-gabarito\",\"versao\":1,\"respostas\":["
                + "{\"id\":\"gjs-1\",\"resposta\":\"C\"},{\"id\":\"gjs-4\",\"anulada\":true}]}";
        AnswerKeyPreview preview = answerKeyService.preview("g.json", json.getBytes(StandardCharsets.UTF_8),
                AnswerKeyPreview.Mode.ID, null);
        assertEquals(2, preview.getToApply().size());
        answerKeyService.apply(preview.getToken(), false, false);
        assertEquals("C", byExternalId("gjs-1").getCorrectAnswer());
        assertTrue(byExternalId("gjs-4").isAnnulled());

        AnswerKeyPreview numbers = answerKeyService.preview("g", "901;A\n902;B\n".getBytes(StandardCharsets.UTF_8),
                AnswerKeyPreview.Mode.ID, null);
        assertTrue(numbers.getFileErrors().get(0).contains("pelo número da questão"));
    }
}
