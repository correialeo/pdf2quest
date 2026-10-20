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

    private static final String STYLE = "FCC: ".repeat(10) + "x".repeat(600);

    private static int counter;

    private static ExamQuestion q(String subject, String answer) {
        return new ExamQuestion(subject, "Assunto", null, "Enunciado " + (++counter) + " de " + subject + "?",
                List.of("a", "b", "c", "d", "e"), answer, "A correta é a " + answer + " porque a regra diz assim; "
                + "as outras invertem o conceito.", "FCC 2024 Q12", "inversão de conceito");
    }

    @Test
    void perfilSimuladoEmLotesViraBlocoEResultadoAnalisado() throws Exception {
        var profile = exam.saveProfile("Teste – C03", "P1 min 3", 5.0, List.of(
                new GroupInput("P1", 3.0, List.of(new SubjectInput("Português", List.of("Língua Portuguesa"), 2, 1.0),
                        new SubjectInput("AFO", List.of(), 2, 2.0))),
                new GroupInput("P2", 3.0, List.of(new SubjectInput("TI", List.of(), 2, 3.0)))));
        assertEquals(2, profile.provas().size());
        assertEquals(1, exam.examStructure("teste").size());

        assertThrows(IllegalArgumentException.class, () -> exam.createExam(profile.id(), "P1", "Sim", "FCC", 240,
                "curto", "a\nb"), "sem pesquisa da banca nao cria");
        assertThrows(IllegalArgumentException.class, () -> exam.createExam(profile.id(), "P1", "Sim", "FCC", 240,
                STYLE, "so uma prova"), "precisa de duas provas analisadas");

        var p1 = exam.createExam(profile.id(), "P1", "Simulado P1", "FCC", 240, STYLE, "prova local #1\nhttps://x/prova.pdf");
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

        exam.addQuestions(p1.id(), List.of(q("AFO", "E")));
        var p2 = exam.createExam(profile.id(), "P2", "Simulado P2", "FCC", 240, STYLE, "a\nb");
        exam.addQuestions(p2.id(), List.of(q("TI", "A"), q("TI", "B")));
        var block = exam.finishExam(List.of(p1.id(), p2.id()), "Dia de prova", false);
        assertEquals(480, block.tempoMinutos());
        assertEquals(6, block.questoes());
        assertTrue(exam.examStatus(null).isEmpty(), "sem rascunhos abertos");

        StudySession session = sessionService.startBloco(block.blocoId());
        assertEquals(6, session.questionIds().size());
        assertEquals(480, session.getTimeLimitMinutes());
        for (Long id : session.questionIds()) {
            sessionService.answer(session.getId(), id, "A", 30_000L);
        }
        sessionService.finish(session.getId());

        var result = insight.sessionResult(session.getId());
        assertEquals(6, result.questoes());
        assertEquals(2, result.acertos());   // Português A, TI A
        assertTrue(result.notaProjetada() != null);
        assertEquals(4, result.erradas().size());
        assertEquals(1, insight.examHistory().size());
        assertTrue(insight.errorSummary().totalDeErros() >= 4);
        var explanation = blockService.questionIds(block.blocoId()).size();
        assertEquals(6, explanation);
    }

    @Test
    void discursivaCriadaEscritaEntregueECorrigida() {
        var created = essays.createEssay("P3 – Caso", "Analise o caso...", "FCC", "Conteúdo – 80\nLíngua – 40",
                90, 30, 120.0);
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
        assertEquals("corrigida", essays.listEssays().get(0).situacao());
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
