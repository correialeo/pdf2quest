package com.pdf2questao.mcp;

import com.pdf2questao.Pdf2QuestaoApplication;
import com.pdf2questao.block.BlockService;
import com.pdf2questao.discursiva.EssayService;
import com.pdf2questao.mcp.ExamMcpTools.ExamQuestion;
import com.pdf2questao.mcp.ExamMcpTools.GroupInput;
import com.pdf2questao.mcp.ExamMcpTools.SubjectInput;
import com.pdf2questao.session.StudySession;
import com.pdf2questao.session.StudySessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Perfil do edital, simulado inedito em lotes ate virar bloco resolvivel, analise da sessao e discursiva. */
@SpringBootTest(classes = Pdf2QuestaoApplication.class)
class ExamMcpToolsIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("test.db"));
        registry.add("pdf2questao.pdf-dir", () -> tempDir.resolve("pdfs").toString());
    }

    @Autowired
    ExamMcpTools exam;
    @Autowired
    InsightMcpTools insight;
    @Autowired
    EssayMcpTools essays;
    @Autowired
    EssayService essayService;
    @Autowired
    BlockService blockService;
    @Autowired
    StudySessionService sessionService;
    @Autowired
    ReviewMcpTools review;
    @Autowired
    com.pdf2questao.question.QuestionRepository questionRepository;

    /** Questao real da banca, para servir de referencia nas revisoes. */
    private Long realReference() {
        var q = new com.pdf2questao.question.Question();
        q.setStatement("Questão real da FCC: " + statement(++counter));
        q.setOrganization("FCC");
        q.setCorrectAnswer("A");
        q.setAlternativeA("x");
        q.setAlternativeB("y");
        return questionRepository.save(q).getId();
    }

    private static List<ReviewMcpTools.CriterionInput> notes(int n) {
        return java.util.Collections.nCopies(8, new ReviewMcpTools.CriterionInput(n, "compatível com a referência lida"));
    }

    /** Revisa e aprova as questoes 1..n do simulado (conceito estavel, resposta independente = gabarito). */
    private void approveAll(Long examId) {
        Long ref = realReference();
        for (var q : exam.examStatus(examId).get(0).gravadas()) {
            review.reviewFidelity(examId, q.numero(), notes(4), List.of(new ReviewMcpTools.ReferenceInput(ref, null, null)),
                    null);
            review.validateMaterial(examId, q.numero(), "CONCEITO_ESTAVEL", q.resposta(), null, false, null, null,
                    false, null, "fundamento");
        }
    }

    private static final String STYLE = "FCC: ".repeat(10) + "x".repeat(600);

    private static int counter;

    /** Enunciado com palavras proprias de cada questao (enunciados parecidos seriam duplicatas). */
    private static String statement(int n) {
        StringBuilder sb = new StringBuilder("Considere");
        for (int i = 1; i <= 6; i++) {
            sb.append(' ').append(Integer.toString(n * 7919 + i * 104729, 36)).append("palavra");
        }
        return sb.toString();
    }

    private static ExamQuestion q(String subject, String answer) {
        return new ExamQuestion(subject, "Assunto", null, statement(++counter) + " (" + subject + ")?",
                List.of("a", "b", "c", "d", "e"), answer, "A correta é a " + answer + " porque a regra diz assim; "
                + "as outras invertem o conceito.", "FCC 2024 Q12", "inversão de conceito");
    }

    @Test
    void perfilSimuladoEmLotesViraBlocoEResultadoAnalisado() throws Exception {
        var profile = exam.saveProfile("Teste – C03", "P1 min 3", 5.0, List.of(
                new GroupInput("P1", 3.0, List.of(new SubjectInput("Português", List.of("Língua Portuguesa"), 2, 1.0),
                        new SubjectInput("AFO", List.of(), 2, 2.0))),
                new GroupInput("P2", 3.0, List.of(new SubjectInput("TI", List.of(), 2, 3.0)))), null);
        assertEquals(2, profile.provas().size());
        assertEquals(1, exam.examStructure("teste – c03", null).size());

        assertThrows(IllegalArgumentException.class, () -> exam.createExam(profile.id(), "P1", "Sim", "FCC", 240,
                "curto", "a\nb", null), "sem pesquisa da banca nao cria");
        assertThrows(IllegalArgumentException.class, () -> exam.createExam(profile.id(), "P1", "Sim", "FCC", 240,
                STYLE, "so uma prova", null), "precisa de duas provas analisadas");

        var p1 = exam.createExam(profile.id(), "P1", "Simulado P1", "FCC", 240, STYLE, "prova local #1\nhttps://x/prova.pdf", null);
        assertEquals(2, p1.disciplinas().size());

        var added = exam.addQuestions(p1.id(), List.of(q("Língua Portuguesa", "A"), q("Português", "B"),
                q("Português", "C"), q("Direito Penal", "A"), q("AFO", "D")));
        assertEquals(3, added.gravadas());
        assertEquals(2, added.recusadas().size(), "excedente de Português e disciplina fora do edital");
        List<ExamQuestion> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(q("AFO", "A"));
        }
        assertThrows(IllegalArgumentException.class, () -> exam.addQuestions(p1.id(), six), "no maximo 5 por vez");
        assertThrows(IllegalArgumentException.class, () -> exam.finishExam(List.of(p1.id()), null, false),
                "falta 1 de AFO");

        // Gabarito errado: corrige; questao ruim: remove e reescreve.
        var fixed = exam.fixQuestion(p1.id(), 1, null, null, "c", "Na verdade a C, porque a regra mudou em 2024.",
                "gabarito errado");
        assertEquals("C", fixed.resposta());
        assertThrows(IllegalArgumentException.class, () -> exam.fixQuestion(p1.id(), 1, null, null, "Z", null, null));
        var afterRemove = exam.removeQuestion(p1.id(), 3);
        assertEquals(2, afterRemove.questoes());
        exam.addQuestions(p1.id(), List.of(q("AFO", "B")));
        exam.addQuestions(p1.id(), List.of(q("AFO", "E")));
        var p2 = exam.createExam(profile.id(), "P2", "Simulado P2", "FCC", 240, STYLE, "a\nb", null);
        exam.addQuestions(p2.id(), List.of(q("TI", "A"), q("TI", "B")));
        assertThrows(IllegalArgumentException.class, () -> exam.finishExam(List.of(p1.id(), p2.id()), "Dia de prova",
                false), "sem revisão aprovada não finaliza");
        approveAll(p1.id());
        approveAll(p2.id());
        // Corrigir depois de aprovar invalida a revisão daquela questão.
        exam.fixQuestion(p2.id(), 1, null, null, null, "Comentário reescrito com mais detalhe sobre a regra.", "ajuste");
        assertEquals(com.pdf2questao.simulado.review.ReviewStatus.GERADA, review.reviewState(p2.id(), 1).estado());
        assertTrue(review.reviewState(p2.id(), 1).alteradaDepoisDaRevisao());
        assertThrows(IllegalArgumentException.class, () -> exam.finishExam(List.of(p1.id(), p2.id()), "Dia de prova", false));
        approveAll(p2.id());
        var audit = review.audit(List.of(p1.id(), p2.id()));
        assertEquals(6, audit.aprovadas());
        assertTrue(audit.falhasCriticas().isEmpty());
        var block = exam.finishExam(List.of(p1.id(), p2.id()), "Dia de prova", false);
        assertEquals(480, block.tempoMinutos());
        assertEquals(6, block.questoes());
        assertTrue(exam.examStatus(null).stream().noneMatch(d -> d.id().equals(p1.id()) || d.id().equals(p2.id())),
                "finalizados saem dos rascunhos");

        StudySession session = sessionService.startBloco(block.blocoId());
        assertEquals(6, session.questionIds().size());
        assertEquals(480, session.getTimeLimitMinutes());
        for (Long id : session.questionIds()) {
            sessionService.answer(session.getId(), id, "A", 30_000L);
        }
        sessionService.finish(session.getId());

        var result = insight.sessionResult(session.getId());
        assertEquals(6, result.questoes());
        assertEquals(1, result.acertos());   // TI A (a Q1 de Português foi corrigida para C)
        assertTrue(result.notaProjetada() != null);
        assertEquals(5, result.erradas().size());
        assertEquals(1, insight.examHistory(null).size());
        assertTrue(insight.errorSummary(null).totalDeErros() >= 4);
        var explanation = blockService.questionIds(block.blocoId()).size();
        assertEquals(6, explanation);
    }

    @Test
    void foraDoEditalEReprovadaAparecemNaAuditoriaEBloqueiam() throws Exception {
        var profile = exam.saveProfile("Teste – edital", null, null, List.of(
                new GroupInput("P1", null, List.of(new SubjectInput("Dir. Constitucional", List.of(), 2, 1.0)))), null);
        review.saveTopics(profile.id(), "Dir. Constitucional", List.of("Precatórios", "Direitos fundamentais"));
        var p1 = exam.createExam(profile.id(), "P1", "Sim edital", "FCC", 60, STYLE, "a\nb", null);
        exam.addQuestions(p1.id(), List.of(q("Dir. Constitucional", "C"), q("Dir. Constitucional", "A")));
        Long ref = realReference();
        var refs = List.of(new ReviewMcpTools.ReferenceInput(ref, null, null));

        var outside = review.reviewFidelity(p1.id(), 1, notes(4), refs, "Direito Penal");
        assertTrue(outside.problemas().stream().anyMatch(p -> p.contains("não está no conteúdo programático")));
        review.validateMaterial(p1.id(), 1, "CONCEITO_ESTAVEL", "C", null, false, null, null, false, null, null);
        assertEquals(com.pdf2questao.simulado.review.ReviewStatus.EM_REVISAO, review.reviewState(p1.id(), 1).estado());

        // Q2: precatórios com o prazo anterior à EC 136/2025 como vigente.
        review.reviewFidelity(p1.id(), 2, notes(4), refs, "Precatórios");
        var stale = review.validateMaterial(p1.id(), 2, "LEGISLACAO", "A", null, false, null, List.of(
                new ReviewMcpTools.SourceInput("https://www.planalto.gov.br/ccivil_03/constituicao/emendas/emc/emc136.htm",
                        true, "CF, art. 100, § 5º", "2026-10-20", "ALTERADA", "prazo mudou")), false, null, null);
        assertEquals(com.pdf2questao.simulado.review.ReviewStatus.REPROVADA, stale.estado());

        var audit = review.audit(List.of(p1.id()));
        assertEquals(0, audit.aprovadas());
        assertEquals(1, audit.falhasCriticas().size());
        assertEquals(2, audit.falhasCriticas().get(0).numero());
        assertTrue(audit.conclusao().contains("NÃO está validado"));
        assertTrue(audit.porDisciplina().get(0).topicosSemQuestao().contains("Direitos fundamentais"));
        assertThrows(IllegalArgumentException.class, () -> exam.finishExam(List.of(p1.id()), null, false));
        assertNull(review.blindQuestion(p1.id(), 2).textoBase());
    }

    @Test
    void discursivaCriadaEscritaEntregueECorrigida() {
        var created = essays.createEssay("P3 – Caso", "Analise o caso...", "FCC", "Conteúdo – 80\nLíngua – 40",
                90, 30, 120.0, null);
        essayService.saveDraft(created.id(), "Primeiro parágrafo.", 60_000L);
        assertThrows(IllegalStateException.class, () -> essays.correctEssay(created.id(), 80, "ok"),
                "so corrige depois de entregue");
        essayService.submit(created.id(), "Primeiro parágrafo.\nSegundo parágrafo.", 30_000L);

        var text = essays.readEssay(created.id());
        assertTrue(text.entregue());
        assertEquals(2, text.linhasUsadas());
        assertEquals(1, text.minutosEscrevendo());
        assertThrows(IllegalArgumentException.class, () -> essays.correctEssay(created.id(), 130, "x"));
        var corrected = essays.correctEssay(created.id(), 84.5, "## Conteúdo\n- faltou citar a LRF");
        assertEquals(84.5, corrected.nota());
        assertEquals("corrigida", essays.listEssays(null).get(0).situacao());
        assertThrows(IllegalStateException.class, () -> essayService.saveDraft(created.id(), "mudou", 0L));
    }

    @Test
    void lePaginasDoEditalPorTermo() throws Exception {
        Path pdf = tempDir.resolve("edital.pdf");
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            for (String text : new String[]{"Capitulo I disposicoes", "Quadro de provas C03 Peso 3"}) {
                var page = new org.apache.pdfbox.pdmodel.PDPage();
                doc.addPage(page);
                try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                    stream.beginText();
                    stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                            org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700);
                    stream.showText(text);
                    stream.endText();
                }
            }
            doc.save(pdf.toFile());
        }
        var found = exam.readEdital(pdf.toString(), "c03, inexistente", null, null);
        assertEquals(2, found.totalDePaginas());
        assertEquals(1, found.paginas().size());
        assertEquals(2, found.paginas().get(0).pagina());
        assertNull(found.aviso());
        assertThrows(IllegalArgumentException.class, () -> exam.readEdital("/nao/existe.pdf", null, null, null));
    }
}
