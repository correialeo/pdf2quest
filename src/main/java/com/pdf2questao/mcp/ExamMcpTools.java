package com.pdf2questao.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdf2questao.block.StudyBlock;
import com.pdf2questao.importer.ImportJobRepository;
import com.pdf2questao.importer.structured.QuestionDraft;
import com.pdf2questao.question.PassageRepository;
import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionRepository;
import com.pdf2questao.scoring.ScoringCalculator.ProfileSpec;
import com.pdf2questao.scoring.ScoringProfile;
import com.pdf2questao.scoring.ScoringProfileService;
import com.pdf2questao.simulado.GeneratedExamService;
import com.pdf2questao.simulado.GeneratedExamService.AddResult;
import com.pdf2questao.simulado.GeneratedExamService.ExamStatus;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ferramentas do simulado criado pelo Claude: ler o edital, gravar o perfil de pontuacao, estudar provas reais da
 * banca e montar o simulado questao a questao, nas medidas do edital.
 */
@Component
public class ExamMcpTools {

    private static final int MAX_EDITAL_CHARS = 30000;
    private static final int MAX_SAMPLES = 10;

    private final ScoringProfileService profileService;
    private final GeneratedExamService examService;
    private final QuestionRepository questionRepository;
    private final ImportJobRepository importJobRepository;
    private final PassageRepository passageRepository;
    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExamMcpTools(ScoringProfileService profileService, GeneratedExamService examService,
                        QuestionRepository questionRepository, ImportJobRepository importJobRepository,
                        PassageRepository passageRepository, @Value("${server.port:8080}") int port) {
        this.passageRepository = passageRepository;
        this.profileService = profileService;
        this.examService = examService;
        this.questionRepository = questionRepository;
        this.importJobRepository = importJobRepository;
        this.baseUrl = "http://localhost:" + port;
    }

    // ---- roteiro ----

    @McpPrompt(name = "montar_simulado", title = "Montar simulado inédito da banca",
            description = "Roteiro para criar um simulado inédito, nas medidas do edital e no estilo real da banca.")
    public String buildExamPrompt(
            @McpArg(name = "edital", description = "Caminho do PDF do edital (opcional se o perfil já existe).",
                    required = false) String edital,
            @McpArg(name = "cargo", description = "Cargo/área do edital, ex.: C03.", required = false) String cargo,
            @McpArg(name = "provas", description = "Quais provas montar, ex.: P1 e P2 (vazio = todas as objetivas).",
                    required = false) String provas) {
        return """
                Monte um simulado inédito que pareça uma prova REAL da banca. Isto é demorado de propósito: \
                qualidade e fidelidade à banca importam mais que velocidade. Não pule etapas.

                1. ESTRUTURA. %s Use estrutura_do_edital para ver se o perfil já existe. Se não existir (ou estiver \
                diferente do edital), leia o edital com ler_edital (procure o quadro das provas, os mínimos e o \
                conteúdo programático do cargo %s) e grave com salvar_perfil_edital: cada disciplina com nº de \
                questões, peso e apelidos (os nomes que aparecem nas provas reais). Anote o tempo de cada prova.

                2. PESQUISA DA BANCA (crucial). Antes de escrever qualquer questão:
                   a) Pesquise na internet provas anteriores da MESMA banca, de preferência da mesma área/cargo e \
                dos últimos anos (PDFs de provas e gabaritos, PCI Concursos, sites de questões, análises de \
                professores). Leia pelo menos 2 provas reais inteiras, não só resumos.
                   b) Leia as provas reais que já estão no sistema: provas_da_banca e exemplos_da_banca (com provaId, \
                página a página, até o fim da prova).
                   c) Para cada disciplina, registre: formato e tamanho dos enunciados, comandos típicos \
                ("é correto afirmar", "EXCETO", "NÃO", "I, II e III"), tipos de distratores, assuntos mais \
                cobrados, nível de dificuldade, se usa caso prático/texto base, e as pegadinhas recorrentes.
                   Escreva esse perfil em estiloDaBanca e as provas lidas em fontes ao chamar criar_simulado.

                3. ESCRITA, QUESTÃO A QUESTÃO. Para cada prova (%s), crie o rascunho com criar_simulado e escreva \
                as questões na quantidade exata do edital por disciplina, distribuindo os assuntos como a banca \
                distribui. Para CADA questão: baseie-se num padrão real (inspiracao), aplique uma pegadinha típica \
                (pegadinha), escreva 5 alternativas no estilo da banca com distratores plausíveis, confira que só \
                existe UMA resposta correta e que o gabarito está certo pela lei/teoria vigente, e escreva o \
                gabarito comentado (por que a certa está certa e cada distrator errado). Envie no máximo 5 por \
                chamada de adicionar_questoes e corrija as recusadas.

                4. REVISÃO. Antes de finalizar, releia o conjunto: distribuição dos gabaritos (sem padrão óbvio, \
                letras equilibradas), repetição de assuntos, dificuldade parecida com a da banca. Use \
                status_simulado para ver o que falta.

                5. FINALIZAR. finalizar_simulado com os rascunhos (P1 e P2 juntos = dia de prova, com a soma dos \
                tempos). Passe ao usuário o link do bloco. Não mostre gabaritos nem comentários na conversa: o \
                usuário vai resolver no sistema.
                """.formatted(
                has(edital) ? "Edital: " + edital + "." : "Se o usuário não indicou o edital, pergunte o caminho do PDF.",
                has(cargo) ? cargo : "do usuário",
                has(provas) ? provas : "cada prova objetiva do perfil");
    }

    private static boolean has(String s) {
        return !McpText.blank(s);
    }

    // ---- edital e perfil ----

    public record EditalPage(int pagina, String texto) {
    }

    public record EditalText(String arquivo, int totalDePaginas, List<EditalPage> paginas, String aviso) {
    }

    @McpTool(name = "ler_edital",
            description = "Lê o texto de um PDF de edital do computador do usuário. Com 'termos', devolve só as "
                    + "páginas que contêm algum deles (ex.: \"C03, Peso, Conteúdo programático\"); sem termos, as "
                    + "páginas do intervalo pedido. Até ~30 mil caracteres por chamada: peça mais páginas se precisar.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public EditalText readEdital(
            @McpToolParam(description = "Caminho absoluto do PDF (ex.: /Users/.../Downloads/edital.pdf).") String caminho,
            @McpToolParam(description = "Termos separados por vírgula (sem diferenciar maiúsculas).", required = false)
            String termos,
            @McpToolParam(description = "Primeira página (1 = primeira).", required = false) Integer paginaInicial,
            @McpToolParam(description = "Última página.", required = false) Integer paginaFinal) throws IOException {
        Path path = Path.of(caminho.strip().replaceFirst("^~", System.getProperty("user.home")));
        if (!path.toString().toLowerCase(Locale.ROOT).endsWith(".pdf") || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Não encontrei o PDF: " + path);
        }
        List<String> terms = McpText.blank(termos) ? List.of() : Arrays.stream(termos.split(","))
                .map(t -> t.strip().toLowerCase(Locale.ROOT)).filter(t -> !t.isEmpty()).toList();
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            int total = document.getNumberOfPages();
            int from = Math.max(1, paginaInicial == null ? 1 : paginaInicial);
            int to = Math.min(total, paginaFinal == null ? total : paginaFinal);
            PDFTextStripper stripper = new PDFTextStripper();
            List<EditalPage> pages = new ArrayList<>();
            int chars = 0;
            String warning = null;
            for (int p = from; p <= to; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(document).strip();
                String lower = text.toLowerCase(Locale.ROOT);
                if (!terms.isEmpty() && terms.stream().noneMatch(lower::contains)) {
                    continue;
                }
                if (chars + text.length() > MAX_EDITAL_CHARS) {
                    warning = "Parou na página " + (p - 1) + " pelo tamanho; chame de novo com paginaInicial=" + p + ".";
                    break;
                }
                chars += text.length();
                pages.add(new EditalPage(p, text));
            }
            if (pages.isEmpty() && warning == null) {
                warning = terms.isEmpty() ? "Nenhuma página no intervalo." : "Nenhuma página com esses termos.";
            }
            return new EditalText(path.getFileName().toString(), total, pages, warning);
        }
    }

    public record ProfileRule(String disciplina, List<String> apelidos, double peso, Integer questoes) {
    }

    public record ProfileGroup(String prova, Double minimo, List<ProfileRule> disciplinas) {
    }

    public record ProfileView(Long id, String nome, String descricao, Double minimoTotal, List<ProfileGroup> provas) {
    }

    @McpTool(name = "estrutura_do_edital",
            description = "Perfis de pontuação cadastrados (estrutura do edital): provas, disciplinas com nº de "
                    + "questões e peso, mínimos. Sem 'perfil', lista todos.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public List<ProfileView> examStructure(
            @McpToolParam(description = "Parte do nome do perfil ou o id (vazio = todos).", required = false) String perfil) {
        return profileService.findAll().stream()
                .filter(p -> McpText.blank(perfil) || p.getId().toString().equals(perfil.strip())
                        || p.getName().toLowerCase(Locale.ROOT).contains(perfil.strip().toLowerCase(Locale.ROOT)))
                .map(this::view).toList();
    }

    private ProfileView view(ScoringProfile profile) {
        ProfileSpec spec = profileService.toSpec(profile);
        return new ProfileView(profile.getId(), profile.getName(), profile.getDescription(), spec.minTotal(),
                spec.groups().stream().map(g -> new ProfileGroup(g.name(), g.minScore(), g.rules().stream()
                        .map(r -> new ProfileRule(r.subject(), List.copyOf(r.aliases()), r.weight(), r.questionCount()))
                        .toList())).toList());
    }

    public record SubjectInput(
            @McpToolParam(description = "Nome da disciplina como no edital.") String disciplina,
            @McpToolParam(description = "Outros nomes da mesma disciplina (como aparecem nas provas reais).",
                    required = false) List<String> apelidos,
            @McpToolParam(description = "Questões dessa disciplina na prova.") Integer questoes,
            @McpToolParam(description = "Peso de cada questão.") Double peso) {
    }

    public record GroupInput(
            @McpToolParam(description = "Nome da prova, ex.: P1.") String prova,
            @McpToolParam(description = "Pontos mínimos nessa prova.", required = false) Double minimo,
            @McpToolParam(description = "Disciplinas da prova.") List<SubjectInput> disciplinas) {
    }

    @McpTool(name = "salvar_perfil_edital",
            description = "Cria ou atualiza (pelo nome) o perfil de pontuação a partir da estrutura do edital: provas "
                    + "objetivas com disciplinas, nº de questões, pesos e mínimos. Não inclua a discursiva aqui.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    public ProfileView saveProfile(
            @McpToolParam(description = "Nome do perfil, ex.: \"SEFAZ-SP APOFP 2026 – C03\".") String nome,
            @McpToolParam(description = "Resumo das regras (mínimos, totais).", required = false) String descricao,
            @McpToolParam(description = "Mínimo total das objetivas.", required = false) Double minimoTotal,
            @McpToolParam(description = "Provas objetivas.") List<GroupInput> provas) throws JsonProcessingException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", nome);
        root.put("description", descricao);
        root.put("minTotal", minimoTotal);
        List<Map<String, Object>> groups = new ArrayList<>();
        for (GroupInput g : provas == null ? List.<GroupInput>of() : provas) {
            List<Map<String, Object>> rules = new ArrayList<>();
            for (SubjectInput r : g.disciplinas() == null ? List.<SubjectInput>of() : g.disciplinas()) {
                Map<String, Object> rule = new LinkedHashMap<>();
                rule.put("subject", r.disciplina());
                rule.put("aliases", r.apelidos() == null ? List.of() : r.apelidos());
                rule.put("weight", r.peso());
                rule.put("questionCount", r.questoes());
                rules.add(rule);
            }
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("name", g.prova());
            group.put("minScore", g.minimo());
            group.put("rules", rules);
            groups.add(group);
        }
        root.put("groups", groups);
        return view(profileService.saveFromJson(mapper.writeValueAsString(root)));
    }

    // ---- provas reais da banca ----

    public record BancaExam(Long provaId, String titulo, int questoes, Integer ano, String link) {
    }

    @McpTool(name = "provas_da_banca",
            description = "Provas reais da banca já importadas no sistema (PDFs de provas anteriores), para estudar o "
                    + "estilo. Leia cada uma com exemplos_da_banca(provaId=...).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public List<BancaExam> bancaExams(@McpToolParam(description = "Banca, ex.: FCC.") String banca) {
        List<BancaExam> out = new ArrayList<>();
        for (Object[] row : questionRepository.bancaJobs(banca.strip())) {
            Long jobId = ((Number) row[0]).longValue();
            importJobRepository.findById(jobId).ifPresent(job -> out.add(new BancaExam(jobId, job.getDisplayName(),
                    ((Number) row[1]).intValue(), job.getDetectedYear(), baseUrl + "/import/" + jobId + "/questoes")));
        }
        out.sort(Comparator.comparing(BancaExam::provaId));
        return out;
    }

    public record SampleQuestion(Long id, Long provaId, Integer numero, String disciplina, String textoBase,
                                 String enunciado, Map<String, String> alternativas, String gabarito,
                                 boolean anulada) {
    }

    public record Samples(List<SampleQuestion> questoes, Integer proximoInicio) {
    }

    @McpTool(name = "exemplos_da_banca",
            description = "Questões REAIS da banca (com gabarito) para estudar o estilo. Com provaId, lê a prova na "
                    + "ordem, 10 por vez a partir de 'inicio' (use proximoInicio para continuar até o fim); sem "
                    + "provaId, sorteia questões da banca, opcionalmente de uma disciplina.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public Samples bancaSamples(
            @McpToolParam(description = "Banca, ex.: FCC.") String banca,
            @McpToolParam(description = "Parte do nome da disciplina (só no sorteio).", required = false) String disciplina,
            @McpToolParam(description = "Prova a ler inteira (de provas_da_banca).", required = false) Long provaId,
            @McpToolParam(description = "Posição inicial na prova (0 = começo).", required = false) Integer inicio) {
        List<Question> questions;
        Integer next = null;
        if (provaId != null) {
            List<Question> all = questionRepository.findByImportJobId(provaId).stream()
                    .filter(q -> q.getOrganization() == null || q.getOrganization().equalsIgnoreCase(banca.strip()))
                    .sorted(Comparator.comparing(Question::getQuestionNumber, Comparator.nullsLast(Integer::compare)))
                    .toList();
            int from = Math.max(0, inicio == null ? 0 : inicio);
            questions = all.subList(Math.min(from, all.size()), Math.min(from + MAX_SAMPLES, all.size()));
            next = from + MAX_SAMPLES < all.size() ? from + MAX_SAMPLES : null;
        } else {
            questions = questionRepository.findBancaSamples(banca.strip(),
                    McpText.blank(disciplina) ? null : disciplina.strip(), MAX_SAMPLES);
        }
        Map<Long, String> passages = new LinkedHashMap<>();
        List<SampleQuestion> out = new ArrayList<>();
        for (Question q : questions) {
            Map<String, String> alternatives = new LinkedHashMap<>();
            q.alternatives().forEach((k, v) -> alternatives.put(k, McpText.plain(v)));
            // Texto base inteiro so na primeira questao que o usa; nas seguintes, so a referencia.
            String passage = null;
            if (q.getPassageId() != null) {
                passage = passages.containsKey(q.getPassageId()) ? "(mesmo texto base da questão "
                        + passages.get(q.getPassageId()) + ")"
                        : passageRepository.findById(q.getPassageId())
                        .map(p -> McpText.clip(McpText.plain(p.getContent()), 6000)).orElse(null);
                passages.putIfAbsent(q.getPassageId(), String.valueOf(q.getQuestionNumber()));
            }
            out.add(new SampleQuestion(q.getId(), q.getImportJobId(), q.getQuestionNumber(), q.getSubject(),
                    passage, McpText.clip(McpText.plain(q.getStatement()), 3000), alternatives, q.getCorrectAnswer(),
                    q.isAnnulled()));
        }
        return new Samples(out, next);
    }

    // ---- simulado ----

    @McpTool(name = "criar_simulado",
            description = "Abre o rascunho de um simulado INÉDITO para uma prova do perfil (ex.: P1). Exige a pesquisa "
                    + "da banca já feita: 'estiloDaBanca' com o perfil detalhado (formato dos enunciados, comandos, "
                    + "distratores e pegadinhas por disciplina) e 'fontes' com ao menos 2 provas reais lidas. Devolve "
                    + "quantas questões criar de cada disciplina. Veja o roteiro no prompt montar_simulado.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public ExamStatus createExam(
            @McpToolParam(description = "Id do perfil (estrutura_do_edital).") Long perfilId,
            @McpToolParam(description = "Prova do perfil, ex.: P1 (vazio = todas as provas num simulado só).",
                    required = false) String prova,
            @McpToolParam(description = "Título, ex.: \"Simulado inédito FCC – APOFP C03 – P1 #1\".") String titulo,
            @McpToolParam(description = "Banca imitada, ex.: FCC.") String banca,
            @McpToolParam(description = "Tempo da prova em minutos (do edital).", required = false) Integer tempoMinutos,
            @McpToolParam(description = "Perfil da banca levantado na pesquisa (mínimo 600 caracteres).") String estiloDaBanca,
            @McpToolParam(description = "Provas reais analisadas, uma por linha (link ou \"prova local #id\").") String fontes) {
        return examService.create(perfilId, prova, titulo, banca, tempoMinutos, estiloDaBanca, fontes);
    }

    public record ExamQuestion(
            @McpToolParam(description = "Disciplina (nome ou apelido do perfil).") String disciplina,
            @McpToolParam(description = "Assunto do conteúdo programático.") String assunto,
            @McpToolParam(description = "Texto base, se a questão tiver (repita igual nas questões que o usam).",
                    required = false) String textoBase,
            @McpToolParam(description = "Enunciado completo, no estilo da banca.") String enunciado,
            @McpToolParam(description = "Alternativas na ordem A–E, só o texto (5 na FCC).") List<String> alternativas,
            @McpToolParam(description = "Letra correta.") String resposta,
            @McpToolParam(description = "Gabarito comentado: por que a correta está certa e cada distrator errado.")
            String explicacao,
            @McpToolParam(description = "Padrão real em que se baseou (prova/questão ou tipo de questão da banca).")
            String inspiracao,
            @McpToolParam(description = "Pegadinha da banca aplicada (ou \"questão direta\").") String pegadinha) {

        QuestionDraft toDraft() {
            String explanation = McpText.blank(explicacao) ? null : explicacao.strip()
                    + (McpText.blank(pegadinha) ? "" : "\n\nPegadinha: " + pegadinha.strip())
                    + (McpText.blank(inspiracao) ? "" : "\nInspirada em: " + inspiracao.strip());
            return new QuestionDraft(enunciado, alternativas, resposta, null, null, disciplina, assunto, explanation,
                    textoBase);
        }
    }

    @McpTool(name = "adicionar_questoes",
            description = "Acrescenta até 5 questões ao rascunho do simulado. Cada uma precisa de disciplina do perfil, "
                    + "gabarito comentado, inspiração e pegadinha. Devolve o que entrou, o que foi recusado (com o "
                    + "motivo) e quanto falta por disciplina.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public AddResult addQuestions(
            @McpToolParam(description = "Id do simulado (criar_simulado).") Long simuladoId,
            @McpToolParam(description = "De 1 a 5 questões.") List<ExamQuestion> questoes) {
        List<String> missing = new ArrayList<>();
        List<QuestionDraft> drafts = new ArrayList<>();
        for (int i = 0; i < (questoes == null ? 0 : questoes.size()); i++) {
            ExamQuestion q = questoes.get(i);
            if (McpText.blank(q.inspiracao()) || McpText.blank(q.pegadinha())) {
                missing.add("Questão " + (i + 1) + ": informe 'inspiracao' e 'pegadinha'.");
            } else {
                drafts.add(q.toDraft());
            }
        }
        if (drafts.isEmpty()) {
            throw new IllegalArgumentException(missing.isEmpty() ? "Envie ao menos uma questão." : String.join(" ", missing));
        }
        AddResult result = examService.add(simuladoId, drafts);
        if (missing.isEmpty()) {
            return result;
        }
        List<String> refused = new ArrayList<>(missing);
        refused.addAll(result.recusadas());
        return new AddResult(result.gravadas(), refused, result.status());
    }

    public record FixedQuestion(int numero, String disciplina, String resposta, String link) {
    }

    @McpTool(name = "corrigir_questao_simulado",
            description = "Corrige uma questão já gravada num simulado (número da questão na prova, ver "
                    + "status_simulado): enunciado, alternativas, resposta e/ou gabarito comentado; o que não for "
                    + "enviado fica como está. Funciona também depois de finalizado (respostas já dadas são "
                    + "recorrigidas). Use quando perceber gabarito ou comentário errado.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    public FixedQuestion fixQuestion(
            @McpToolParam(description = "Id do simulado.") Long simuladoId,
            @McpToolParam(description = "Número da questão na prova (1 = primeira).") Integer numero,
            @McpToolParam(description = "Novo enunciado.", required = false) String enunciado,
            @McpToolParam(description = "Novas alternativas A–E (todas).", required = false) List<String> alternativas,
            @McpToolParam(description = "Nova letra correta.", required = false) String resposta,
            @McpToolParam(description = "Novo gabarito comentado.", required = false) String explicacao) {
        var q = examService.fix(simuladoId, numero, enunciado, alternativas, resposta, explicacao);
        return new FixedQuestion(q.getQuestionNumber(), q.getSubject(), q.getCorrectAnswer(),
                baseUrl + "/import/" + q.getImportJobId() + "/questoes");
    }

    @McpTool(name = "remover_questao_simulado",
            description = "Remove uma questão de um simulado ainda em rascunho (número na prova), para reescrevê-la "
                    + "com adicionar_questoes. Depois de finalizado, use corrigir_questao_simulado.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true))
    public ExamStatus removeQuestion(
            @McpToolParam(description = "Id do simulado.") Long simuladoId,
            @McpToolParam(description = "Número da questão na prova.") Integer numero) {
        return examService.remove(simuladoId, numero);
    }

    @McpTool(name = "status_simulado",
            description = "Situação de um rascunho de simulado (questões por disciplina e quanto falta). Sem id, lista "
                    + "os rascunhos em andamento.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public List<ExamStatus> examStatus(
            @McpToolParam(description = "Id do simulado (vazio = todos os rascunhos).", required = false) Long simuladoId) {
        return simuladoId == null ? examService.drafts() : List.of(examService.status(simuladoId));
    }

    public record FinishedExam(Long blocoId, String nome, Integer tempoMinutos, int questoes, String link) {
    }

    @McpTool(name = "finalizar_simulado",
            description = "Fecha um ou mais rascunhos num bloco pronto para resolver no sistema, com o perfil de "
                    + "pontuação e a soma dos tempos (P1 + P2 = dia de prova). Recusa se faltar questão do edital, a "
                    + "menos que incompleto=true.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public FinishedExam finishExam(
            @McpToolParam(description = "Ids dos simulados, na ordem das provas.") List<Long> simulados,
            @McpToolParam(description = "Nome do bloco (vazio = título da prova ou \"Dia de prova\").", required = false)
            String nome,
            @McpToolParam(description = "true para finalizar mesmo faltando questões.", required = false) Boolean incompleto) {
        StudyBlock block = examService.finish(simulados, nome, Boolean.TRUE.equals(incompleto));
        int total = simulados.stream().mapToInt(id -> examService.status(id).questoes()).sum();
        return new FinishedExam(block.getId(), block.getName(), block.getTimeLimitMinutes(), total,
                baseUrl + "/blocos/" + block.getId());
    }
}
