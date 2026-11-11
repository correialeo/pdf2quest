package com.pdf2questao.aula;

import com.pdf2questao.aula.QuizQuestion.Alternative;
import com.pdf2questao.scoring.ScoringCalculator;
import com.pdf2questao.study.StudyTask;
import com.pdf2questao.study.StudyTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Aulas e quizzes. Uma aula pode ter varios quizzes; um quiz pode ser avulso (so disciplina/topico). Criar e pelo
 * MCP; responder e na tela. Todo quiz novo retoma os erros dos quizzes anteriores do mesmo assunto.
 */
@Service
public class LessonService {

    /** Resposta "em branco" (so no Certo/Errado): nao pontua e nao anula acerto. */
    public static final String BLANK = "-";

    public static final Set<String> LEVELS = Set.of("basico", "intermediario", "avancado", "prova");
    /** Nivel padrao: o usuario quer treinar sempre no nivel da prova. */
    public static final String PROVA = "prova";
    static final int MIN_STYLE_CHARS = 300;
    static final int MIN_STYLE_SOURCES = 2;

    private final LessonRepository lessonRepository;
    private final QuizRepository quizRepository;
    private final QuizQuestionRepository questionRepository;
    private final QuizAttemptRepository attemptRepository;
    private final StudyTaskRepository taskRepository;
    private final com.pdf2questao.concurso.Workspace workspace;
    private final com.pdf2questao.concurso.ConcursoService concursoService;
    private final LessonBlockValidator blockValidator;
    private final SourceExcerptRepository excerptRepository;
    private final com.pdf2questao.flashcard.FlashcardRepository flashcardRepository;
    private final com.pdf2questao.flashcard.FlashcardRatingRepository flashcardRatingRepository;

    public LessonService(LessonRepository lessonRepository, QuizRepository quizRepository,
                         QuizQuestionRepository questionRepository, QuizAttemptRepository attemptRepository,
                         StudyTaskRepository taskRepository, com.pdf2questao.concurso.Workspace workspace,
                         com.pdf2questao.concurso.ConcursoService concursoService,
                         LessonBlockValidator blockValidator, SourceExcerptRepository excerptRepository,
                         com.pdf2questao.flashcard.FlashcardRepository flashcardRepository,
                         com.pdf2questao.flashcard.FlashcardRatingRepository flashcardRatingRepository) {
        this.flashcardRepository = flashcardRepository;
        this.flashcardRatingRepository = flashcardRatingRepository;
        this.blockValidator = blockValidator;
        this.excerptRepository = excerptRepository;
        this.workspace = workspace;
        this.concursoService = concursoService;
        this.taskRepository = taskRepository;
        this.lessonRepository = lessonRepository;
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.attemptRepository = attemptRepository;
    }

    // ---- aulas ----

    public List<Lesson> all() {
        return lessonRepository.findAllByOrderByCreatedAtDesc();
    }

    /** Aulas do concurso em vigor (em "Todos", todas). */
    public List<Lesson> visible() {
        return all().stream().filter(l -> workspace.matches(l.getConcursoId())).toList();
    }

    /** Quizzes do concurso em vigor (em "Todos", todos). */
    public List<Quiz> visibleQuizzes() {
        return allQuizzes().stream().filter(q -> workspace.matches(q.getConcursoId())).toList();
    }

    /** Concurso de algo novo ligado a tarefa: o do cronograma da tarefa ou, sem ele, o em vigor. */
    private Long concursoFor(Long taskId, Long fallback) {
        Long fromTask = concursoService.concursoOfTask(taskId);
        return fromTask != null ? fromTask : fallback != null ? fallback : workspace.currentId();
    }

    public Lesson find(Long id) {
        return lessonRepository.findById(id).orElseThrow();
    }

    @Transactional
    public Lesson create(String title, String subject, String editalTopic, String content, Long taskId) {
        if (title == null || title.isBlank() || content == null || content.isBlank()) {
            throw new IllegalArgumentException("A aula precisa de título e conteúdo.");
        }
        Lesson lesson = new Lesson();
        lesson.setTitle(title.strip());
        lesson.setSubject(canonicalSubject(subject));
        lesson.setEditalTopic(blank(editalTopic));
        lesson.setContent(content.strip());
        lesson.setTaskId(checkTask(taskId));
        lesson.setConcursoId(concursoFor(taskId, null));
        return lessonRepository.save(lesson);
    }

    /** Minimo de uma aula nova: slides de conteudo (fora o de fontes) e questoes de fixacao no meio deles. */
    public static final int MIN_SLIDES = 2;
    public static final int MIN_FIXATION = 3;

    /**
     * Toda aula nova (ou reescrita) e em slides, com questoes de fixacao no meio: a de pagina unica virava um texto
     * longo lido sem pratica. Validado antes de gravar, para nao sobrar aula sem as questoes.
     */
    public static void requireSlides(String content, int multipleChoice) {
        int questions = multipleChoice + (int) LessonSlides.parseAll(content).blocks().stream()
                .filter(LessonBlocks.Block::graded).count();
        long slides = LessonSlides.parse(content).stream().filter(s -> !s.title().equalsIgnoreCase("Fontes")).count();
        if (slides < MIN_SLIDES || questions < MIN_FIXATION) {
            throw new IllegalArgumentException("A aula precisa ser em slides: ao menos " + MIN_SLIDES + " slides "
                    + "(linhas \"=== Título\", um por assunto do tópico) e ao menos " + MIN_FIXATION + " questões de "
                    + "fixação (perguntasDaAula citadas como [[questão N]] logo depois do que cada uma fixa, "
                    + "exercícios ```calculo ou tarefas ```tarefa) "
                    + "(nível prova, com estiloDaBanca e referencias). Recebido: " + slides + " slides e " + questions
                    + " questões. Se a ferramenta não tem o campo perguntasDaAula, reconecte o servidor MCP (/mcp).");
        }
        List<String> untagged = LessonSlides.parse(content).stream()
                .filter(s -> !s.title().equalsIgnoreCase("Fontes") && s.concept() == null).map(LessonSlides.Slide::title)
                .toList();
        if (!untagged.isEmpty()) {
            throw new IllegalArgumentException("Todo slide declara o conceito que ensina, no fim do título: "
                    + "\"=== Título {#id-do-conceito}\" (minúsculas, números e hífen; caixas também aceitam "
                    + "\":::regra Título {#id}\"). Sem conceito: " + untagged.stream().map(t -> "\"" + t + "\"")
                    .collect(Collectors.joining(", ")) + ". As perguntas dizem em 'conceitos' qual cobram.");
        }
        LessonSlides.concepts(content);   // recusa id repetido
    }

    /** Cria a aula e as questoes de fixacao juntas (falhando uma, nenhuma fica gravada). */
    @Transactional
    public Lesson createWithFixation(String title, String subject, String editalTopic, String content, Long taskId,
                                     List<NewQuestion> questions, String format, String styleNotes, String styleSources) {
        requireSlides(content, questions == null ? 0 : questions.size());
        blockValidator.validate(content);
        Lesson lesson = create(title, subject, editalTopic, content, taskId);
        replaceFixation(lesson.getId(), questions, format, styleNotes, styleSources, null);
        return lesson;
    }

    /** Reescreve a aula e troca as questoes de fixacao juntas. */
    @Transactional
    public Lesson rewriteWithFixation(Long lessonId, String title, String content, List<NewQuestion> questions,
                                      String format, String styleNotes, String styleSources, List<Long> reviewedErrors) {
        requireSlides(content, questions == null ? 0 : questions.size());
        blockValidator.validate(content);
        Lesson lesson = rewrite(lessonId, title, content);
        replaceFixation(lessonId, questions, format, styleNotes, styleSources, reviewedErrors);
        return lesson;
    }

    /** Troca o conteudo da aula (titulo vazio = mantem). Os quizzes dela continuam. */
    @Transactional
    public Lesson rewrite(Long lessonId, String title, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("A aula precisa de conteúdo.");
        }
        Lesson lesson = find(lessonId);
        if (blank(title) != null) {
            lesson.setTitle(title.strip());
        }
        lesson.setContent(content.strip());
        return lessonRepository.save(lesson);
    }

    /** Quiz embutido com as questoes de fixacao da aula em slides (nulo = a aula nao tem). */
    public Quiz fixation(Long lessonId) {
        return quizzes(lessonId).stream().filter(Quiz::isEmbedded).reduce((a, b) -> b).orElse(null);
    }

    /**
     * Grava as questoes de fixacao da aula em slides, no nivel prova, substituindo as anteriores (e as respostas
     * delas). Cada "[[questao N]]" do conteudo precisa de uma questao, e toda questao precisa aparecer num slide.
     */
    @Transactional
    public Quiz replaceFixation(Long lessonId, List<NewQuestion> questions, String format, String styleNotes,
                                String styleSources, List<Long> reviewedErrors) {
        Lesson lesson = find(lessonId);
        List<Integer> refs = LessonSlides.questionRefs(lesson.getContent());
        int n = questions == null ? 0 : questions.size();
        List<Integer> outOfRange = refs.stream().filter(r -> r < 1 || r > n).distinct().toList();
        if (!outOfRange.isEmpty()) {
            throw new IllegalArgumentException("A aula cita [[questão " + outOfRange.get(0) + "]], mas há " + n
                    + " questões de fixação.");
        }
        List<Integer> missing = java.util.stream.IntStream.rangeClosed(1, n).filter(i -> !refs.contains(i)).boxed().toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("As questões de fixação " + missing + " não aparecem na aula: ponha "
                    + "[[questão N]] no slide em que cada uma deve ser respondida.");
        }
        // Os erros da fixacao antiga somem com ela: exige que a nova os retome antes de apagar.
        List<PreviousError> errors = previousErrors(lessonId, null, null, null);
        List<Long> reviewed = reviewedErrors == null ? List.of() : reviewedErrors;
        if (!errors.isEmpty() && errors.stream().noneMatch(e -> reviewed.contains(e.perguntaId()))) {
            throw new IllegalArgumentException("Há " + errors.size() + " erros em quizzes anteriores desta aula "
                    + "(perguntas " + errors.stream().limit(10).map(e -> "#" + e.perguntaId())
                    .collect(Collectors.joining(", ")) + "). Leia com erros_quiz_anteriores, crie questões de "
                    + "fixação que retomem esses pontos e informe os ids em errosRetomados.");
        }
        Quiz old = fixation(lessonId);
        if (old != null) {
            deleteQuiz(old.getId());
        }
        Quiz quiz = createQuiz(lessonId, "Fixação da aula", null, null, PROVA, null, reviewedErrors, questions, null,
                format, styleNotes, styleSources, true);
        addExerciseRows(quiz, lesson);
        return quiz;
    }

    /**
     * Exercicios guiados e tarefas de codigo da aula viram itens da fixacao (sem alternativas): o resultado de cada
     * tentativa entra no desempenho por topico como uma pergunta, com as dicas usadas.
     */
    private void addExerciseRows(Quiz quiz, Lesson lesson) {
        LessonSlides.Parsed parsed = LessonSlides.parseAll(lesson.getContent());
        int position = questions(quiz.getId()).size();
        for (LessonBlocks.Block b : parsed.blocks()) {
            if (!b.graded()) {
                continue;
            }
            LessonSlides.Slide slide = parsed.slides().get(b.slide() - 1);
            QuizQuestion q = new QuizQuestion();
            q.setQuizId(quiz.getId());
            q.setLessonId(lesson.getId());
            q.setPosition(++position);
            q.setSubject(quiz.getSubject());
            q.setTopic(quiz.getTopic());
            q.setAlternativeList(List.of());
            q.setCorrectAnswer(EXERCISE_OK);
            q.setBlockIndex(b.index());
            String concept = slide.concept();
            if (b.kind() == LessonBlocks.Kind.CALCULO) {
                LessonBlocks.Calc c = LessonBlocks.calc(b.body());
                q.setKind("calculo");
                q.setStatement("Exercício guiado (slide " + b.slide() + "): " + (c.titulo() != null ? c.titulo() + " — " : "")
                        + clip(c.enunciado(), 400));
                concept = c.conceito() != null ? conceptId(c.conceito()) : concept;
            } else {
                q.setKind("codigo");
                q.setStatement("Tarefa de código " + b.langLabel() + " (slide " + b.slide() + "): "
                        + clip(LessonBlocks.task(b.body()).enunciado(), 400));
            }
            q.setConcepts(concept);
            questionRepository.save(q);
        }
    }

    /** Resposta gravada para um exercicio guiado/tarefa: concluiu sozinho (OK) ou nao (X). */
    public static final String EXERCISE_OK = "OK";

    public record ExerciseResult(boolean correct, int hints, boolean finished) {
    }

    /**
     * Resultado de uma tentativa de exercicio guiado ou tarefa de codigo da aula. Acerto = concluiu sem ver a
     * resolucao; as dicas ficam registradas mesmo quando acertou.
     */
    @Transactional
    public ExerciseResult recordExercise(Long lessonId, int blockIndex, boolean correct, int hints) {
        Quiz fixation = fixation(lessonId);
        QuizQuestion q = fixation == null ? null : questions(fixation.getId()).stream()
                .filter(x -> Integer.valueOf(blockIndex).equals(x.getBlockIndex())).findFirst().orElse(null);
        if (q == null) {
            throw new IllegalArgumentException("A aula " + lessonId + " não tem o exercício " + blockIndex + ".");
        }
        AnswerResult r = answer(q.getId(), correct ? EXERCISE_OK : "X");
        QuizAttempt last = latest(fixation.getId()).get(q.getId());
        last.setHintsUsed(Math.max(0, hints));
        attemptRepository.save(last);
        return new ExerciseResult(r.correct(), last.getHintsUsed(), r.finished());
    }

    /** Ultima tentativa de cada exercicio da aula: indice do bloco -> [acertou, dicas]. */
    public Map<Integer, Map<String, Object>> exerciseStatus(Long lessonId) {
        Map<Integer, Map<String, Object>> out = new LinkedHashMap<>();
        Quiz fixation = fixation(lessonId);
        if (fixation == null) {
            return out;
        }
        Map<Long, QuizAttempt> latest = latest(fixation.getId());
        for (QuizQuestion q : questions(fixation.getId())) {
            QuizAttempt a = latest.get(q.getId());
            if (q.getBlockIndex() != null && a != null) {
                out.put(q.getBlockIndex(), Map.of("acertou", Boolean.TRUE.equals(a.getCorrect()), "dicas", a.getHintsUsed()));
            }
        }
        return out;
    }

    // ---- trechos literais das fontes ----

    static final int MIN_EXCERPT_CHARS = 80;

    /**
     * Grava o texto literal de um dispositivo/trecho de fonte. O mesmo texto (hash) nao duplica: devolve o ja gravado.
     */
    @Transactional
    public SourceExcerpt registerExcerpt(String url, String dispositivo, String texto, String comoObtido) {
        if (blank(url) == null || !url.strip().matches("https?://\\S+")) {
            throw new IllegalArgumentException("Informe a 'url' (https://...) de onde o texto foi baixado.");
        }
        if (blank(dispositivo) == null) {
            throw new IllegalArgumentException("Informe o 'dispositivo' (ex.: \"LRF, art. 42\" ou \"PySpark: "
                    + "DataFrame.persist\").");
        }
        if (texto == null || texto.strip().length() < MIN_EXCERPT_CHARS) {
            throw new IllegalArgumentException("'texto' precisa do trecho LITERAL da fonte (mínimo " + MIN_EXCERPT_CHARS
                    + " caracteres), copiado do arquivo baixado, não um resumo.");
        }
        if (blank(comoObtido) == null || comoObtido.matches("(?is).*(webfetch|resum|mem[óo]ria|parafrase).*")) {
            throw new IllegalArgumentException("'comoObtido' diz como o texto literal foi extraído (ex.: \"curl do "
                    + "HTML do Planalto + recorte do art. 42\", \"pdftotext do PDF oficial\"). Resumo de ferramenta de "
                    + "leitura (WebFetch) ou memória não servem: baixe o arquivo e extraia o texto.");
        }
        if (NormCitations.hasArticle(texto) && NormCitations.first(dispositivo) == null) {
            throw new IllegalArgumentException("O trecho tem texto de artigo: diga a norma no 'dispositivo' (ex.: \"LRF, "
                    + "art. 42\", \"Lei 4.320, art. 36\", \"CF, art. 165\"), para a aula não confundir artigos de "
                    + "mesmo número em leis diferentes.");
        }
        String sha = sha256(texto.strip());
        List<SourceExcerpt> same = excerptRepository.findBySha256(sha);
        if (!same.isEmpty()) {
            return same.get(0);
        }
        SourceExcerpt e = new SourceExcerpt();
        e.setConcursoId(workspace.currentId());
        e.setUrl(url.strip());
        e.setDispositivo(dispositivo.strip());
        e.setTexto(texto.strip());
        e.setComoObtido(comoObtido.strip());
        e.setSha256(sha);
        return excerptRepository.save(e);
    }

    public List<SourceExcerpt> excerpts(List<Long> ids) {
        List<SourceExcerpt> out = new ArrayList<>();
        for (Long id : ids == null ? List.<Long>of() : ids) {
            out.add(excerptRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Trecho " + id
                    + " não existe: grave com registrar_trecho.")));
        }
        return out;
    }

    public List<SourceExcerpt> lessonExcerpts(Long lessonId) {
        return excerptRepository.findByLessonIdOrderById(lessonId);
    }

    @Transactional
    public void linkExcerpts(Long lessonId, List<Long> ids) {
        for (SourceExcerpt e : excerpts(ids)) {
            e.setLessonId(lessonId);
            excerptRepository.save(e);
        }
    }

    /**
     * Artigos citados no conteudo sem trecho literal da MESMA norma que contenha o texto deles (nem mencao em
     * naoConfirmado). A norma vem da citacao ("art. 42 da LRF"); sem ela, do titulo do slide; sem ele, da unica norma
     * dos trechos enviados. Se nem assim da para saber, a citacao e devolvida pedindo a norma.
     */
    public static List<String> articlesWithoutExcerpt(String content, List<SourceExcerpt> excerpts, String unconfirmed) {
        java.util.Set<String> covered = new java.util.HashSet<>();
        java.util.Set<String> norms = new java.util.LinkedHashSet<>();
        for (SourceExcerpt e : excerpts) {
            covered.addAll(NormCitations.proven(e));
            NormCitations.Norm n = NormCitations.first(e.getDispositivo());
            if (n != null) {
                norms.add(n.key() + "|" + n.label());
            }
        }
        NormCitations.Norm only = norms.size() == 1 ? new NormCitations.Norm(norms.iterator().next().split("\\|")[0],
                norms.iterator().next().split("\\|", 2)[1]) : null;
        java.util.Set<String> admitted = new java.util.HashSet<>();   // naoConfirmado: com norma, ou so o numero
        for (NormCitations.Citation c : NormCitations.cited(unconfirmed, null)) {
            admitted.add(c.norm() == null ? "*:" + c.article() : c.key());
        }
        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        List<LessonSlides.Slide> slides = LessonSlides.parse(content);
        List<String[]> parts = new ArrayList<>();   // [titulo, texto]
        if (slides.isEmpty()) {
            parts.add(new String[]{"", content});
        } else {
            slides.forEach(sl -> parts.add(new String[]{sl.title(), sl.source()}));
        }
        for (String[] part : parts) {
            NormCitations.Norm fromTitle = NormCitations.first(part[0]);
            for (NormCitations.Citation c : NormCitations.cited(part[1], fromTitle != null ? fromTitle : only)) {
                if (admitted.contains("*:" + c.article()) || admitted.contains(c.key())) {
                    continue;
                }
                if (c.norm() == null) {
                    missing.add("art. " + c.article() + " (de que norma? escreva \"art. " + c.article()
                            + " da LRF\", \"da Lei 4.320\", \"da CF\"...)");
                } else if (!covered.contains(c.key())) {
                    missing.add(c.label());
                }
            }
        }
        return List.copyOf(missing);
    }

    private static String sha256(String text) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- ligacao com o cronograma ----

    /** Aula ou quiz ligado a uma tarefa, com o andamento para mostrar na tarefa. */
    public record Linked(String tipo, Long id, String titulo, String andamento, boolean concluido, String link) {
    }

    /** Aulas e quizzes de cada tarefa. O quiz sem tarefa propria herda a da aula (aparece dentro dela). */
    public Map<Long, List<Linked>> forTasks(Collection<Long> taskIds) {
        Map<Long, List<Linked>> out = new LinkedHashMap<>();
        if (taskIds == null || taskIds.isEmpty()) {
            return out;
        }
        for (Lesson lesson : lessonRepository.findByTaskIdInOrderByCreatedAt(taskIds)) {
            List<Quiz> qs = quizzes(lesson.getId());
            long answered = qs.stream().filter(q -> q.getFinishedAt() != null).count();
            boolean done = !qs.isEmpty() && answered == qs.size();
            String progress = qs.isEmpty() ? "sem quiz" : answered + " de " + qs.size() + " quizzes respondidos";
            out.computeIfAbsent(lesson.getTaskId(), k -> new ArrayList<>())
                    .add(new Linked("Aula", lesson.getId(), lesson.getTitle(), progress, done, "/aulas/" + lesson.getId()));
        }
        for (Quiz quiz : quizRepository.findByTaskIdInOrderByCreatedAt(taskIds)) {
            Long lessonTask = quiz.getLessonId() == null ? null : find(quiz.getLessonId()).getTaskId();
            if (quiz.getTaskId().equals(lessonTask)) {
                continue;   // ja aparece pela aula
            }
            QuizResult r = result(quiz.getId());
            String progress = r.respondidas() == 0 ? "a responder"
                    : r.acertos() + "/" + r.respondidas() + " acertos" + (r.concluido() ? "" : " (em andamento)");
            out.computeIfAbsent(quiz.getTaskId(), k -> new ArrayList<>())
                    .add(new Linked("Quiz", quiz.getId(), quiz.getTitle(), progress, r.concluido(), "/quizzes/" + quiz.getId()));
        }
        return out;
    }

    public List<Linked> forTask(Long taskId) {
        return forTasks(List.of(taskId)).getOrDefault(taskId, List.of());
    }

    /** Tarefa de uma aula ou quiz (a do quiz ou, sem ela, a da aula), para o link de volta. */
    public StudyTask task(Long taskId) {
        return taskId == null ? null : taskRepository.findById(taskId).orElse(null);
    }

    public Long effectiveTaskId(Quiz quiz) {
        return quiz.getTaskId() != null || quiz.getLessonId() == null ? quiz.getTaskId()
                : find(quiz.getLessonId()).getTaskId();
    }

    /** Liga (ou desliga, com tarefa null) uma aula e/ou um quiz a uma tarefa do cronograma. */
    @Transactional
    public void linkTask(Long taskId, Long lessonId, Long quizId) {
        if (lessonId == null && quizId == null) {
            throw new IllegalArgumentException("Informe a aula e/ou o quiz.");
        }
        Long task = checkTask(taskId);
        if (lessonId != null) {
            Lesson lesson = lessonRepository.findById(lessonId)
                    .orElseThrow(() -> new IllegalArgumentException("Aula " + lessonId + " não existe."));
            lesson.setTaskId(task);
            Long concurso = concursoService.concursoOfTask(task);
            if (concurso != null) {
                lesson.setConcursoId(concurso);
            }
            lessonRepository.save(lesson);
        }
        if (quizId != null) {
            Quiz quiz = quiz(quizId);
            quiz.setTaskId(task);
            Long concurso = concursoService.concursoOfTask(task);
            if (concurso != null) {
                quiz.setConcursoId(concurso);
            }
            quizRepository.save(quiz);
        }
    }

    private Long checkTask(Long taskId) {
        if (taskId != null && !taskRepository.existsById(taskId)) {
            throw new IllegalArgumentException("Tarefa " + taskId + " não existe.");
        }
        return taskId;
    }

    public List<Quiz> quizzes(Long lessonId) {
        return quizRepository.findByLessonIdOrderByCreatedAt(lessonId);
    }

    /** Aula concluida = tem quiz e todos os quizzes foram respondidos. */
    public boolean lessonDone(Long lessonId) {
        List<Quiz> qs = quizzes(lessonId);
        return !qs.isEmpty() && qs.stream().allMatch(q -> q.getFinishedAt() != null);
    }

    // ---- quizzes ----

    public List<Quiz> allQuizzes() {
        return quizRepository.findAllByOrderByCreatedAtDesc();
    }

    public Quiz quiz(Long id) {
        return quizRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Quiz " + id + " não existe."));
    }

    public List<QuizQuestion> questions(Long quizId) {
        return questionRepository.findByQuizIdOrderByPosition(quizId);
    }

    public record PreviousError(Long perguntaId, Long quizId, String quiz, String topico, String enunciado,
                                String suaResposta, String explicacaoDaSuaResposta, String correta,
                                String explicacaoDaCorreta, LocalDateTime respondidaEm) {
    }

    /**
     * Erros (ultima resposta errada) dos quizzes do mesmo assunto: da aula, se informada; senao da disciplina e/ou
     * topico (parte do nome, sem acento). Os mais recentes primeiro. Um erro sai da lista quando um quiz posterior
     * que o retomou foi respondido e o usuario acertou todas as perguntas daquele topico.
     */
    public List<PreviousError> previousErrors(Long lessonId, String subject, String topic, Long exceptQuiz) {
        String s = subject == null || subject.isBlank() ? null : ScoringCalculator.normalize(subject);
        String t = topic == null || topic.isBlank() ? null : ScoringCalculator.normalize(topic);
        if (lessonId == null && s == null && t == null) {
            throw new IllegalArgumentException("Informe a aula, a disciplina ou o tópico.");
        }
        Map<Long, Quiz> quizzes = allQuizzes().stream().collect(Collectors.toMap(Quiz::getId, Function.identity()));
        List<QuizQuestion> candidates = questionRepository.findAll().stream()
                .filter(q -> exceptQuiz == null || !exceptQuiz.equals(q.getQuizId()))
                .filter(q -> lessonId != null ? lessonId.equals(q.getLessonId())
                        : (s == null || q.getSubject() != null && contains(q.getSubject(), s))
                        && (t == null || q.getTopic() != null && contains(q.getTopic(), t)))
                .toList();
        Map<Long, QuizAttempt> last = new LinkedHashMap<>();
        attemptRepository.findByQuizQuestionIdInOrderByAnsweredAt(candidates.stream().map(QuizQuestion::getId).toList())
                .forEach(a -> last.put(a.getQuizQuestionId(), a));
        List<PreviousError> out = new ArrayList<>();
        for (QuizQuestion q : candidates) {
            QuizAttempt a = last.get(q.getId());
            if (a == null || Boolean.TRUE.equals(a.getCorrect()) || resolved(q, quizzes.values())) {
                continue;
            }
            Map<String, Alternative> alt = q.alternativeList().stream()
                    .collect(Collectors.toMap(Alternative::letra, Function.identity()));
            Quiz quiz = quizzes.get(q.getQuizId());
            out.add(new PreviousError(q.getId(), q.getQuizId(), quiz == null ? null : quiz.getTitle(), q.getTopic(),
                    q.getStatement(), shown(a.getSelectedAnswer()),
                    alt.containsKey(a.getSelectedAnswer()) ? alt.get(a.getSelectedAnswer()).explicacao()
                            : BLANK.equals(a.getSelectedAnswer()) ? "Deixado em branco: não pontua, mas indica lacuna." : null,
                    q.getCorrectAnswer(), alt.containsKey(q.getCorrectAnswer()) ? alt.get(q.getCorrectAnswer()).explicacao() : null,
                    a.getAnsweredAt()));
        }
        out.sort(Comparator.comparing(PreviousError::respondidaEm).reversed());
        return out;
    }

    /** Erro em aberto para a caixa de erros: a pergunta inteira, para responder de novo na tela. */
    public record OpenError(QuizQuestion pergunta, String quiz, String disciplina, String suaResposta,
                            LocalDateTime respondidaEm) {
    }

    /**
     * Caixa de erros: perguntas de quiz cuja ultima resposta foi errada (e que nenhum quiz posterior resolveu), de
     * todas as disciplinas ou de uma. Responder certo de novo tira a pergunta da caixa.
     */
    public List<OpenError> openErrors(String subject) {
        String s = blank(subject) == null ? null : ScoringCalculator.normalize(subject);
        Map<Long, Quiz> quizzes = allQuizzes().stream().collect(Collectors.toMap(Quiz::getId, Function.identity()));
        List<QuizQuestion> candidates = questionRepository.findAll().stream()
                .filter(q -> s == null || ScoringCalculator.normalize(subjectName(q.getSubject())).equals(s))
                .filter(q -> workspace.matches(quizzes.containsKey(q.getQuizId())
                        ? quizzes.get(q.getQuizId()).getConcursoId() : null))
                .toList();
        Map<Long, QuizAttempt> last = new LinkedHashMap<>();
        attemptRepository.findByQuizQuestionIdInOrderByAnsweredAt(candidates.stream().map(QuizQuestion::getId).toList())
                .forEach(a -> last.put(a.getQuizQuestionId(), a));
        List<OpenError> out = new ArrayList<>();
        for (QuizQuestion q : candidates) {
            QuizAttempt a = last.get(q.getId());
            if (a == null || Boolean.TRUE.equals(a.getCorrect()) || resolved(q, quizzes.values())) {
                continue;
            }
            Quiz quiz = quizzes.get(q.getQuizId());
            out.add(new OpenError(q, quiz == null ? null : quiz.getTitle(), subjectName(q.getSubject()),
                    shown(a.getSelectedAnswer()), a.getAnsweredAt()));
        }
        out.sort(Comparator.comparing(OpenError::respondidaEm).reversed());
        return out;
    }

    /** Erros em aberto por disciplina (mais erros primeiro), para os atalhos da caixa de erros. */
    public Map<String, Long> openErrorsBySubject() {
        Map<String, Long> out = openErrors(null).stream()
                .collect(Collectors.groupingBy(OpenError::disciplina, LinkedHashMap::new, Collectors.counting()));
        return out.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    // ---- volta dos erros: passo diario da tela Hoje ----

    /** Um erro so volta depois deste tanto de dias: responder de novo no mesmo dia mede memoria curta. */
    public static final int ERROR_RETURN_DAYS = 2;
    /** Quantos erros antigos entram por dia (o resto espera os proximos dias, os mais antigos primeiro). */
    public static final int ERRORS_PER_DAY = 20;

    /**
     * @param hoje       erros a responder hoje (os mais antigos primeiro, ate completar a cota do dia)
     * @param vencidos   todos os erros que ja podem voltar (inclui os de hoje)
     * @param feitosHoje erros antigos ja respondidos de novo hoje
     */
    public record ErrorReturn(List<OpenError> hoje, int vencidos, int feitosHoje) {
        public boolean pendente() {
            return !hoje.isEmpty();
        }

        public boolean concluido() {
            return hoje.isEmpty() && feitosHoje > 0;
        }
    }

    public ErrorReturn errorReturn(LocalDate today) {
        return errorReturn(openErrors(null), today);
    }

    /**
     * Erros de quiz que voltam hoje: ultima resposta errada ha {@link #ERROR_RETURN_DAYS} dias ou mais. Acertou, sai
     * da caixa; errou de novo, a data da resposta passa a ser hoje e a pergunta volta sozinha daqui a
     * {@link #ERROR_RETURN_DAYS} dias. Exercicio guiado e tarefa de codigo (sem alternativas) nao entram: sao
     * refeitos na aula.
     */
    public ErrorReturn errorReturn(List<OpenError> open, LocalDate today) {
        LocalDate limit = today.minusDays(ERROR_RETURN_DAYS);
        List<OpenError> due = open.stream()
                .filter(e -> !e.pergunta().alternativeList().isEmpty())
                .filter(e -> !e.respondidaEm().toLocalDate().isAfter(limit))
                .sorted(Comparator.comparing(OpenError::respondidaEm)).toList();
        int done = retriedToday(today, limit);
        return new ErrorReturn(due.stream().limit(Math.max(0, ERRORS_PER_DAY - done)).toList(), due.size(), done);
    }

    /** Perguntas respondidas hoje cuja resposta anterior era um erro que ja podia voltar. */
    private int retriedToday(LocalDate today, LocalDate limit) {
        Set<Long> visible = visibleQuizzes().stream().map(Quiz::getId).collect(Collectors.toSet());
        Set<Long> questions = questionRepository.findAll().stream().filter(q -> visible.contains(q.getQuizId()))
                .map(QuizQuestion::getId).collect(Collectors.toSet());
        Map<Long, QuizAttempt> before = new LinkedHashMap<>();   // ultima resposta antes de hoje
        Set<Long> answeredToday = new java.util.HashSet<>();
        attemptRepository.findAll().stream()
                .filter(a -> a.getAnsweredAt() != null && questions.contains(a.getQuizQuestionId()))
                .sorted(Comparator.comparing(QuizAttempt::getAnsweredAt).thenComparing(QuizAttempt::getId))
                .forEach(a -> {
                    if (a.getAnsweredAt().toLocalDate().isBefore(today)) {
                        before.put(a.getQuizQuestionId(), a);
                    } else {
                        answeredToday.add(a.getQuizQuestionId());
                    }
                });
        return (int) answeredToday.stream().map(before::get).filter(Objects::nonNull)
                .filter(a -> !Boolean.TRUE.equals(a.getCorrect()) && !a.getAnsweredAt().toLocalDate().isAfter(limit))
                .count();
    }

    /** O que mostrar da resposta dada: a letra ou "em branco". */
    private static String shown(String selected) {
        return BLANK.equals(selected) ? "em branco" : selected;
    }

    private static String subjectName(String subject) {
        return blank(subject) == null ? "Sem disciplina" : subject.strip();
    }

    /** Retomado por um quiz posterior, ja respondido, em que todas as perguntas do mesmo topico foram acertadas. */
    private boolean resolved(QuizQuestion error, java.util.Collection<Quiz> quizzes) {
        for (Quiz later : quizzes) {
            if (later.getId().equals(error.getQuizId()) || later.getPreviousErrors() == null
                    || later.getPreviousErrors().lines().noneMatch(l -> l.startsWith("#" + error.getId() + " "))) {
                continue;
            }
            Map<Long, QuizAttempt> answers = latest(later.getId());
            List<QuizQuestion> sameTopic = questions(later.getId()).stream()
                    .filter(q -> error.getTopic() == null || error.getTopic().equals(q.getTopic())).toList();
            List<QuizQuestion> checked = sameTopic.isEmpty() ? questions(later.getId()) : sameTopic;
            if (!checked.isEmpty() && checked.stream().allMatch(q -> answers.containsKey(q.getId())
                    && Boolean.TRUE.equals(answers.get(q.getId()).getCorrect()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String value, String normalizedWanted) {
        String v = ScoringCalculator.normalize(value);
        return v.contains(normalizedWanted) || normalizedWanted.contains(v);
    }

    /**
     * @param concepts     conceitos da aula que a pergunta cobra ({#id} dos slides/caixas)
     * @param beyondLesson pergunta que vai alem da aula de proposito
     */
    public record NewQuestion(String statement, String topic, List<String> texts, List<String> explanations,
                              String correct, List<String> concepts, boolean beyondLesson) {

        public NewQuestion(String statement, String topic, List<String> texts, List<String> explanations,
                           String correct) {
            this(statement, topic, texts, explanations, correct, null, false);
        }
    }

    /**
     * Cria um quiz (da aula ou avulso). Se ha erros em quizzes anteriores do mesmo assunto, exige que o quiz diga
     * quais retoma ({@code reviewedErrors}, ids das perguntas erradas): nenhum quiz novo ignora os erros anteriores.
     */
    @Transactional
    public Quiz createQuiz(Long lessonId, String title, String subject, String topic, String level, String doubts,
                           List<Long> reviewedErrors, List<NewQuestion> questions, Long taskId) {
        return createQuiz(lessonId, title, subject, topic, level, doubts, reviewedErrors, questions, taskId, null);
    }

    /** @param format null = multipla escolha; "certo_errado" = itens Certo/Errado (Cebraspe). */
    @Transactional
    public Quiz createQuiz(Long lessonId, String title, String subject, String topic, String level, String doubts,
                           List<Long> reviewedErrors, List<NewQuestion> questions, Long taskId, String format) {
        return createQuiz(lessonId, title, subject, topic, level, doubts, reviewedErrors, questions, taskId, format,
                null, null);
    }

    /**
     * No nivel prova (o padrao), o quiz exige o que o simulado exige: o estilo da banca observado em questoes reais
     * lidas e essas questoes em {@code styleSources}. Sem isso o quiz sai com cara de livro, nao de prova.
     */
    @Transactional
    public Quiz createQuiz(Long lessonId, String title, String subject, String topic, String level, String doubts,
                           List<Long> reviewedErrors, List<NewQuestion> questions, Long taskId, String format,
                           String styleNotes, String styleSources) {
        return createQuiz(lessonId, title, subject, topic, level, doubts, reviewedErrors, questions, taskId, format,
                styleNotes, styleSources, false);
    }

    private Quiz createQuiz(Long lessonId, String title, String subject, String topic, String level, String doubts,
                            List<Long> reviewedErrors, List<NewQuestion> questions, Long taskId, String format,
                            String styleNotes, String styleSources, boolean embedded) {
        String fmt = blank(format) == null ? null : ScoringCalculator.normalize(format).replace(' ', '_')
                .replace('/', '_').replace('-', '_');
        if (fmt != null && !fmt.equals("multipla_escolha") && !fmt.equals(Quiz.CERTO_ERRADO)) {
            throw new IllegalArgumentException("Formato: multipla_escolha ou certo_errado.");
        }
        Lesson lesson = lessonId == null ? null : find(lessonId);
        String subj = lesson != null && blank(subject) == null ? lesson.getSubject() : canonicalSubject(subject);
        String top = lesson != null && blank(topic) == null ? lesson.getEditalTopic() : blank(topic);
        if (lesson == null && subj == null && top == null) {
            throw new IllegalArgumentException("Quiz avulso precisa de disciplina ou tópico.");
        }
        String lvl = level == null || level.isBlank() ? PROVA : ScoringCalculator.normalize(level);
        if (!LEVELS.contains(lvl)) {
            throw new IllegalArgumentException("Nível: basico, intermediario, avancado ou prova.");
        }
        if (PROVA.equals(lvl)) {
            if (styleNotes == null || styleNotes.strip().length() < MIN_STYLE_CHARS) {
                throw new IllegalArgumentException("Quiz nível prova: pesquise antes como a banca cobra este tópico "
                        + "(questões reais na internet e com provas_da_banca/exemplos_da_banca) e descreva em "
                        + "'estiloDaBanca' o que observou: formato e tamanho dos enunciados, comandos (\"EXCETO\", "
                        + "I-II-III, caso concreto), distratores e pegadinhas recorrentes (mínimo " + MIN_STYLE_CHARS
                        + " caracteres).");
            }
            long sources = styleSources == null ? 0 : styleSources.lines().filter(l -> !l.isBlank()).count();
            if (sources < MIN_STYLE_SOURCES) {
                throw new IllegalArgumentException("Quiz nível prova: informe em 'referencias' ao menos "
                        + MIN_STYLE_SOURCES + " questões reais da banca que você LEU como modelo, uma por linha (link "
                        + "da questão ou \"questão local #id\").");
            }
        }
        List<PreviousError> errors = previousErrors(lessonId, lessonId == null ? subj : null,
                lessonId == null ? top : null, null);
        List<Long> reviewed = reviewedErrors == null ? List.of() : reviewedErrors;
        if (!errors.isEmpty() && errors.stream().noneMatch(e -> reviewed.contains(e.perguntaId()))) {
            throw new IllegalArgumentException("Há " + errors.size() + " erros em quizzes anteriores deste assunto "
                    + "(perguntas " + errors.stream().limit(10).map(e -> "#" + e.perguntaId())
                    .collect(Collectors.joining(", ")) + "). Leia com erros_quiz_anteriores, crie perguntas que "
                    + "retomem esses pontos e informe os ids em errosRetomados.");
        }
        Quiz quiz = new Quiz();
        quiz.setLessonId(lessonId);
        quiz.setTaskId(checkTask(taskId));
        quiz.setConcursoId(concursoFor(taskId != null ? taskId : lesson == null ? null : lesson.getTaskId(),
                lesson == null ? null : lesson.getConcursoId()));
        quiz.setSubject(subj);
        quiz.setTopic(top);
        quiz.setLevel(lvl);
        quiz.setFormat(Quiz.CERTO_ERRADO.equals(fmt) ? fmt : null);
        quiz.setDoubts(blank(doubts));
        quiz.setStyleNotes(blank(styleNotes));
        quiz.setStyleSources(blank(styleSources));
        quiz.setPreviousErrors(errors.stream().filter(e -> reviewed.contains(e.perguntaId()))
                .map(e -> "#" + e.perguntaId() + " " + (e.topico() == null ? "" : e.topico() + ": ")
                        + clip(e.enunciado(), 120))
                .collect(Collectors.joining("\n")));
        int number = lessonId == null ? 0 : quizzes(lessonId).size();
        quiz.setTitle(blank(title) != null ? title.strip()
                : (lesson != null ? "Quiz " + (number + 1) : "Quiz – " + (top != null ? top : subj)));
        quiz.setEmbedded(embedded);
        quiz = quizRepository.save(quiz);
        if (!embedded || questions != null && !questions.isEmpty()) {
            addQuestions(quiz.getId(), questions);   // fixacao so com exercicios guiados/tarefas nao tem pergunta aqui
        }
        return quiz;
    }

    /**
     * Pistas que deixam acertar sem saber o conteudo, num lote de 4 ou mais: a correta e a alternativa mais longa na
     * maioria das perguntas, ou o gabarito e sempre a mesma letra (no Certo/Errado, todos C ou todos E).
     */
    private void rejectGiveaways(Quiz quiz, List<NewQuestion> questions) {
        if (questions.size() < 4) {
            return;
        }
        List<String> answers = questions.stream().map(n -> answerLetter(quiz, n.correct())).toList();
        if (answers.stream().distinct().count() == 1) {
            throw new IllegalArgumentException("Nível prova: o gabarito é \"" + answers.get(0) + "\" em todas as "
                    + questions.size() + " perguntas. Distribua as respostas como numa prova real.");
        }
        if (quiz.certoErrado()) {
            return;
        }
        List<Integer> longest = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            NewQuestion n = questions.get(i);
            if (n.texts() == null || n.texts().size() < 2 || answers.get(i).isEmpty()) {
                continue;
            }
            int correct = answers.get(i).charAt(0) - 'A';
            if (correct < 0 || correct >= n.texts().size()) {
                continue;
            }
            int size = n.texts().get(correct) == null ? 0 : n.texts().get(correct).strip().length();
            boolean strictlyLongest = true;
            for (int a = 0; a < n.texts().size(); a++) {
                strictlyLongest &= a == correct || n.texts().get(a) == null || n.texts().get(a).strip().length() < size;
            }
            if (strictlyLongest) {
                longest.add(i + 1);
            }
        }
        if (longest.size() * 2 > questions.size()) {
            throw new IllegalArgumentException("Nível prova: a correta é a alternativa mais longa nas perguntas "
                    + longest.stream().map(String::valueOf).collect(Collectors.joining(", ")) + ". Dá para acertar "
                    + "sem saber o conteúdo: deixe as alternativas do mesmo tamanho e estrutura (distratores com o "
                    + "mesmo nível de detalhe).");
        }
    }

    /** "{#rp-x}", "#rp-x" ou "rp-x" viram "rp-x". */
    static String conceptId(String raw) {
        return raw == null ? "" : raw.strip().replaceAll("^\\{?#?|}$", "").strip();
    }

    /**
     * Quiz de aula com conceitos declarados: cada pergunta diz o que cobra, e o conceito tem de estar na aula (o
     * quiz nao cobra o que nao foi ensinado), salvo ampliacao=true, que fica marcada como "alem da aula". E a
     * questao nao reaproveita os dados do exemplo do slide desse conceito (nem do slide em que a fixacao aparece):
     * com os mesmos numeros, o acerto mede reconhecimento, nao raciocinio. Aula antiga, sem conceitos, nao e checada.
     */
    private void checkConcepts(Quiz quiz, Lesson lesson, List<NewQuestion> questions) {
        Map<String, LessonSlides.Concept> concepts = LessonSlides.concepts(lesson.getContent());
        if (concepts.isEmpty()) {
            return;
        }
        List<LessonSlides.Slide> slides = LessonSlides.parse(lesson.getContent());
        Map<Integer, Integer> placed = quiz.isEmbedded() ? LessonSlides.questionSlides(lesson.getContent()) : Map.of();
        int offset = questions(quiz.getId()).size();
        String available = concepts.values().stream().map(c -> c.id() + " (slide " + c.slide() + ": " + c.slideTitle() + ")")
                .collect(Collectors.joining(", "));
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            NewQuestion n = questions.get(i);
            String label = "Pergunta " + (i + 1);
            List<String> ids = n.concepts() == null ? List.of()
                    : n.concepts().stream().map(LessonService::conceptId).filter(c -> !c.isEmpty()).toList();
            if (n.beyondLesson()) {
                if (quiz.isEmbedded()) {
                    problems.add(label + ": questão de fixação não pode ser ampliacao; ela fixa o que o slide acabou "
                            + "de ensinar");
                }
                continue;
            }
            if (ids.isEmpty()) {
                problems.add(label + ": informe em 'conceitos' o que ela cobra (um dos ids da aula) ou marque "
                        + "ampliacao=true se for além da aula de propósito");
                continue;
            }
            List<String> unknown = ids.stream().filter(id -> !concepts.containsKey(id)).toList();
            if (!unknown.isEmpty()) {
                problems.add(label + " cobra " + unknown.stream().map(u -> "{#" + u + "}").collect(Collectors.joining(", "))
                        + ", que não está em nenhum slide da aula: falta um slide ou caixa que ensine isso (inclua "
                        + "com reescrever_aula) ou marque ampliacao=true");
                continue;
            }
            StringBuilder example = new StringBuilder();
            ids.forEach(id -> example.append(concepts.get(id).source()).append('\n'));
            Integer at = placed.get(offset + i + 1);
            if (at != null && at >= 1 && at <= slides.size()) {
                example.append(slides.get(at - 1).source());
            }
            String asked = n.statement() + "\n" + (n.texts() == null ? "" : String.join("\n",
                    n.texts().stream().map(t -> t == null ? "" : t).toList()));
            java.util.Set<java.math.BigDecimal> shared = DataOverlap.shared(example.toString(), asked);
            if (shared.size() > DataOverlap.MAX_SHARED) {
                LessonSlides.Concept c = concepts.get(ids.get(0));
                problems.add(label + " repete os valores " + shared.stream().map(DataOverlap::show)
                        .collect(Collectors.joining(", ")) + " do exemplo do slide " + c.slide() + " (\"" + c.slideTitle()
                        + "\"): troque os números, os nomes e a estrutura dos dados, para o aluno aplicar a regra a um "
                        + "caso novo em vez de reconhecer o exemplo");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems.stream().limit(6).toList())
                    + (problems.size() > 6 ? "; ..." : "") + ". Conceitos da aula: " + available + ".");
        }
    }

    /**
     * @param respondidas perguntas do conceito com resposta (ultima tentativa; branco nao conta)
     * @param acertos     dessas, quantas certas
     * @param percentual  acerto (nulo = nada respondido)
     */
    public record ConceptCoverage(String conceito, int slide, String titulo, int perguntas, int respondidas,
                                  int acertos, Double percentual) {

        /** Precisa reler: sem pergunta, ou respondido com menos de 70% de acerto. */
        public boolean fraco() {
            return perguntas == 0 || percentual != null && percentual < 70;
        }
    }

    public record QuestionRef(Long quizId, String quiz, int numero, String enunciado, List<String> conceitos) {
    }

    /**
     * Cobertura de uma aula pelos quizzes dela (fixacao incluida): conceitos sem pergunta, perguntas sem conceito
     * (quizzes antigos) e perguntas alem da aula.
     */
    public record LessonCoverage(Long aulaId, String titulo, List<ConceptCoverage> conceitos,
                                 List<ConceptCoverage> semPergunta, List<QuestionRef> perguntasSemConceito,
                                 List<QuestionRef> alemDaAula) {
    }

    public LessonCoverage coverage(Long lessonId) {
        Lesson lesson = find(lessonId);
        Map<String, LessonSlides.Concept> concepts = LessonSlides.concepts(lesson.getContent());
        Map<String, Integer> count = new LinkedHashMap<>();
        Map<String, Integer> answered = new LinkedHashMap<>();
        Map<String, Integer> right = new LinkedHashMap<>();
        List<QuestionRef> untagged = new ArrayList<>();
        List<QuestionRef> beyond = new ArrayList<>();
        for (Quiz quiz : quizzes(lessonId)) {
            Map<Long, QuizAttempt> latest = latest(quiz.getId());
            for (QuizQuestion q : questions(quiz.getId())) {
                QuizAttempt a = latest.get(q.getId());
                if (a != null && a.getCorrect() != null) {
                    q.conceptList().forEach(c -> {
                        answered.merge(c, 1, Integer::sum);
                        right.merge(c, Boolean.TRUE.equals(a.getCorrect()) ? 1 : 0, Integer::sum);
                    });
                }
                QuestionRef ref = new QuestionRef(quiz.getId(), quiz.getTitle(), q.getPosition(),
                        clip(q.getStatement(), 160), q.conceptList());
                if (q.isBeyondLesson()) {
                    beyond.add(ref);
                } else if (q.conceptList().isEmpty()) {
                    untagged.add(ref);
                }
                q.conceptList().forEach(c -> count.merge(c, 1, Integer::sum));
            }
        }
        List<ConceptCoverage> all = concepts.values().stream()
                .map(c -> {
                    int n = answered.getOrDefault(c.id(), 0);
                    int ok = right.getOrDefault(c.id(), 0);
                    return new ConceptCoverage(c.id(), c.slide(), c.slideTitle(), count.getOrDefault(c.id(), 0), n, ok,
                            n == 0 ? null : Math.round(ok * 1000.0 / n) / 10.0);
                }).toList();
        return new LessonCoverage(lessonId, lesson.getTitle(), all,
                all.stream().filter(c -> c.perguntas() == 0).toList(), untagged, beyond);
    }

    /** Slide de cada conceito da aula da pergunta, para o "Revise: slide X" (vazio se a aula nao tem conceitos). */
    public Map<Long, List<LessonSlides.Concept>> conceptsOf(Long lessonId, List<QuizQuestion> questions) {
        Map<Long, List<LessonSlides.Concept>> out = new LinkedHashMap<>();
        if (lessonId == null) {
            return out;
        }
        Map<String, LessonSlides.Concept> concepts = LessonSlides.concepts(find(lessonId).getContent());
        for (QuizQuestion q : questions) {
            out.put(q.getId(), q.conceptList().stream().map(concepts::get).filter(Objects::nonNull).toList());
        }
        return out;
    }

    /**
     * Nenhum quiz repete pergunta ja feita no mesmo concurso (em outro quiz, revisao ou no mesmo envio): a repeticao
     * so treina a memoria do enunciado. Reescrever cobrando o mesmo ponto de outro angulo e permitido (e e o que
     * "retomar erro" pede); trocar so as palavras nao.
     */
    private void rejectRepeated(Quiz quiz, List<NewQuestion> questions) {
        Map<Long, Quiz> quizzes = allQuizzes().stream().collect(Collectors.toMap(Quiz::getId, Function.identity()));
        List<QuizQuestion> existing = questionRepository.findAll().stream()
                .filter(q -> quizzes.containsKey(q.getQuizId())
                        && Objects.equals(quizzes.get(q.getQuizId()).getConcursoId(), quiz.getConcursoId())).toList();
        // No Certo/Errado as alternativas sao sempre as mesmas duas, entao so o enunciado distingue o item.
        List<QuestionSimilarity.Fingerprint> known = existing.stream().map(q -> QuestionSimilarity.Fingerprint.of(
                q.getStatement(), quizzes.get(q.getQuizId()).certoErrado() ? null
                        : q.alternativeList().stream().map(Alternative::texto).toList())).toList();
        List<QuestionSimilarity.Fingerprint> sent = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            NewQuestion n = questions.get(i);
            QuestionSimilarity.Fingerprint print = QuestionSimilarity.Fingerprint.of(n.statement(),
                    quiz.certoErrado() ? null : n.texts());
            int old = -1;
            for (int k = 0; k < known.size() && old < 0; k++) {
                old = print.repeats(known.get(k)) ? k : -1;
            }
            int twin = -1;
            for (int k = 0; k < sent.size() && twin < 0; k++) {
                twin = print.repeats(sent.get(k)) ? k : -1;
            }
            if (old >= 0) {
                QuizQuestion q = existing.get(old);
                Quiz where = quizzes.get(q.getQuizId());
                problems.add("Pergunta " + (i + 1) + " repete a #" + q.getId() + " do quiz \""
                        + (where == null ? q.getQuizId() : where.getTitle()) + "\" (" + clip(q.getStatement(), 80) + ")");
            } else if (twin >= 0) {
                problems.add("Pergunta " + (i + 1) + " repete a pergunta " + (twin + 1) + " deste mesmo envio");
            }
            sent.add(print);
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Pergunta repetida (já feita neste concurso): "
                    + String.join("; ", problems.stream().limit(5).toList()) + (problems.size() > 5 ? "; ..." : "")
                    + ". Escreva outra sobre um ponto ou ângulo ainda não cobrado (trocar só as palavras não vale) e, "
                    + "para ver o que já foi perguntado, use perguntas_ja_feitas.");
        }
    }

    /** Pergunta de quiz ja feita, com o resultado da ultima resposta (nulo = ainda nao respondida ou em branco). */
    public record AskedQuestion(Long perguntaId, Long quizId, String quiz, String disciplina, String topico,
                                String enunciado, Boolean acertou, LocalDateTime respondidaEm) {
    }

    /**
     * Perguntas ja feitas nos quizzes (e revisoes) do concurso em vigor, as mais recentes primeiro: da aula, se
     * informada, ou da disciplina e/ou topico (parte do nome, sem acento); sem nada, de tudo.
     */
    public List<AskedQuestion> askedQuestions(Long lessonId, String subject, String topic, int limit) {
        String s = blank(subject) == null ? null : ScoringCalculator.normalize(subject);
        String t = blank(topic) == null ? null : ScoringCalculator.normalize(topic);
        Map<Long, Quiz> quizzes = visibleQuizzes().stream().collect(Collectors.toMap(Quiz::getId, Function.identity()));
        List<QuizQuestion> found = questionRepository.findAll().stream()
                .filter(q -> quizzes.containsKey(q.getQuizId()))
                .filter(q -> lessonId == null || lessonId.equals(q.getLessonId()))
                .filter(q -> s == null || q.getSubject() != null && contains(q.getSubject(), s))
                .filter(q -> t == null || q.getTopic() != null && contains(q.getTopic(), t))
                .sorted(Comparator.comparing(QuizQuestion::getId).reversed()).limit(Math.max(1, limit)).toList();
        Map<Long, QuizAttempt> last = new LinkedHashMap<>();
        attemptRepository.findByQuizQuestionIdInOrderByAnsweredAt(found.stream().map(QuizQuestion::getId).toList())
                .forEach(a -> last.put(a.getQuizQuestionId(), a));
        return found.stream().map(q -> new AskedQuestion(q.getId(), q.getQuizId(), quizzes.get(q.getQuizId()).getTitle(),
                q.getSubject(), q.getTopic(), clip(q.getStatement(), 220),
                last.containsKey(q.getId()) ? last.get(q.getId()).getCorrect() : null,
                last.containsKey(q.getId()) ? last.get(q.getId()).getAnsweredAt() : null)).toList();
    }

    /** Acrescenta perguntas a um quiz. Toda alternativa precisa de explicacao (por que certa/errada). */
    @Transactional
    public List<QuizQuestion> addQuestions(Long quizId, List<NewQuestion> questions) {
        Quiz quiz = quiz(quizId);
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("Envie ao menos uma pergunta.");
        }
        rejectRepeated(quiz, questions);
        if (quiz.getLessonId() != null) {
            checkConcepts(quiz, find(quiz.getLessonId()), questions);
        }
        if (PROVA.equals(quiz.getLevel())) {
            rejectGiveaways(quiz, questions);
        }
        int position = questions(quizId).size();
        List<QuizQuestion> saved = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            NewQuestion n = questions.get(i);
            String label = "Pergunta " + (i + 1) + ": ";
            if (n.statement() == null || n.statement().isBlank()) {
                throw new IllegalArgumentException(label + "falta o enunciado.");
            }
            List<Alternative> alternatives;
            if (quiz.certoErrado()) {
                alternatives = certoErrado(label, n.explanations());
            } else {
                if (n.texts() == null || n.texts().size() < 2 || n.texts().size() > 5) {
                    throw new IllegalArgumentException(label + "de 2 a 5 alternativas.");
                }
                if (n.explanations() == null || n.explanations().size() != n.texts().size()
                        || n.explanations().stream().anyMatch(e -> e == null || e.isBlank())) {
                    throw new IllegalArgumentException(label + "cada alternativa precisa da explicação de por que está "
                            + "certa ou errada.");
                }
                alternatives = new ArrayList<>();
                for (int a = 0; a < n.texts().size(); a++) {
                    alternatives.add(new Alternative(String.valueOf((char) ('A' + a)), n.texts().get(a).strip(),
                            n.explanations().get(a).strip()));
                }
            }
            String correct = answerLetter(quiz, n.correct());
            if (alternatives.stream().noneMatch(a -> a.letra().equals(correct))) {
                throw new IllegalArgumentException(label + "a correta (\"" + n.correct() + "\") não é uma das alternativas.");
            }
            QuizQuestion q = new QuizQuestion();
            q.setQuizId(quizId);
            q.setLessonId(quiz.getLessonId());
            q.setPosition(++position);
            q.setSubject(quiz.getSubject());
            q.setTopic(blank(n.topic()) != null ? n.topic().strip() : quiz.getTopic());
            q.setStatement(n.statement().strip());
            q.setAlternativeList(alternatives);
            q.setCorrectAnswer(correct);
            q.setConcepts(n.concepts() == null || n.concepts().isEmpty() ? null
                    : n.concepts().stream().map(LessonService::conceptId).filter(c -> !c.isEmpty()).distinct()
                    .collect(Collectors.joining(",")));
            q.setBeyondLesson(n.beyondLesson() && !quiz.isEmbedded());
            saved.add(questionRepository.save(q));
        }
        if (quiz.getFinishedAt() != null) {
            quiz.setFinishedAt(null);   // pergunta nova: o quiz volta a ter o que responder
            quizRepository.save(quiz);
        }
        return saved;
    }

    public record DeletedQuiz(Long quizId, String title, int questions, int answers) {
    }

    /** Apaga o quiz com as perguntas e todas as respostas (saem do desempenho e dos erros anteriores). */
    @Transactional
    public DeletedQuiz deleteQuiz(Long quizId) {
        Quiz quiz = quiz(quizId);
        List<QuizQuestion> questions = questions(quizId);
        List<QuizAttempt> attempts = attemptRepository.findByQuizQuestionIdInOrderByAnsweredAt(
                questions.stream().map(QuizQuestion::getId).toList());
        attemptRepository.deleteAll(attempts);
        flashcardRatingRepository.deleteAll(flashcardRatingRepository.findByQuizIdOrderByRatedAt(quizId));
        questionRepository.deleteAll(questions);
        quizRepository.delete(quiz);
        return new DeletedQuiz(quizId, quiz.getTitle(), questions.size(), attempts.size());
    }

    /**
     * Corrige a pergunta e, se enviados, os conceitos que ela cobra (precisam existir na aula) ou a marca de
     * ampliacao; e o fecho do "incluir na aula": reescrever_aula com o conceito novo e depois isto.
     */
    @Transactional
    public QuizQuestion fixQuestion(Long quizId, int number, String statement, String topic, List<String> texts,
                                    List<String> explanations, String correct, List<String> concepts,
                                    Boolean beyondLesson) {
        QuizQuestion q = fixQuestion(quizId, number, statement, topic, texts, explanations, correct);
        if (concepts == null && beyondLesson == null) {
            return q;
        }
        List<String> ids = concepts == null ? q.conceptList()
                : concepts.stream().map(LessonService::conceptId).filter(c -> !c.isEmpty()).distinct().toList();
        boolean beyond = beyondLesson != null ? beyondLesson : q.isBeyondLesson();
        Quiz quiz = quiz(quizId);
        if (quiz.getLessonId() != null && !beyond) {
            Map<String, LessonSlides.Concept> known = LessonSlides.concepts(find(quiz.getLessonId()).getContent());
            List<String> unknown = ids.stream().filter(id -> !known.containsKey(id)).toList();
            if (!known.isEmpty() && (ids.isEmpty() || !unknown.isEmpty())) {
                throw new IllegalArgumentException("Conceito " + unknown + " não está na aula " + quiz.getLessonId()
                        + " (conceitos: " + String.join(", ", known.keySet()) + "). Inclua com reescrever_aula ou "
                        + "envie ampliacao=true.");
            }
        }
        q.setConcepts(ids.isEmpty() ? null : String.join(",", ids));
        q.setBeyondLesson(beyond && !quiz.isEmbedded());
        return questionRepository.save(q);
    }

    /**
     * Corrige uma pergunta (campos nulos ficam como estao). Mudando a correta ou as alternativas, as respostas ja
     * dadas sao recorrigidas pela nova correta (o placar e os erros anteriores acompanham).
     */
    @Transactional
    public QuizQuestion fixQuestion(Long quizId, int number, String statement, String topic, List<String> texts,
                                    List<String> explanations, String correct) {
        QuizQuestion q = questions(quizId).stream().filter(x -> Integer.valueOf(number).equals(x.getPosition()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("O quiz " + quizId + " não tem a pergunta "
                        + number + "."));
        if (statement != null && !statement.isBlank()) {
            q.setStatement(statement.strip());
        }
        if (topic != null && !topic.isBlank()) {
            q.setTopic(topic.strip());
        }
        Quiz quiz = quiz(quizId);
        List<Alternative> alternatives = new ArrayList<>(q.alternativeList());
        if (quiz.certoErrado()) {
            List<String> given = explanations != null && !explanations.isEmpty() ? explanations : texts;
            if (given != null && !given.isEmpty()) {
                alternatives = certoErrado("", given);
            }
        } else if (texts != null && !texts.isEmpty()) {
            if (texts.size() < 2 || texts.size() > 5 || explanations == null || explanations.size() != texts.size()
                    || texts.stream().anyMatch(t -> t == null || t.isBlank())
                    || explanations.stream().anyMatch(e -> e == null || e.isBlank())) {
                throw new IllegalArgumentException("Envie de 2 a 5 alternativas, cada uma com texto e explicação.");
            }
            alternatives.clear();
            for (int a = 0; a < texts.size(); a++) {
                alternatives.add(new Alternative(String.valueOf((char) ('A' + a)), texts.get(a).strip(),
                        explanations.get(a).strip()));
            }
        } else if (explanations != null && !explanations.isEmpty()) {
            if (explanations.size() != alternatives.size() || explanations.stream().anyMatch(e -> e == null || e.isBlank())) {
                throw new IllegalArgumentException("Envie uma explicação para cada uma das " + alternatives.size()
                        + " alternativas.");
            }
            for (int a = 0; a < alternatives.size(); a++) {
                Alternative old = alternatives.get(a);
                alternatives.set(a, new Alternative(old.letra(), old.texto(), explanations.get(a).strip()));
            }
        }
        String letter = correct == null || correct.isBlank() ? q.getCorrectAnswer() : answerLetter(quiz, correct);
        if (alternatives.stream().noneMatch(a -> a.letra().equals(letter))) {
            throw new IllegalArgumentException("A correta (\"" + letter + "\") não é uma das alternativas.");
        }
        q.setAlternativeList(alternatives);
        q.setCorrectAnswer(letter);
        questionRepository.save(q);
        List<QuizAttempt> attempts = attemptRepository.findByQuizQuestionIdInOrderByAnsweredAt(List.of(q.getId()));
        attempts.forEach(a -> a.setCorrect(BLANK.equals(a.getSelectedAnswer()) ? null
                : letter.equals(a.getSelectedAnswer())));
        attemptRepository.saveAll(attempts);
        return q;
    }

    /** Item Certo/Errado: as duas opcoes fixas (C e E), com a explicacao de cada uma, na ordem Certo, Errado. */
    private static List<Alternative> certoErrado(String label, List<String> explanations) {
        if (explanations == null || explanations.size() != 2 || explanations.stream().anyMatch(e -> e == null || e.isBlank())) {
            throw new IllegalArgumentException(label + "item Certo/Errado: envie 2 alternativas (Certo e Errado, nessa "
                    + "ordem), cada uma com a explicação de por que o item está ou não certo.");
        }
        return List.of(new Alternative("C", "Certo", explanations.get(0).strip()),
                new Alternative("E", "Errado", explanations.get(1).strip()));
    }

    /** Letra da correta; no Certo/Errado aceita "Certo"/"Errado" (e "A"/"B", na ordem das alternativas). */
    private static String answerLetter(Quiz quiz, String correct) {
        String c = correct == null ? "" : ScoringCalculator.normalize(correct).toUpperCase(Locale.ROOT);
        if (quiz.certoErrado()) {
            return switch (c) {
                case "C", "CERTO", "A" -> "C";
                case "E", "ERRADO", "B" -> "E";
                default -> c;
            };
        }
        return c;
    }

    public record AnswerResult(boolean correct, boolean blank, String correctAnswer, boolean finished, int correctCount,
                               int total) {
    }

    /**
     * Responde uma pergunta na tela. Quando todas tem resposta, o quiz fica concluido. No Certo/Errado, {@link #BLANK}
     * deixa o item em branco: conta como respondido, mas sem acerto nem erro.
     */
    @Transactional
    public AnswerResult answer(Long questionId, String selected) {
        QuizQuestion q = questionRepository.findById(questionId).orElseThrow();
        boolean blank = selected != null && BLANK.equals(selected.strip());
        if (blank && !quiz(q.getQuizId()).certoErrado()) {
            throw new IllegalArgumentException("Só os itens Certo/Errado podem ficar em branco.");
        }
        QuizAttempt attempt = new QuizAttempt();
        attempt.setQuizQuestionId(questionId);
        attempt.setSelectedAnswer(selected == null ? null : selected.strip().toUpperCase(Locale.ROOT));
        attempt.setCorrect(blank ? null : q.getCorrectAnswer().equals(attempt.getSelectedAnswer()));
        attemptRepository.save(attempt);
        Map<Long, QuizAttempt> latest = latest(q.getQuizId());
        List<QuizQuestion> all = questions(q.getQuizId());
        boolean finished = all.stream().allMatch(x -> latest.containsKey(x.getId()));
        if (finished) {
            Quiz quiz = quiz(q.getQuizId());
            quiz.setFinishedAt(LocalDateTime.now());
            quizRepository.save(quiz);
        }
        int right = (int) latest.values().stream().filter(a -> Boolean.TRUE.equals(a.getCorrect())).count();
        return new AnswerResult(Boolean.TRUE.equals(attempt.getCorrect()), blank, q.getCorrectAnswer(), finished, right,
                all.size());
    }

    /** Refazer: as respostas antigas ficam no historico, mas a tela volta a ficar em branco. */
    @Transactional
    public void restart(Long quizId) {
        Quiz quiz = quiz(quizId);
        quiz.setFinishedAt(null);
        quiz.setRestartedAt(LocalDateTime.now());
        quizRepository.save(quiz);
    }

    /** Ultima resposta de cada pergunta do quiz (desde a ultima vez que foi recomecado). */
    public Map<Long, QuizAttempt> latest(Long quizId) {
        List<QuizQuestion> qs = questions(quizId);
        LocalDateTime since = quiz(quizId).getRestartedAt();
        Map<Long, QuizAttempt> out = new LinkedHashMap<>();
        for (QuizAttempt a : attemptRepository.findByQuizQuestionIdInOrderByAnsweredAt(
                qs.stream().map(QuizQuestion::getId).toList())) {
            if (since == null || !a.getAnsweredAt().isBefore(since)) {
                out.put(a.getQuizQuestionId(), a);
            }
        }
        return out;
    }

    /** @param emBranco item Certo/Errado deixado em branco (acertou fica nulo: nao e acerto nem erro). */
    /**
     * @param revise    slides da aula que ensinam o que a pergunta cobra ("slide 3: Titulo")
     * @param alemDaAula pergunta marcada como ampliacao (vai alem do que a aula ensinou)
     */
    public record QuestionResult(Long perguntaId, int numero, String topico, String enunciado, String suaResposta,
                                 String correta, Boolean acertou, boolean emBranco, String explicacaoDaSuaResposta,
                                 List<String> conceitos, List<String> revise, boolean alemDaAula, String tipo,
                                 int dicasUsadas) {
    }

    /**
     * @param brancos itens deixados em branco (contam como respondidos; nao pontuam nem anulam acerto).
     * @param liquido no Certo/Errado: acertos menos erros (cada erro anula um acerto; branco nao conta); null na
     *                multipla escolha.
     */
    public record QuizResult(Long quizId, String titulo, Long aulaId, String disciplina, String topico, String nivel,
                             int perguntas, int respondidas, int acertos, int brancos, boolean concluido,
                             List<QuestionResult> questoes, String formato, Integer liquido,
                             List<Integer> alemDaAula, String incluirNaAula, List<FlashcardOutcome> flashcards) {
    }

    /** Flashcard do quiz com a ultima autoavaliacao (nula = nao avaliado). */
    public record FlashcardOutcome(Long flashcardId, Long aulaId, String conceito, String frente, String avaliacao) {
    }

    private List<FlashcardOutcome> flashcardOutcomes(Quiz quiz) {
        List<Long> ids = quiz.flashcardIds();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, String> last = new LinkedHashMap<>();
        flashcardRatingRepository.findByQuizIdOrderByRatedAt(quiz.getId())
                .forEach(r -> last.put(r.getFlashcardId(), r.getRating()));
        Map<Long, com.pdf2questao.flashcard.Flashcard> byId = flashcardRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(com.pdf2questao.flashcard.Flashcard::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).map(f -> new FlashcardOutcome(f.getId(),
                f.getLessonId(), f.getConcept(), clip(f.getFront(), 160), last.get(f.getId()))).toList();
    }

    public QuizResult result(Long quizId) {
        Quiz quiz = quiz(quizId);
        List<QuizQuestion> qs = questions(quizId);
        Map<Long, QuizAttempt> latest = latest(quizId);
        Map<Long, List<LessonSlides.Concept>> where = conceptsOf(quiz.getLessonId(), qs);
        List<QuestionResult> items = new ArrayList<>();
        for (QuizQuestion q : qs) {
            QuizAttempt a = latest.get(q.getId());
            Map<String, Alternative> byLetter = q.alternativeList().stream()
                    .collect(Collectors.toMap(Alternative::letra, Function.identity()));
            boolean blank = a != null && BLANK.equals(a.getSelectedAnswer());
            items.add(new QuestionResult(q.getId(), q.getPosition(), q.getTopic(), q.getStatement(),
                    a == null ? null : shown(a.getSelectedAnswer()), q.getCorrectAnswer(),
                    a == null ? null : a.getCorrect(), blank,
                    blank ? "Em branco: não pontua nem anula acerto."
                            : a == null || byLetter.get(a.getSelectedAnswer()) == null ? null
                            : byLetter.get(a.getSelectedAnswer()).explicacao(),
                    q.conceptList(), where.getOrDefault(q.getId(), List.of()).stream()
                    .map(c -> "slide " + c.slide() + ": " + c.slideTitle()).distinct().toList(), q.isBeyondLesson(),
                    q.getKind() == null ? "pergunta" : q.getKind(), a == null ? 0 : a.getHintsUsed()));
        }
        List<Integer> beyond = items.stream().filter(QuestionResult::alemDaAula).map(QuestionResult::numero).toList();
        int blanks = (int) items.stream().filter(QuestionResult::emBranco).count();
        int answered = (int) items.stream().filter(i -> i.acertou() != null || i.emBranco()).count();
        int right = (int) items.stream().filter(i -> Boolean.TRUE.equals(i.acertou())).count();
        return new QuizResult(quiz.getId(), quiz.getTitle(), quiz.getLessonId(), quiz.getSubject(), quiz.getTopic(),
                quiz.getLevel(), qs.size(), answered, right, blanks, quiz.getFinishedAt() != null, items,
                quiz.certoErrado() ? Quiz.CERTO_ERRADO : "multipla_escolha",
                quiz.certoErrado() ? right - (answered - blanks - right) : null, beyond,
                beyond.isEmpty() || quiz.getLessonId() == null ? null
                        : "As perguntas " + beyond + " foram além da aula " + quiz.getLessonId() + ". Para incluí-las, "
                        + "chame reescrever_aula acrescentando um slide (ou caixa) com {#id} para cada ponto e depois "
                        + "corrigir_pergunta_quiz com esses conceitos.", flashcardOutcomes(quiz));
    }

    // ---- tela /aulas: tudo agrupado por disciplina ----

    /** Resumo de um quiz para as listas. */
    public record QuizCard(Long id, String titulo, String nivel, int perguntas, int respondidas, int acertos,
                           boolean concluido, LocalDateTime criadoEm, Long aulaId, String aula) {
        public String status() {
            return concluido ? "feito" : respondidas > 0 ? "andamento" : "novo";
        }
    }

    public record LessonCard(Lesson aula, List<QuizCard> quizzes, int feitos, int acertos, int respondidas) {
        public boolean concluida() {
            return !quizzes.isEmpty() && feitos == quizzes.size();
        }
    }

    public record SubjectGroup(String disciplina, List<LessonCard> aulas, List<QuizCard> avulsos, int pendentes,
                               int acertos, int respondidas, LocalDateTime ultimaAtividade) {
        public double percentual() {
            return respondidas == 0 ? 0 : Math.round(acertos * 1000.0 / respondidas) / 10.0;
        }
    }

    public record Overview(List<QuizCard> pendentes, List<SubjectGroup> disciplinas) {
    }

    /** Quizzes a responder (mais recentes primeiro) e aulas/quizzes avulsos por disciplina (mais mexida primeiro). */
    public Overview overview() {
        Map<Long, Lesson> lessons = visible().stream().collect(Collectors.toMap(Lesson::getId, Function.identity(),
                (a, b) -> a, LinkedHashMap::new));
        List<Quiz> visibleQuizzes = visibleQuizzes();
        List<QuizCard> cards = visibleQuizzes.stream().map(q -> {
            QuizResult r = result(q.getId());
            Lesson l = q.getLessonId() == null ? null : lessons.get(q.getLessonId());
            return new QuizCard(q.getId(), q.getTitle(), q.getLevel(), r.perguntas(), r.respondidas(), r.acertos(),
                    r.concluido(), q.getCreatedAt(), l == null ? null : l.getId(), l == null ? null : l.getTitle());
        }).toList();
        Map<Long, List<QuizCard>> byLesson = cards.stream().filter(c -> c.aulaId() != null)
                .collect(Collectors.groupingBy(QuizCard::aulaId));
        Map<Long, Quiz> quizzes = visibleQuizzes.stream().collect(Collectors.toMap(Quiz::getId, Function.identity()));

        Map<String, String> names = new LinkedHashMap<>();
        Map<String, List<LessonCard>> lessonsBy = new LinkedHashMap<>();
        Map<String, List<QuizCard>> looseBy = new LinkedHashMap<>();
        for (Lesson l : lessons.values()) {
            List<QuizCard> qs = new ArrayList<>(byLesson.getOrDefault(l.getId(), List.of()));
            qs.sort(Comparator.comparing(QuizCard::criadoEm));
            LessonCard card = new LessonCard(l, qs, (int) qs.stream().filter(QuizCard::concluido).count(),
                    qs.stream().mapToInt(QuizCard::acertos).sum(), qs.stream().mapToInt(QuizCard::respondidas).sum());
            lessonsBy.computeIfAbsent(subjectKey(l.getSubject(), names), k -> new ArrayList<>()).add(card);
        }
        for (QuizCard c : cards) {
            if (c.aulaId() == null) {
                looseBy.computeIfAbsent(subjectKey(quizzes.get(c.id()).getSubject(), names), k -> new ArrayList<>()).add(c);
            }
        }
        List<SubjectGroup> groups = new ArrayList<>();
        for (Map.Entry<String, String> e : names.entrySet()) {
            List<LessonCard> ls = lessonsBy.getOrDefault(e.getKey(), List.of());
            List<QuizCard> loose = looseBy.getOrDefault(e.getKey(), List.of());
            List<QuizCard> every = new ArrayList<>(loose);
            ls.forEach(l -> every.addAll(l.quizzes()));
            LocalDateTime last = every.stream().map(QuizCard::criadoEm).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(ls.stream().map(l -> l.aula().getCreatedAt())
                            .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null));
            groups.add(new SubjectGroup(e.getValue(), ls, loose, (int) every.stream().filter(c -> !c.concluido()).count(),
                    every.stream().mapToInt(QuizCard::acertos).sum(), every.stream().mapToInt(QuizCard::respondidas).sum(),
                    last));
        }
        groups.sort(Comparator.comparing(SubjectGroup::ultimaAtividade,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return new Overview(cards.stream().filter(c -> !c.concluido()).toList(), groups);
    }

    /** Chave da disciplina sem acento/caixa; guarda o primeiro nome visto para exibir. */
    private static String subjectKey(String subject, Map<String, String> names) {
        String name = blank(subject) == null ? "Sem disciplina" : subject.strip();
        String key = ScoringCalculator.normalize(name);
        names.putIfAbsent(key, name);
        return key;
    }

    // ---- desempenho (separado do das provas e simulados) ----

    public record TopicStat(String disciplina, String topico, long acertos, long respondidas, double percentual,
                            String ultimaResposta) {
    }

    /** Desempenho nos quizzes por topico (ultima resposta de cada pergunta), piores primeiro. */
    public List<TopicStat> stats() {
        return attemptRepository.statsByTopic(workspace.currentId()).stream().map(r -> {
            long right = ((Number) r[2]).longValue();
            long total = ((Number) r[3]).longValue();
            return new TopicStat((String) r[0], (String) r[1], right, total,
                    total == 0 ? 0 : Math.round(right * 1000.0 / total) / 10.0, r[4] == null ? null : r[4].toString());
        }).toList();
    }

    public record Totals(long acertos, long erros, long respondidas, double percentual, long quizzes) {
    }

    /** Acertos x erros em todos os quizzes (ultima resposta de cada pergunta). */
    public Totals totals() {
        Object[] r = attemptRepository.totals(workspace.currentId()).get(0);
        long right = ((Number) r[0]).longValue();
        long total = ((Number) r[1]).longValue();
        return new Totals(right, total - right, total, total == 0 ? 0 : Math.round(right * 1000.0 / total) / 10.0,
                visibleQuizzes().size());
    }

    /** Reaproveita o nome de disciplina ja usado (sem acento/caixa), para nao dividir a mesma materia em dois grupos. */
    private String canonicalSubject(String subject) {
        String name = blank(subject);
        if (name == null) {
            return null;
        }
        String key = ScoringCalculator.normalize(name);
        return Stream.concat(all().stream().map(Lesson::getSubject), allQuizzes().stream().map(Quiz::getSubject))
                .filter(Objects::nonNull).filter(x -> ScoringCalculator.normalize(x).equals(key))
                .findFirst().orElse(name);
    }

    // ---- constancia: dias seguidos respondendo quiz ----

    public record DayCount(LocalDate dia, long respostas) {
    }

    /** Respostas de hoje, dias seguidos (conta ate ontem se hoje ainda nao respondeu) e os ultimos 28 dias. */
    public record Streak(long hoje, int diasSeguidos, int recorde, List<DayCount> ultimosDias) {
    }

    public Streak streak() {
        Map<LocalDate, Long> perDay = attemptRepository.findAll().stream()
                .filter(a -> a.getAnsweredAt() != null)
                .collect(Collectors.groupingBy(a -> a.getAnsweredAt().toLocalDate(), Collectors.counting()));
        LocalDate today = LocalDate.now();
        LocalDate day = perDay.containsKey(today) ? today : today.minusDays(1);
        int current = 0;
        while (perDay.containsKey(day)) {
            current++;
            day = day.minusDays(1);
        }
        int best = 0;
        int run = 0;
        LocalDate prev = null;
        for (LocalDate d : perDay.keySet().stream().sorted().toList()) {
            run = prev != null && prev.plusDays(1).equals(d) ? run + 1 : 1;
            best = Math.max(best, run);
            prev = d;
        }
        List<DayCount> last = new ArrayList<>();
        for (int i = 27; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            last.add(new DayCount(d, perDay.getOrDefault(d, 0L)));
        }
        return new Streak(perDay.getOrDefault(today, 0L), current, best, last);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
