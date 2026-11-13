package com.pdf2questao.mcp;

import com.pdf2questao.Pdf2QuestaoApplication;
import com.pdf2questao.concurso.ConcursoService;
import com.pdf2questao.importer.ImportJob;
import com.pdf2questao.importer.ImportJobRepository;
import com.pdf2questao.mcp.ExamMcpTools.ExamQuestion;
import com.pdf2questao.mcp.ExamMcpTools.GroupInput;
import com.pdf2questao.mcp.ExamMcpTools.SubjectInput;
import com.pdf2questao.mcp.LessonMcpTools.QuizAlternative;
import com.pdf2questao.mcp.LessonMcpTools.QuizItem;
import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionRepository;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Questoes ineditas partem das questoes REAIS da banca importadas no sistema: o exemplos_da_banca acha as do assunto
 * e, quando o sistema tem modelos da banca na disciplina, quiz e simulado exigem citar os ids lidos.
 */
@SpringBootTest(classes = Pdf2QuestaoApplication.class)
class RealModelsIntegrationTest {

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
    LessonMcpTools lessons;
    @Autowired
    QuestionRepository questionRepository;
    @Autowired
    ImportJobRepository importJobRepository;
    @Autowired
    ConcursoService concursoService;

    private static final String ESTILO = LessonMcpToolsIntegrationTest.ESTILO;
    private static final String STYLE = "FCC: ".repeat(10) + "x".repeat(600);

    private static Long jobId;
    private static Long st1;
    private static Long st2;
    private static Long lancamento;
    private static Long generated;
    private static String concurso;
    private static int seq;

    @BeforeEach
    void seed() {
        if (jobId != null) {
            return;
        }
        ImportJob job = new ImportJob();
        job.setTitle("FCC - 2026 - SEFAZ-SP - P3");
        job.setBanca("FCC");
        job.setStartedAt(LocalDateTime.now());
        jobId = importJobRepository.save(job).getId();
        st1 = real("Direito Tributário", "Impostos Estaduais, Substituição Tributária", "A LC 87 prevê, na "
                + "substituição tributária do ICMS, que a base de cálculo");
        st2 = real("Direito Tributário", "Substituição Tributária", "Na substituição tributária progressiva, o "
                + "contribuinte substituto");
        lancamento = real("Direito Tributário", "Lançamento Tributário", "O lançamento por homologação ocorre quando");
        Question fake = new Question();
        fake.setStatement("Questão escrita pelo Claude sobre substituição tributária");
        fake.setOrganization("FCC");
        fake.setSubject("Direito Tributário");
        fake.setCorrectAnswer("A");
        fake.setSource("Simulado do Claude");
        generated = questionRepository.save(fake).getId();
        concurso = concursoService.create(new ConcursoService.ConcursoForm("Fiscal FCC", "SEFAZ", "FCC", null,
                null, null, null, null, null, null, null)).getId().toString();
    }

    private Long real(String subject, String topic, String statement) {
        Question q = new Question();
        q.setStatement(statement + "...");
        q.setOrganization("FCC");
        q.setSubject(subject);
        q.setTopic(topic);
        q.setCorrectAnswer("B");
        q.setAlternativeA("a");
        q.setAlternativeB("b");
        q.setImportJobId(jobId);
        return questionRepository.save(q).getId();
    }

    private static QuizItem item(Long modelo) {
        int n = ++seq;
        String tag = java.util.stream.IntStream.range(0, 10).mapToObj(i -> "palavra" + n + "y" + i)
                .collect(java.util.stream.Collectors.joining(" "));
        return new QuizItem("Na substituição tributária do ICMS (" + tag + "), é correto afirmar:", "ST", List.of(
                new QuizAlternative("o substituto recolhe o imposto devido nas operações seguintes " + n,
                        "Certa: é a ST progressiva."),
                new QuizAlternative("o substituído recolhe o imposto devido nas operações anteriores " + n,
                        "Errada: inverte os papéis."),
                new QuizAlternative("a base de cálculo é sempre o valor da operação própria " + n,
                        "Errada: soma a margem de valor agregado.")), "A", null, null, modelo);
    }

    @Test
    void exemplosDaBancaTrazPrimeiroOsDoAssunto() {
        var samples = exam.bancaSamples("FCC", "Direito Tributário", null, null, "substituição tributária ICMS",
                null, null);
        assertEquals(3L, samples.disponiveis(), "a questão escrita pelo Claude não conta como real");
        assertEquals(3, samples.questoes().size());
        assertTrue(List.of(st1, st2).contains(samples.questoes().get(0).id()));
        assertTrue(List.of(st1, st2).contains(samples.questoes().get(1).id()));
        assertEquals(lancamento, samples.questoes().get(2).id());
        assertEquals(0, samples.questoes().get(2).relevancia());

        var others = exam.bancaSamples("FCC", "Direito Tributário", null, null, "substituição tributária",
                List.of(st1, st2), null);
        assertEquals(List.of(lancamento), others.questoes().stream().map(ExamMcpTools.SampleQuestion::id).toList());

        var none = exam.bancaSamples("FCC", "Direito Penal", null, null, null, null, null);
        assertEquals(0L, none.disponiveis());
        assertNotNull(none.aviso(), "sem questões reais no sistema, avisa para pesquisar na internet");
    }

    @Test
    void quizNivelProvaExigeModelosDoSistema() {
        String internet = "https://www.qconcursos.com/q/1\nhttps://www.qconcursos.com/q/2";
        var semLocais = assertThrows(IllegalArgumentException.class, () -> lessons.createQuiz(null, "ST",
                "Direito Tributário", "Substituição tributária", "prova", null, null, List.of(item(null)), null,
                concurso, null, ESTILO, internet));
        assertTrue(semLocais.getMessage().contains("exemplos_da_banca"), semLocais.getMessage());

        var falsa = assertThrows(IllegalArgumentException.class, () -> lessons.createQuiz(null, "ST",
                "Direito Tributário", "Substituição tributária", "prova", null, null, List.of(item(st1)), null,
                concurso, null, ESTILO, "questão local #" + st1 + "\nquestão local #" + generated));
        assertTrue(falsa.getMessage().contains("#" + generated), "questão do Claude não vale como modelo real");

        String refs = "questão local #" + st1 + "\nquestão local #" + st2 + "\n" + internet;
        var semModelo = assertThrows(IllegalArgumentException.class, () -> lessons.createQuiz(null, "ST",
                "Direito Tributário", "Substituição tributária", "prova", null, null, List.of(item(st1), item(null)),
                null, concurso, null, ESTILO, refs));
        assertTrue(semModelo.getMessage().startsWith("Pergunta 2"), semModelo.getMessage());

        var quiz = lessons.createQuiz(null, "ST", "Direito Tributário", "Substituição tributária", "prova", null,
                null, List.of(item(st1), item(st2)), null, concurso, null, ESTILO, refs);
        assertEquals(2, quiz.perguntas());

        // Disciplina sem questões reais no sistema: valem as referências da internet.
        var penal = lessons.createQuiz(null, "Penal", "Direito Penal", "Peculato", "prova", null, null,
                List.of(item(null)), null, concurso, null, ESTILO, internet);
        assertEquals(1, penal.perguntas());
    }

    @Test
    void simuladoParteDasProvasEQuestoesDoSistema() throws Exception {
        var profile = exam.saveProfile("Fiscal – teste modelos", "P1 min 1", 1.0, List.of(
                new GroupInput("P1", 1.0, List.of(new SubjectInput("Direito Tributário", List.of(), 2, 1.0),
                        new SubjectInput("Direito Penal", List.of(), 1, 1.0)))), null, null);
        var semLocal = assertThrows(IllegalArgumentException.class, () -> exam.createExam(profile.id(), "P1",
                "Sim", "FCC", 60, STYLE, "https://a\nhttps://b", null, null));
        assertTrue(semLocal.getMessage().contains("prova local #" + jobId) || semLocal.getMessage().contains("#" + jobId),
                semLocal.getMessage());

        var p1 = exam.createExam(profile.id(), "P1", "Sim", "FCC", 60, STYLE, "prova local #" + jobId
                + "\nhttps://b", null, null);
        var added = exam.addQuestions(p1.id(), List.of(
                q("Direito Tributário", null),
                q("Direito Tributário", List.of(generated)),
                q("Direito Tributário", List.of(st1)),
                q("Direito Penal", null)));
        assertEquals(2, added.gravadas(), added.recusadas().toString());
        assertEquals(2, added.recusadas().size());
        assertTrue(added.recusadas().get(0).contains("modelos"), added.recusadas().get(0));
    }

    private static ExamQuestion q(String subject, List<Long> modelos) {
        int n = ++seq;
        String tag = java.util.stream.IntStream.range(0, 8).mapToObj(i -> Integer.toString(n * 7919 + i * 104729, 36)
                + "termo").collect(java.util.stream.Collectors.joining(" "));
        return new ExamQuestion(subject, "Assunto", null, "Considere " + tag + "?", List.of("a", "b", "c", "d", "e"),
                "A", "A correta é a A porque a regra diz assim; as outras invertem o conceito.", "FCC 2026 Q12",
                "inversão de conceito", modelos);
    }
}
