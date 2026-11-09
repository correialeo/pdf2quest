package com.pdf2questao.mcp;

import com.pdf2questao.aula.Lesson;
import com.pdf2questao.aula.LessonService;
import com.pdf2questao.aula.LessonService.AskedQuestion;
import com.pdf2questao.aula.LessonService.NewQuestion;
import com.pdf2questao.aula.LessonService.PreviousError;
import com.pdf2questao.aula.LessonService.QuizResult;
import com.pdf2questao.aula.LessonService.Totals;
import com.pdf2questao.aula.Quiz;
import com.pdf2questao.aula.LessonService.TopicStat;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Tutor: aula sobre um topico do edital com quiz ligado a ela, respondido na tela do sistema; o resultado volta para
 * orientar reforcos e revisoes. O desempenho do quiz e separado do das provas e simulados.
 */
@Component
public class LessonMcpTools {

    private final LessonService lessonService;
    private final McpConcurso mcpConcurso;
    private static final Pattern SOURCE_LINK = Pattern.compile("https?://\\S+");
    /** Sinais de que a "fonte" não foi lida de fato: isso vai em 'naoConfirmado', não em 'fontes'. */
    private static final Pattern UNVERIFIED = Pattern.compile(
            "de mem[óo]ria|n[ãa]o (consegui|confirm|li\\b|lid[oa]|foi poss)|sem confirma|truncad|s[óo] (o )?resumo|apenas (o )?resumo",
            Pattern.CASE_INSENSITIVE);
    private static final int MIN_ATTEMPTS = 40;
    private static final int MAX_EXCERPT_SHOWN = 2500;

    private final String baseUrl;

    private final com.pdf2questao.flashcard.FlashcardService flashcardService;
    private final com.pdf2questao.study.SpacedRepetitionService spacedRepetition;

    public LessonMcpTools(LessonService lessonService, McpConcurso mcpConcurso,
                          com.pdf2questao.flashcard.FlashcardService flashcardService,
                          com.pdf2questao.study.SpacedRepetitionService spacedRepetition,
                          @Value("${server.port:8080}") int port) {
        this.spacedRepetition = spacedRepetition;
        this.flashcardService = flashcardService;
        this.mcpConcurso = mcpConcurso;
        this.lessonService = lessonService;
        this.baseUrl = "http://localhost:" + port;
    }

    @McpPrompt(name = "rotina_estudo_do_dia", title = "Rotina de estudo do dia",
            description = "Planeja o dia pelo cronograma, prepara a aula e o quiz no sistema e, depois que o usuário "
                    + "responde, agenda reforços e atualiza as revisões.")
    public String dailyRoutine(
            @McpArg(name = "foco", description = "Disciplina ou assunto para priorizar hoje (opcional).",
                    required = false) String foco,
            @McpArg(name = "concurso", description = "Concurso do dia (id ou parte do nome; vazio = o ativo).",
                    required = false) String concurso) {
        return """
                Seja o tutor do usuário hoje, usando o servidor MCP pdf2questao. %s%s

                1. PLANEJAMENTO. Consulte prioridades (o que dá mais ponto estudar agora), tarefas_de_hoje, \\
                revisoes_de_hoje, desempenho_quiz (quizzes, por tópico), \\
                resumo_de_erros e desempenho_por_disciplina (questões de prova/simulado). Escolha o tópico principal \\
                (tarefa do cronograma de hoje) e, se couber, um reforço do que o usuário vem errando. Confira o \\
                tópico no conteúdo programático do edital (estrutura_do_edital / ler_edital) e diga ao usuário, em \\
                3 a 5 linhas, o plano do dia e por quê; se prioridades apontar algo mais rentável que a tarefa do dia, \\
                mostre a sugestão e deixe o usuário decidir (não mude o cronograma sozinho).

                2. AULA. Antes de escrever, leia o item do edital (ler_edital), pesquise na web fontes atuais do \\
                tópico (norma vigente, súmulas, temas e jurisprudência, autores e conceitos; prefira fontes \\
                oficiais) e veja o que o usuário já anotou (buscar_paginas / ler_pagina) e como a \\
                banca cobra o tópico (exemplos_da_banca). Escreva a aula com criar_aula (tarefaId = a tarefa de \\
                hoje que ela cobre; em 'fontes', só o que você LEU, cada fonte com o link (URL)), no nível dele (use o \\
                desempenho), EM SLIDES (veja a descrição de criar_aula: caixas :::regra/:::pegadinha/:::exemplo e \\
                exemplos com os dados de entrada e o resultado lado a lado): um slide ("=== Título") por assunto do tópico (ex.: DFC num, DMPL noutro), \\
                cada um com a regra, a exceção e a pegadinha da banca, do que mais cai ao que menos cai (sem número \\
                de item de norma que a banca não cobra, sem o que está fora do edital e sem bastidor da pesquisa), e \\
                logo depois de cada explicação uma questão de fixação no nível da prova ("[[questão N]]", enviadas \\
                em perguntasDaAula com estiloDaBanca e referencias, cada uma com 'conceitos' = o {#id} do slide, e \\
                com números, nomes e estrutura de dados DIFERENTES do exemplo do slide). Cada conceito tem definição \\
                curta, o porquê, um exemplo com dados e a pegadinha de prova; nenhuma regra solta. Feche com um slide de quadro comparativo e um \\
                de resumo de meia página (é ele que volta na revisão). Para lei \\
                ou norma, use a redação vigente confirmada na fonte oficial e cite o dispositivo.
                COLETA DA FONTE EM TEXTO LITERAL (antes de escrever). O WebFetch devolve um RESUMO da página, não o \\
                texto: não escreva a aula a partir dele. Para cada fonte: baixe com Bash (curl -sL URL > f.html; PDF: \\
                curl -sLo f.pdf URL && pdftotext -layout f.pdf f.txt), leia o arquivo em partes (várias páginas e \\
                seções, não só o começo), recorte o texto literal de cada dispositivo ou seção que a aula vai usar \\
                (artigo inteiro, com parágrafos e incisos) e grave com registrar_trecho; passe os ids em 'trechos' \\
                de criar_aula. Todo artigo citado precisa de um trecho gravado. Se a fonte não abrir, procure outro \\
                site oficial (Câmara, Senado, LexML, normas.leg.br, documentação oficial do produto); só dê algo por \\
                não confirmado depois de pelo menos duas tentativas diferentes, em 'naoConfirmado' e 'tentativas'. \\
                Escreva cada conceito a partir do trecho: definição curta, o porquê, um exemplo com dados e a \\
                pegadinha. Nunca escreva da memória o que a fonte deveria dizer, nem leve isso para o quiz.

                FLASHCARDS. Logo depois de criar a aula, crie os cartões dela com criar_flashcards (um ou mais \\
                por conceito; eles voltam nas revisões, antes das questões).

                3. QUIZ. Veja erros_quiz_anteriores do assunto e, com criar_quiz (aulaId da aula; o quiz herda a tarefa), crie de 6 a 10 \
                perguntas baseadas no que a aula ensinou (não em outra \\
                coisa: cada uma com 'conceitos' = o {#id} do slide que cobra; veja cobertura_aula e prefira os \\
                conceitos ainda sem pergunta; o que for além da aula só com ampliacao=true), no nível da prova (nivel "prova"): siga os passos 3 a 5 do prompt novo_quiz (questões \\
                reais da banca lidas como modelo, estiloDaBanca, referencias e o teste do chute), com 4 ou 5 \\
                alternativas. Para CADA alternativa, a explicação de \\
                por que está certa ou errada (ela aparece quando o usuário clica). Informe o tópico de cada uma. \\
                Passe o link da aula ao usuário e peça para ele estudar e responder no sistema. Não mostre as \\
                respostas na conversa.

                4. DEPOIS DO QUIZ (quando o usuário avisar). Leia resultado_quiz (sem id = o mais recente). Explique os erros em poucas \\
                linhas. Se ele errou um tópico, agende um reforço com criar_tarefa (em 1 a 2 dias, descrição com \\
                o que confundiu) e confirme com ele. Conclua a tarefa do cronograma com concluir_tarefa (minutos \\
                informados por ele), registre o resumo com anexar_ao_resumo_do_dia e, se houver revisões do dia, \\
                faça-as pelo prompt revisao_do_dia (quiz difícil, sem repetir pergunta) e marcar_revisao. Termine \\
                dizendo o que vem amanhã.
                """.formatted(McpText.blank(foco) ? "" : "Foco pedido: " + foco + ". ",
                McpText.blank(concurso) ? "Use o concurso ativo (listar_concursos)."
                        : "Concurso: " + concurso + ". Passe concurso=\"" + concurso + "\" em tarefas_de_hoje, "
                        + "criar_aula, criar_quiz, criar_tarefa, desempenho_quiz, desempenho_por_disciplina e "
                        + "listar_erros; o perfil do edital é o perfilId dele em listar_concursos.");
    }

    public record LessonCreated(Long aulaId, String titulo, String concurso, int slides, int questoesDeFixacao,
                                String link) {
    }

    private static final String SLIDES_HELP = "Aula em SLIDES (obrigatório: ao menos 2 slides e 3 questões de "
            + "fixação): cada linha \"=== Título\" abre um slide, "
            + "uma página por assunto (ex.: \"=== DFC\", \"=== DMPL\"); dentro do slide, a linha \"[[questão N]]\" "
            + "põe ali a questão N de 'perguntasDaAula' para fixar o que acabou de ser explicado (nível prova, com "
            + "estiloDaBanca e referencias). Toda questão precisa aparecer em algum slide. As fontes viram o último "
            + "slide sozinhas: não escreva bastidor (ferramenta que falhou, como leu o PDF) no conteúdo. "
            + "ESCRITA: um conceito por slide, frases curtas, no máximo 5 tópicos seguidos; destaque em caixas "
            + "\":::regra Título\", \":::pegadinha\", \":::exemplo Título\" ou \":::dica\" fechadas com \":::\". "
            + "TODO exemplo é concreto: mostre os DADOS de entrada (tabelas pequenas, 3 a 4 linhas, com nomes do "
            + "contexto do concurso, ex.: contribuinte e debitos) e o RESULTADO, lado a lado com \"+++\" entre as "
            + "colunas da caixa; nunca use nome solto (esq, dir, df, x) sem mostrar o que tem dentro. Explique a regra "
            + "a partir do exemplo, não o contrário. "
            + "CONCEITOS: todo slide declara o conceito que ensina no fim do título, \"=== Desbloqueio de RP "
            + "{#rp-desbloqueio}\" (minúsculas, números e hífen); uma caixa pode declarar um conceito próprio, "
            + "\":::pegadinha Prazo {#rp-prazo}\". Cada questão de fixação e cada pergunta de quiz da aula diz em "
            + "'conceitos' o(s) id(s) que cobra; o sistema recusa conceito que não está na aula. "
            + "CADA CONCEITO TEM: definição curta, o porquê da regra, um exemplo com dados e a pegadinha de prova; "
            + "nenhuma regra solta, sem o caso que a mostra. "
            + "DADOS DIFERENTES: a questão de fixação não reaproveita os valores, os nomes de entidade nem a mesma "
            + "estrutura de dados do exemplo do slide (o aluno tem de aplicar a regra a um caso novo, não "
            + "reconhecer o exemplo). O sistema recusa a questão que repete mais de "
            + com.pdf2questao.aula.DataOverlap.MAX_SHARED + " valor do slide do conceito (0, 1, 2, 100 e números "
            + "de artigo/lei não contam). "
            + "CÁLCULO (Matemática Financeira, Contabilidade, Economia, Estatística): cartão de fórmula em "
            + "```formula {#id} com JSON {titulo, formula (TeX), simbolos: [{simbolo, significado, unidade}], "
            + "quandoUsar, pegadinha}; exercício guiado em ```calculo com JSON {titulo, conceito, enunciado (com {var}), "
            + "variaveis: {var: {min, max, passo}}, restricoes: [\"expr\"], exemplo: {var: valor usado no slide}, "
            + "etapas: [{titulo, pergunta, resposta: \"expr\", unidade, tolerancia, dica, errosTipicos: [{resposta: "
            + "\"expr\", mensagem}]}], resposta: \"expr\", interpretacao}. Expressões: + - * / ^ %, parênteses, ln, "
            + "log10, exp, sqrt, abs, round(x, casas), min, max; a etapa k usa as variáveis e e1..e(k-1). Etapas "
            + "típicas: identificar dados, converter a taxa, montar a fórmula, calcular, interpretar; cada erro típico "
            + "é a conta errada que o aluno costuma fazer (ex.: taxa mensal com prazo em anos). Na criação o gerador "
            + "roda 40 vezes: a resposta tem de fechar com a última etapa e os números nunca repetem o exemplo. "
            + "Exercício guiado e tarefa de código contam como questão de fixação. "
            + "CÓDIGO (SQL, pandas, PySpark): tabelas pequenas em \":::dados nome\" (CSV, fechado com \":::\"; no "
            + "SQL vira a tabela nome, no Python o arquivo dados/nome.csv); exemplo executável em ```sql run, "
            + "```python run ou ```pyspark run, seguido de ```saida com a saída exata (o valor da última linha é "
            + "mostrado, como no Jupyter; SQL: cabeçalho e linhas com | entre as colunas). Tarefa em ```tarefa "
            + "python|sql|pyspark com seções ### enunciado, ### codigo (inicial, que ainda não resolve), ### teste "
            + "(asserts Python) ou ### esperado (SQL: o resultado), ### dica e ### solucao. Na criação, o servidor "
            + "roda cada exemplo e cada solução (mesmo Pyodide/sql.js do navegador): a aula só é criada se rodar e "
            + "der a saída declarada. PySpark roda numa SIMULAÇÃO didática (pandas, sem JVM): show, count, collect, "
            + "explain, groupBy/agg, join, orderBy, cache/persist, contagem de jobs e stages; não use RDD API, UDF, "
            + "SQL em texto nem write. R não roda na aula (use bloco ``` comum).";

    @McpTool(name = "criar_aula",
            description = "Cria uma aula no sistema (Markdown: ## títulos, listas, **negrito**, `código`, blocos ``` , "
                    + "citações > e listas numeradas). Vincule à disciplina e ao tópico do edital; depois crie o quiz "
                    + "com criar_quiz. ANTES de escrever, confira o tópico no edital (ler_edital / estrutura_do_edital) "
                    + "e pesquise fontes atuais na web (norma vigente, súmulas, temas e jurisprudência, autores e "
                    + "conceitos). TEXTO LITERAL: baixe cada fonte (curl/pdftotext) e grave o texto de cada dispositivo "
                    + "com registrar_trecho antes de escrever; passe os ids em 'trechos' (o resumo do WebFetch não "
                    + "serve). 'fontes' lista só o que foi LIDO, com o link de cada um; o que não foi confirmado vai "
                    + "em 'naoConfirmado', e então 'tentativas' (o que você fez para confirmar: outras fontes, "
                    + "download e extração do texto) é obrigatório. Se a fonte vier truncada ou em resumo, busque o "
                    + "texto por outro caminho antes de criar a aula. Tudo é anexado ao fim dela. " + SLIDES_HELP,
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public LessonCreated createLesson(
            @McpToolParam(description = "Título da aula.") String titulo,
            @McpToolParam(description = "Disciplina (como no perfil do edital; use o mesmo nome das aulas já existentes, veja listar_aulas).") String disciplina,
            @McpToolParam(description = "Tópico do conteúdo programático do edital.") String topicoEdital,
            @McpToolParam(description = "Conteúdo da aula em Markdown.") String conteudo,
            @McpToolParam(description = "Fontes conferidas para esta aula, em Markdown (uma por linha), SEMPRE com "
                    + "o link: [título](https://...) para cada página da web, dispositivo de lei, súmula ou tema "
                    + "verificado, e o item do edital lido (com o nome do arquivo ou o link), com a data da "
                    + "conferência. Só o que foi LIDO de fato; \"de memória\", \"não consegui ler\" ou \"só resumo\" "
                    + "não entram aqui (use naoConfirmado). Obrigatório.") String fontes,
            @McpToolParam(description = "Id da tarefa do cronograma que a aula cobre (a aula fica no concurso da "
                    + "tarefa).", required = false) Long tarefaId,
            @McpToolParam(description = McpConcurso.PARAM + " Ignorado se a tarefa já é de um concurso.",
                    required = false) String concurso,
            @McpToolParam(description = "O que NÃO foi possível confirmar na fonte (um item por linha). Exige "
                    + "'tentativas'.", required = false) String naoConfirmado,
            @McpToolParam(description = "O que foi tentado para confirmar o que está em naoConfirmado (outros sites, "
                    + "download e extração do texto, leitura em partes) e por que não deu certo.",
                    required = false) String tentativas,
            @McpToolParam(description = "Questões de fixação citadas no conteúdo como [[questão N]] (aula em slides).",
                    required = false) List<QuizItem> perguntasDaAula,
            @McpToolParam(description = "Com perguntasDaAula: como a banca cobra o tópico, observado nas questões "
                    + "reais lidas (mínimo 300 caracteres).", required = false) String estiloDaBanca,
            @McpToolParam(description = "Com perguntasDaAula: questões reais lidas como modelo, uma por linha "
                    + "(mínimo 2).", required = false) String referencias,
            @McpToolParam(description = "Com perguntasDaAula: multipla_escolha (padrão) ou certo_errado.",
                    required = false) String formato,
            @McpToolParam(description = "Ids dos trechos LITERAIS gravados com registrar_trecho (mínimo 1): todo "
                    + "artigo citado na aula precisa estar num deles (ou em naoConfirmado).") List<Long> trechos) {
        return createLesson(titulo, disciplina, topicoEdital, conteudo, fontes, tarefaId, concurso, naoConfirmado,
                tentativas, perguntasDaAula, estiloDaBanca, referencias, formato, trechos, true);
    }

    /** Sem trechos (chamadas antigas e testes): so confere os artigos citados. */
    public LessonCreated createLesson(String titulo, String disciplina, String topicoEdital, String conteudo,
                                      String fontes, Long tarefaId, String concurso, String naoConfirmado,
                                      String tentativas, List<QuizItem> perguntasDaAula, String estiloDaBanca,
                                      String referencias, String formato) {
        return createLesson(titulo, disciplina, topicoEdital, conteudo, fontes, tarefaId, concurso, naoConfirmado,
                tentativas, perguntasDaAula, estiloDaBanca, referencias, formato, null, false);
    }

    private LessonCreated createLesson(String titulo, String disciplina, String topicoEdital, String conteudo,
                                       String fontes, Long tarefaId, String concurso, String naoConfirmado,
                                       String tentativas, List<QuizItem> perguntasDaAula, String estiloDaBanca,
                                       String referencias, String formato, List<Long> trechos, boolean requireExcerpts) {
        List<com.pdf2questao.aula.SourceExcerpt> excerpts = checkExcerpts(conteudo, trechos, naoConfirmado,
                requireExcerpts);
        String content = withSources(conteudo, fontes, naoConfirmado, tentativas, excerpts);
        Lesson lesson = mcpConcurso.in(concurso, () -> lessonService.createWithFixation(titulo, disciplina,
                topicoEdital, content, tarefaId, toNew(perguntasDaAula), formato, estiloDaBanca, referencias));
        lessonService.linkExcerpts(lesson.getId(), trechos);
        return lessonCreated(lesson);
    }

    private List<com.pdf2questao.aula.SourceExcerpt> checkExcerpts(String conteudo, List<Long> trechos,
                                                                  String naoConfirmado, boolean required) {
        if (required && (trechos == null || trechos.isEmpty())) {
            throw new IllegalArgumentException("Informe 'trechos': antes de escrever, baixe cada fonte (Bash: curl "
                    + "do HTML oficial, ou curl + pdftotext do PDF), recorte o texto LITERAL de cada dispositivo ou "
                    + "seção que a aula usa e grave com registrar_trecho. O texto devolvido pelo WebFetch é um resumo "
                    + "e não serve de fonte.");
        }
        List<com.pdf2questao.aula.SourceExcerpt> excerpts = lessonService.excerpts(trechos);
        List<String> missing = LessonService.articlesWithoutExcerpt(conteudo, excerpts, naoConfirmado);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("A aula cita " + String.join(", ", missing) + " sem trecho literal "
                    + "gravado daquela norma. Grave o texto do artigo (com o cabeçalho \"Art. N.\") com "
                    + "registrar_trecho, dizendo a norma no 'dispositivo' (ex.: \"LRF, art. 42\"), e inclua o id em "
                    + "'trechos'; mencionar o artigo dentro de outro trecho não conta. Cite sempre com a norma "
                    + "(\"art. 42 da LRF\") quando o slide tratar de mais de uma lei. Se não conseguiu o texto depois "
                    + "de tentar, ponha o dispositivo em 'naoConfirmado'.");
        }
        return excerpts;
    }

    @McpTool(name = "reescrever_aula",
            description = "Reescreve o conteúdo de uma aula já criada (os quizzes dela continuam). Mesmas regras de "
                    + "criar_aula para fontes, naoConfirmado e tentativas. " + SLIDES_HELP + " Enviar perguntasDaAula "
                    + "substitui as questões de fixação anteriores (e as respostas delas); se a aula tem erros em "
                    + "quizzes anteriores, retome-os em errosRetomados.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false))
    public LessonCreated rewriteLesson(
            @McpToolParam(description = "Id da aula.") Long aulaId,
            @McpToolParam(description = "Novo título (vazio = mantém).", required = false) String titulo,
            @McpToolParam(description = "Novo conteúdo em Markdown.") String conteudo,
            @McpToolParam(description = "Fontes conferidas, com o link de cada uma (como em criar_aula).") String fontes,
            @McpToolParam(description = "O que não foi confirmado (exige 'tentativas').", required = false)
            String naoConfirmado,
            @McpToolParam(description = "O que foi tentado para confirmar.", required = false) String tentativas,
            @McpToolParam(description = "Questões de fixação citadas como [[questão N]].", required = false)
            List<QuizItem> perguntasDaAula,
            @McpToolParam(description = "Como a banca cobra o tópico (mínimo 300 caracteres).", required = false)
            String estiloDaBanca,
            @McpToolParam(description = "Questões reais lidas como modelo, uma por linha (mínimo 2).", required = false)
            String referencias,
            @McpToolParam(description = "multipla_escolha (padrão) ou certo_errado.", required = false) String formato,
            @McpToolParam(description = "Ids das perguntas erradas (erros_quiz_anteriores da aula) que a fixação "
                    + "retoma.", required = false) List<Long> errosRetomados,
            @McpToolParam(description = "Ids dos trechos literais (registrar_trecho), como em criar_aula.")
            List<Long> trechos) {
        return rewriteLesson(aulaId, titulo, conteudo, fontes, naoConfirmado, tentativas, perguntasDaAula,
                estiloDaBanca, referencias, formato, errosRetomados, trechos, true);
    }

    /** Sem trechos (chamadas antigas e testes). */
    public LessonCreated rewriteLesson(Long aulaId, String titulo, String conteudo, String fontes, String naoConfirmado,
                                       String tentativas, List<QuizItem> perguntasDaAula, String estiloDaBanca,
                                       String referencias, String formato, List<Long> errosRetomados) {
        return rewriteLesson(aulaId, titulo, conteudo, fontes, naoConfirmado, tentativas, perguntasDaAula,
                estiloDaBanca, referencias, formato, errosRetomados, null, false);
    }

    private LessonCreated rewriteLesson(Long aulaId, String titulo, String conteudo, String fontes, String naoConfirmado,
                                        String tentativas, List<QuizItem> perguntasDaAula, String estiloDaBanca,
                                        String referencias, String formato, List<Long> errosRetomados,
                                        List<Long> trechos, boolean requireExcerpts) {
        List<com.pdf2questao.aula.SourceExcerpt> excerpts = checkExcerpts(conteudo, trechos, naoConfirmado,
                requireExcerpts);
        String content = withSources(conteudo, fontes, naoConfirmado, tentativas, excerpts);
        Lesson lesson = lessonService.rewriteWithFixation(aulaId, titulo, content, toNew(perguntasDaAula), formato,
                estiloDaBanca, referencias, errosRetomados);
        lessonService.linkExcerpts(lesson.getId(), trechos);
        return lessonCreated(lesson);
    }

    public record ExcerptSaved(Long trechoId, String dispositivo, int caracteres, String sha256) {
    }

    @McpTool(name = "registrar_trecho",
            description = "Grava o texto LITERAL de um dispositivo de lei/norma ou de uma seção de documentação, ANTES "
                    + "de escrever a aula. Colete assim: (1) baixe a fonte oficial com Bash (curl -sL URL; PDF: curl "
                    + "-sLo f.pdf URL && pdftotext -layout f.pdf -); (2) recorte o trecho de cada dispositivo que a aula "
                    + "vai usar (artigo inteiro com parágrafos e incisos; na documentação, a seção inteira); (3) grave "
                    + "aqui, um trecho por dispositivo. Leia várias páginas/seções, não só a primeira. NÃO use o texto "
                    + "do WebFetch: ele devolve um resumo, não o texto. Depois passe os ids em 'trechos' de "
                    + "criar_aula/reescrever_aula; os trechos aparecem no slide de Fontes.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    public ExcerptSaved registerExcerpt(
            @McpToolParam(description = "URL de onde o texto foi baixado (fonte oficial).") String url,
            @McpToolParam(description = "Dispositivo ou seção, SEMPRE com a norma quando for lei (ex.: \"LRF, art. "
                    + "42\", \"Lei 4.320, art. 36\", \"CF, art. 165\", \"PySpark: DataFrame.persist\"). Um trecho "
                    + "por artigo, com o cabeçalho \"Art. N.\" do texto.") String dispositivo,
            @McpToolParam(description = "Texto literal, copiado do arquivo baixado (sem resumir nem reescrever).")
            String texto,
            @McpToolParam(description = "Como foi obtido (ex.: \"curl do HTML do Planalto, recorte do art. 42\").")
            String comoObtido) {
        var e = lessonService.registerExcerpt(url, dispositivo, texto, comoObtido);
        return new ExcerptSaved(e.getId(), e.getDispositivo(), e.getTexto().length(), e.getSha256());
    }

    private static List<NewQuestion> toNew(List<QuizItem> items) {
        return items == null ? null : items.stream().map(QuizItem::toNew).toList();
    }

    private LessonCreated lessonCreated(Lesson lesson) {
        Quiz fixation = lessonService.fixation(lesson.getId());
        return new LessonCreated(lesson.getId(), lesson.getTitle(), mcpConcurso.name(lesson.getConcursoId()),
                com.pdf2questao.aula.LessonSlides.parse(lesson.getContent()).size(),
                fixation == null ? 0 : lessonService.questions(fixation.getId()).size(),
                baseUrl + "/aulas/" + lesson.getId());
    }

    /** Valida as fontes e as anexa ao fim (em aula de slides, como o ultimo slide). */
    private static String withSources(String conteudo, String fontes, String naoConfirmado, String tentativas,
                                      List<com.pdf2questao.aula.SourceExcerpt> excerpts) {
        if (McpText.blank(fontes)) {
            throw new IllegalArgumentException("Informe 'fontes': confira o tópico no edital (ler_edital) e pesquise "
                    + "fontes atuais na web antes de criar a aula, e liste o que foi conferido e o que não foi.");
        }
        if (!SOURCE_LINK.matcher(fontes).find()) {
            throw new IllegalArgumentException("Cada fonte em 'fontes' precisa do link (https://...): use "
                    + "[título](https://...) para as páginas consultadas.");
        }
        if (UNVERIFIED.matcher(fontes).find()) {
            throw new IllegalArgumentException("'fontes' só aceita o que foi lido de fato. Se algo foi de memória, "
                    + "truncado ou só em resumo, tente antes outro caminho (outro site, baixar e extrair o texto, "
                    + "ler em partes) e, se não der, mova para 'naoConfirmado' com 'tentativas'.");
        }
        boolean hasUnconfirmed = !McpText.blank(naoConfirmado);
        if (hasUnconfirmed && (McpText.blank(tentativas) || tentativas.strip().length() < MIN_ATTEMPTS)) {
            throw new IllegalArgumentException("Para deixar algo em 'naoConfirmado', descreva em 'tentativas' o que "
                    + "você tentou para confirmar (outras fontes, baixar o arquivo e extrair o texto, leitura em "
                    + "partes) e por que não deu certo.");
        }
        boolean slides = com.pdf2questao.aula.LessonSlides.isSlides(conteudo);
        StringBuilder literal = new StringBuilder();
        if (excerpts != null && !excerpts.isEmpty()) {
            literal.append("\n### Trechos literais conferidos\n");
            for (var e : excerpts) {
                String text = e.getTexto().length() > MAX_EXCERPT_SHOWN
                        ? e.getTexto().substring(0, MAX_EXCERPT_SHOWN) + " […]" : e.getTexto();
                literal.append("\n**").append(e.getDispositivo()).append("** — [fonte](").append(e.getUrl()).append(")\n");
                text.lines().forEach(l -> literal.append("> ").append(l).append('\n'));
            }
        }
        return conteudo + (slides ? "\n\n=== Fontes\n" : "\n\n---\n\n## Fontes conferidas\n") + fontes.strip() + "\n"
                + literal
                + (hasUnconfirmed ? "\n### Não confirmado\n" + naoConfirmado.strip() + "\n\n### O que foi tentado\n"
                + tentativas.strip() + "\n" : "");
    }

    @McpPrompt(name = "novo_quiz", title = "Novo quiz",
            description = "Cria um quiz (de uma aula ou avulso) no nível pedido, trabalhando as dúvidas do usuário e "
                    + "retomando os erros dos quizzes anteriores do assunto.")
    public String newQuizPrompt(
            @McpArg(name = "assunto", description = "Id da aula, ou disciplina/tópico do quiz.", required = true) String assunto,
            @McpArg(name = "nivel", description = "basico, intermediario, avancado ou prova (padrão: prova).", required = false) String nivel,
            @McpArg(name = "duvidas", description = "Dúvidas que o quiz deve trabalhar.", required = false) String duvidas) {
        return """
                Crie um quiz novo no pdf2questao sobre: %s. Nível: %s. %s

                1. Chame erros_quiz_anteriores para o mesmo assunto (aulaId, ou disciplina/tópico) e leia cada erro: o \
                que o usuário marcou e por quê estava errado. Veja também desempenho_quiz e perguntas_ja_feitas \
                (o sistema recusa pergunta repetida: cubra pontos ainda não perguntados).
                2. Se for de uma aula, leia o conteúdo dela (listar_aulas) e cobertura_aula: cada pergunta cobra \
                um conceito {#id} que está nos slides (informe em 'conceitos'); o que a aula não ensinou só entra \
                com ampliacao=true. Não reaproveite números, nomes nem a estrutura dos exemplos dos slides. Confira na web os pontos de norma, \
                súmula, tema ou jurisprudência que o quiz vai cobrar (se a fonte vier truncada ou em resumo, baixe e \\
                extraia o texto ou ache outra fonte antes de desistir); se for avulso, leia o tópico no edital \
                (ler_edital), pesquise fontes atuais na web e veja como a banca cobra (exemplos_da_banca). Não \
                cobre o que não conseguiu confirmar.
                3. PESQUISA DA BANCA (nível prova, o padrão). Antes de escrever, ache questões REAIS da banca do \
                concurso sobre o tópico: provas_da_banca e exemplos_da_banca no sistema e, na internet, provas e \
                gabaritos da mesma banca (de preferência mesma área e últimos anos; PCI Concursos, sites de \
                questões, PDFs oficiais). Leia pelo menos 3 questões inteiras do tópico e anote o que OBSERVOU (sem \
                inventar): tamanho e contextualização do enunciado, comando ("é correto afirmar", "EXCETO", I-II-III, \
                caso concreto, cálculo), como são os distratores (troca de prazo, de competência, de exceção, de \
                termo parecido) e as pegadinhas recorrentes. Isso vai em estiloDaBanca; as questões lidas, em \
                referencias.
                4. Escreva de 6 a 10 perguntas: uma ou mais para cada erro anterior (com outro enunciado, cobrando o \
                mesmo ponto), as dúvidas do usuário, e o restante no nível pedido (basico = conceito; \
                intermediario = aplicação; avancado = casos e exceções; prova = como a banca cobra). No nível prova, \
                cada pergunta segue uma questão real lida como modelo de estrutura, profundidade e distratores (sem \
                copiar nem parafrasear): nada de "o que é X" nem definição solta; cobre aplicação a caso concreto, \
                distinção entre conceitos vizinhos, exceção, prazo/percentual/competência e a assertiva incorreta; \
                pelo menos metade com afirmativas I-II-III, "EXCETO" ou cenário. Alternativas plausíveis, do mesmo \
                tamanho e estrutura, com distratores tirados dos erros reais do usuário e das pegadinhas da banca; \
                a correta não pode ser a mais longa nem a única com detalhe, e varie a letra do gabarito. \
                Toda alternativa com a explicação de por que está certa ou errada.
                5. TESTE DO CHUTE. Antes de gravar, releia cada pergunta como um candidato que NÃO estudou: se der \
                para acertar por eliminação de absurdos, pela alternativa mais longa ou mais completa, por palavras \
                como "sempre"/"nunca" só nos distratores, ou se o enunciado entrega a resposta, reescreva. Se fosse \
                fácil numa prova da banca, está fácil demais.
                6. Grave com criar_quiz (nivel, estiloDaBanca, referencias) informando em errosRetomados os ids das \
                perguntas erradas que o quiz retoma. Passe o link ao usuário e não mostre as respostas na conversa.
                """.formatted(assunto, McpText.blank(nivel) ? "prova" : nivel,
                McpText.blank(duvidas) ? "" : "Dúvidas do usuário: " + duvidas + ".");
    }

    @McpPrompt(name = "revisao_do_dia", title = "Revisão do dia",
            description = "Monta o quiz de revisão dos assuntos que vencem hoje, difícil e sem repetir pergunta já feita, "
                    + "e depois marca as revisões conforme o desempenho.")
    public String reviewPrompt(
            @McpArg(name = "concurso", description = "Concurso da revisão (id ou parte do nome; vazio = o ativo).",
                    required = false) String concurso) {
        String c = McpText.blank(concurso) ? "" : " Passe concurso=\"" + concurso + "\" em todas as ferramentas.";
        return """
                Monte a revisão do dia do usuário no pdf2questao.%s Revisão não é reler a aula: é testar se o que foi \
                estudado ainda se sustenta, e tem que ser MAIS DIFÍCIL que o quiz da aula. E é FOCADA: poucas \
                disciplinas por dia, atacando os pontos fracos, em vez de um pouco de tudo.

                1. FOCO. Chame revisoes_de_hoje. Ele já escolheu as disciplinas do dia (as mais fracas nos quizzes, \
                2 por dia, 3 no domingo) e traz, em cada uma, os assuntos vencidos e os conceitosFracos (o que ele \
                mais erra, com aulaId). Revise SÓ essas disciplinas; o que vier em 'aguardando' fica para os próximos \
                dias, não entra hoje. Use a etapa: quanto maior, mais difícil. Para cada disciplina do foco chame \
                erros_quiz_anteriores, desempenho_quiz e perguntas_ja_feitas.

                PONTOS FRACOS PRIMEIRO. Pelo menos 60%% das perguntas de cada disciplina cobrem os conceitosFracos e \
                os erros_quiz_anteriores dela (outro ângulo, outro cenário, nunca a mesma pergunta). O restante cobre \
                os assuntos vencidos que ainda não têm erro registrado.

                2. NÃO REPITA. O sistema recusa pergunta já feita no concurso (outro quiz ou revisão anterior), \
                inclusive reescrita de leve. Não mude só as palavras: cubra um ponto ainda não perguntado, ou o mesmo \
                ponto errado por outro ângulo (outro cenário, outra pegadinha), sempre com outro enunciado.

                3. DIFICULDADE. Nível "prova", nunca "basico". Nada de definição solta ou "o que é X". Cada pergunta \
                precisa exigir aplicar a regra a um caso concreto, distinguir dois conceitos parecidos, achar a exceção ou \
                a assertiva incorreta (use "exceto", "apenas" e I-II-III). Pelo menos metade em afirmativas I-II-III ou \
                cenário com cálculo/aplicação. Todas as alternativas plausíveis e do mesmo tamanho e estrutura; os \
                distratores são os erros reais do usuário e as pegadinhas da banca (exemplos_da_banca), não absurdos. \
                A correta não pode ser a mais longa nem a única com detalhe. Priorize o que ele errou e os tópicos de \
                menor acerto; o que ele acerta sempre entra só em versão mais difícil.

                4. FONTE. Baseie-se no conteúdo das aulas (listar_aulas com aulaId) e em questões reais; confira na web \
                o que for norma ou jurisprudência. Não cobre o que não confirmou. Toda alternativa com a explicação de \
                por que está certa ou errada.

                5. FLASHCARDS. Poucos e certeiros: no máximo 6 por disciplina. Chame flashcards_da_revisao e escolha \
                primeiro os cartões dos conceitosFracos, depois os marcados errei/dificil, depois os nunca vistos \
                dos assuntos vencidos. Não inclua cartão de conceito que ele acerta sempre. Conceito fraco sem \
                cartão: crie com criar_flashcards (lacuna para prazos e percentuais, contraste para o que ele \
                confunde). Os cartões aparecem antes das questões, e as questões dos conceitos que ele marcar como \
                difíceis sobem para o começo: escreva perguntas que cubram os conceitos dos cartões, com \
                'conceitos' quando o quiz for de uma aula.

                6. GRAVAR. Um quiz por disciplina do foco (assim o sistema retoma os erros dela; informe \
                errosRetomados) e com tarefaId = tarefaRevisaoId de revisoes_de_hoje (assim a tarefa de \
                revisão da tela Hoje mostra "Começar revisão"), de 8 a 12 perguntas no total da disciplina, nível "prova" (com estiloDaBanca e \
                referencias: as questões reais da banca que você leu como modelo). Grave a disciplina real na \
                questão (nunca "Revisão" ou "Misto"): é ela que alimenta a medida de pontos fracos. Passe em \
                'flashcards' os ids escolhidos. Passe o link e não mostre as respostas.

                7. DEPOIS. Leia resultado_quiz. Para cada assunto, marcar_revisao com resultado: "bom" se acertou \
                85%% ou mais das perguntas daquele assunto, "dificil" de 70%% a 84%%, "errei" abaixo de 70%% (volta \
                amanhã). A autoavaliação dos flashcards não aprova a revisão, só aponta o que ele sente que não \
                sabe. Explique em poucas linhas o que errou, quais conceitos fracos melhoraram ou continuam fracos, \
                e compare com os cartões (marcou fácil e errou a questão = falsa segurança, vale citar).
                """.formatted(c);
    }

    public record QuizAlternative(
            @McpToolParam(description = "Texto da alternativa. Aceita ⟦u⟧sublinhado⟦/u⟧, ⟦b⟧negrito⟦/b⟧ e ⟦i⟧itálico⟦/i⟧.") String texto,
            @McpToolParam(description = "Por que está certa ou errada (mostrado ao clicar).") String explicacao) {
    }

    public record QuizItem(
            @McpToolParam(description = "Enunciado. Para destacar um trecho (a prova sublinha o termo analisado), envolva-o "
                    + "em ⟦u⟧...⟦/u⟧ (sublinhado), ⟦b⟧...⟦/b⟧ (negrito) ou ⟦i⟧...⟦/i⟧ (itálico): a tela mostra o destaque. "
                    + "Não escreva \"destacado\" ou \"sublinhado\" sem marcar o trecho.") String enunciado,
            @McpToolParam(description = "Tópico cobrado (para o desempenho por tópico).", required = false) String topico,
            @McpToolParam(description = "Alternativas na ordem A, B, C...") List<QuizAlternative> alternativas,
            @McpToolParam(description = "Letra correta (no certo_errado: \"C\" ou \"E\").") String correta,
            @McpToolParam(description = "Quiz de aula: id(s) do conceito da aula que a pergunta cobra (o {#id} do "
                    + "slide ou da caixa, ex.: [\"rp-desbloqueio\"]). Obrigatório quando a aula declara conceitos; "
                    + "conceito que não está na aula é recusado.", required = false) List<String> conceitos,
            @McpToolParam(description = "true = a pergunta vai além da aula de propósito (dispensa conceito da "
                    + "aula; aparece como \"além da aula\" no resultado). Não vale para questão de fixação.",
                    required = false) Boolean ampliacao) {

        public QuizItem(String enunciado, String topico, List<QuizAlternative> alternativas, String correta) {
            this(enunciado, topico, alternativas, correta, null, null);
        }

        NewQuestion toNew() {
            return new NewQuestion(enunciado, topico,
                    alternativas == null ? null : alternativas.stream().map(QuizAlternative::texto).toList(),
                    alternativas == null ? null : alternativas.stream().map(QuizAlternative::explicacao).toList(),
                    correta, conceitos, Boolean.TRUE.equals(ampliacao));
        }
    }

    @McpTool(name = "erros_quiz_anteriores",
            description = "Perguntas que o usuário errou (última resposta) nos quizzes anteriores do assunto: da aula, "
                    + "ou da disciplina/tópico. Traz o que ele marcou, por que estava errado e a correta. Chame SEMPRE "
                    + "antes de criar um quiz e retome esses pontos.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public List<PreviousError> previousErrors(
            @McpToolParam(description = "Id da aula.", required = false) Long aulaId,
            @McpToolParam(description = "Disciplina (parte do nome).", required = false) String disciplina,
            @McpToolParam(description = "Tópico (parte do nome).", required = false) String topico) {
        return lessonService.previousErrors(aulaId, disciplina, topico, null).stream().limit(30).toList();
    }

    @McpTool(name = "perguntas_ja_feitas",
            description = "Perguntas que o usuário já recebeu nos quizzes e revisões do concurso (enunciado resumido, "
                    + "quiz, tópico e se acertou na última resposta), as mais recentes primeiro. Chame ANTES de criar "
                    + "um quiz, principalmente de revisão: o sistema recusa pergunta repetida (mesmo enunciado ou "
                    + "quase), então escolha pontos ainda não cobertos ou outro ângulo do que ele errou.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public List<AskedQuestion> askedQuestions(
            @McpToolParam(description = "Id da aula.", required = false) Long aulaId,
            @McpToolParam(description = "Disciplina (parte do nome).", required = false) String disciplina,
            @McpToolParam(description = "Tópico (parte do nome).", required = false) String topico,
            @McpToolParam(description = "Quantas trazer (padrão 60, máximo 200).", required = false) Integer limite,
            @McpToolParam(description = McpConcurso.PARAM, required = false) String concurso) {
        int max = limite == null ? 60 : Math.min(Math.max(limite, 1), 200);
        return mcpConcurso.in(concurso, () -> lessonService.askedQuestions(aulaId, disciplina, topico, max));
    }

    public record QuizCreated(Long quizId, String titulo, Long aulaId, int perguntas, int errosRetomados, String link) {
    }

    @McpTool(name = "criar_quiz",
            description = "Cria um quiz novo: de uma aula (aulaId; uma aula pode ter vários quizzes) ou avulso "
                    + "(disciplina/tópico). Nível: basico, intermediario, avancado ou prova (padrão: prova). No nível prova, "
                    + "pesquise antes como a banca cobra o tópico (questões reais na internet, provas_da_banca e "
                    + "exemplos_da_banca), descreva em estiloDaBanca o que observou e liste em referencias as "
                    + "questões reais lidas (mínimo 2); cada pergunta segue uma delas como modelo de estrutura, "
                    + "profundidade e distratores, sem copiar. Lotes de 4+ com a correta sempre a mais longa ou o "
                    + "gabarito todo na mesma letra são recusados. Se houver erros em "
                    + "quizzes anteriores do assunto, é obrigatório retomá-los: leia erros_quiz_anteriores e informe os "
                    + "ids em errosRetomados. Pergunta repetida (já feita no concurso, em qualquer quiz ou revisão) é "
                    + "recusada: veja perguntas_ja_feitas antes. QUIZ DE AULA: o quiz nasce depois da aula e só cobra "
                    + "o que ela ensinou; cada pergunta diz em 'conceitos' o {#id} do slide/caixa que cobra (veja "
                    + "cobertura_aula), e conceito que não está na aula é recusado. Para ir além de propósito, marque "
                    + "ampliacao=true (o resultado lista essas perguntas e sugere incluí-las na aula). Não reaproveite "
                    + "os números, nomes e a estrutura de dados do exemplo do slide (recusado se repetir mais de um "
                    + "valor). Baseie as perguntas na aula e, em quiz avulso, "
                    + "confira antes o edital e "
                    + "fontes atuais na web. Não cobre o que não foi confirmado nem o que só estava na memória: "
                    + "pergunta sem fonte lida não entra no quiz. "
                    + "Cada alternativa precisa da explicação de por que está certa ou errada. "
                    + "Formato certo_errado (Cebraspe): cada pergunta é um item afirmativo; envie 2 alternativas na "
                    + "ordem Certo, Errado (o texto é ignorado; vale a explicação) e correta \"C\" ou \"E\". "
                    + "Na tela, cada erro anula um acerto.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public QuizCreated createQuiz(
            @McpToolParam(description = "Id da aula (vazio = quiz avulso).", required = false) Long aulaId,
            @McpToolParam(description = "Título (vazio = \"Quiz N\").", required = false) String titulo,
            @McpToolParam(description = "Disciplina (obrigatória no avulso, se não houver tópico).", required = false)
            String disciplina,
            @McpToolParam(description = "Tópico do edital.", required = false) String topico,
            @McpToolParam(description = "Nível pedido (vazio = prova).", required = false) String nivel,
            @McpToolParam(description = "Dúvidas do usuário que o quiz trabalha.", required = false) String duvidas,
            @McpToolParam(description = "Ids das perguntas erradas (erros_quiz_anteriores) que este quiz retoma.",
                    required = false) List<Long> errosRetomados,
            @McpToolParam(description = "Perguntas.") List<QuizItem> perguntas,
            @McpToolParam(description = "Id da tarefa do cronograma que o quiz cobre (vazio = a da aula; na revisão, a "
                    + "tarefa de revisão de hoje: tarefaRevisaoId de revisoes_de_hoje).",
                    required = false) Long tarefaId,
            @McpToolParam(description = McpConcurso.PARAM + " Vazio também herda o da aula/tarefa.", required = false)
            String concurso,
            @McpToolParam(description = "multipla_escolha (padrão) ou certo_errado (itens C/E, estilo Cebraspe).",
                    required = false) String formato,
            @McpToolParam(description = "Nível prova (obrigatório): como a banca cobra este tópico, observado nas "
                    + "questões reais lidas: formato e tamanho dos enunciados, comandos (EXCETO, I-II-III, caso "
                    + "concreto, cálculo), tipo de distrator e pegadinhas recorrentes. Mínimo 300 caracteres.",
                    required = false) String estiloDaBanca,
            @McpToolParam(description = "Nível prova (obrigatório): questões reais da banca lidas como modelo, uma "
                    + "por linha (link da questão/prova ou \"questão local #id\"). Mínimo 2.",
                    required = false) String referencias,
            @McpToolParam(description = "Revisão: ids dos flashcards (flashcards_da_revisao) que aparecem ANTES das "
                    + "questões, na mesma tela. As questões dos conceitos que o usuário marcar como errei/difícil "
                    + "sobem para o começo.", required = false) List<Long> flashcards) {
        // Quiz de uma aula cujo assunto esta no foco da revisao de hoje: e quiz de revisao, liga a tarefa do dia
        // (a fila "Hoje" mostra "Comecar revisao" e o andamento nela).
        Long task = tarefaId != null || aulaId == null ? tarefaId
                : spacedRepetition.openReviewFor(lessonService.find(aulaId).getTaskId(), java.time.LocalDate.now())
                .map(com.pdf2questao.study.StudyTask::getId).orElse(null);
        Quiz quiz = mcpConcurso.in(concurso, () -> lessonService.createQuiz(aulaId, titulo, disciplina, topico, nivel,
                duvidas, errosRetomados, perguntas == null ? null : perguntas.stream().map(QuizItem::toNew).toList(),
                task, formato, estiloDaBanca, referencias));
        flashcardService.attach(quiz, flashcards);
        return new QuizCreated(quiz.getId(), quiz.getTitle(), quiz.getLessonId(), lessonService.questions(quiz.getId()).size(),
                errosRetomados == null ? 0 : errosRetomados.size(), baseUrl + "/quizzes/" + quiz.getId());
    }

    /** Sem flashcards (chamadas antigas e testes). */
    public QuizCreated createQuiz(Long aulaId, String titulo, String disciplina, String topico, String nivel,
                                  String duvidas, List<Long> errosRetomados, List<QuizItem> perguntas, Long tarefaId,
                                  String concurso, String formato, String estiloDaBanca, String referencias) {
        return createQuiz(aulaId, titulo, disciplina, topico, nivel, duvidas, errosRetomados, perguntas, tarefaId,
                concurso, formato, estiloDaBanca, referencias, null);
    }

    public record FlashcardItem(
            @McpToolParam(description = "Id do conceito da aula ({#id} do slide ou caixa).") String conceito,
            @McpToolParam(description = "pergunta (padrão), lacuna (\"___\" na frente no lugar do que se lembra: "
                    + "prazo, percentual, competência) ou contraste (dois conceitos vizinhos lado a lado).",
                    required = false) String tipo,
            @McpToolParam(description = "Frente: pergunta de recordação, sem alternativas e sem pista da resposta.")
            String frente,
            @McpToolParam(description = "Verso: resposta curta (até 220 caracteres), com o item/artigo quando houver.")
            String verso) {
    }

    public record FlashcardsSaved(Long aulaId, int cartoes, List<String> conceitos, String link) {
    }

    @McpTool(name = "criar_flashcards",
            description = "Cria (ou substitui) os flashcards de uma aula, usados nas revisões ANTES das questões: "
                    + "recordação ativa, sem alternativas. Ao menos 1 cartão por conceito de slide de conteúdo (o "
                    + "resumo não precisa), no máximo 3 por conceito; frente sem pista da resposta; verso curto (até "
                    + "220 caracteres). Prefira 'lacuna' para prazos, percentuais, limites e competências (o que a "
                    + "banca troca nas alternativas) e 'contraste' para conceitos vizinhos que o usuário confunde "
                    + "(veja erros_quiz_anteriores). Crie logo depois de criar_aula; aulas antigas, na primeira "
                    + "revisão em que entrarem.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true))
    public FlashcardsSaved createFlashcards(@McpToolParam(description = "Id da aula.") Long aulaId,
                                            @McpToolParam(description = "Cartões.") List<FlashcardItem> cartoes) {
        var saved = flashcardService.replace(aulaId, cartoes == null ? null : cartoes.stream()
                .map(c -> new com.pdf2questao.flashcard.FlashcardService.NewCard(c.conceito(), c.tipo(), c.frente(),
                        c.verso())).toList());
        return new FlashcardsSaved(aulaId, saved.size(), saved.stream().map(f -> f.getConcept()).distinct().toList(),
                baseUrl + "/aulas/" + aulaId);
    }

    @McpTool(name = "flashcards_da_revisao",
            description = "Flashcards das aulas de uma disciplina (ou de uma aula), com a última autoavaliação do "
                    + "usuário em cada um (errei, dificil, facil) e as aulas que ainda não têm cartões. Use na revisão: "
                    + "escolha os cartões dos assuntos que vencem hoje (priorize errei/dificil e os nunca vistos), crie "
                    + "os que faltam com criar_flashcards e passe os ids em 'flashcards' de criar_quiz.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public com.pdf2questao.flashcard.FlashcardService.DueCards reviewFlashcards(
            @McpToolParam(description = "Id da aula.", required = false) Long aulaId,
            @McpToolParam(description = "Disciplina (parte do nome).", required = false) String disciplina,
            @McpToolParam(description = McpConcurso.PARAM, required = false) String concurso) {
        return mcpConcurso.in(concurso, () -> flashcardService.forReview(aulaId, disciplina));
    }

    public record TaskLinked(Long tarefaId, Long aulaId, Long quizId, String link) {
    }

    @McpTool(name = "vincular_tarefa",
            description = "Liga uma aula e/ou um quiz já criados a uma tarefa do cronograma (id de tarefas_de_hoje). "
                    + "Sem tarefaId, desfaz a ligação. A tarefa passa a mostrar a aula/quiz e o andamento.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    public TaskLinked linkTask(
            @McpToolParam(description = "Id da tarefa (vazio = desvincular).", required = false) Long tarefaId,
            @McpToolParam(description = "Id da aula.", required = false) Long aulaId,
            @McpToolParam(description = "Id do quiz.", required = false) Long quizId) {
        lessonService.linkTask(tarefaId, aulaId, quizId);
        return new TaskLinked(tarefaId, aulaId, quizId,
                tarefaId == null ? null : baseUrl + "/estudos/cronograma/" + tarefaId + "/editar");
    }

    public record QuizAdded(Long quizId, int perguntasNoQuiz, String link) {
    }

    @McpTool(name = "adicionar_quiz",
            description = "Acrescenta perguntas a um quiz já criado (criar_quiz). Cada alternativa precisa da "
                    + "explicação de por que está certa ou errada. Em quiz de aula, mesmas regras de criar_quiz para "
                    + "'conceitos', ampliacao e dados diferentes do exemplo do slide.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public QuizAdded addQuiz(
            @McpToolParam(description = "Id do quiz.") Long quizId,
            @McpToolParam(description = "Perguntas.") List<QuizItem> perguntas) {
        lessonService.addQuestions(quizId, perguntas == null ? null : perguntas.stream().map(QuizItem::toNew).toList());
        return new QuizAdded(quizId, lessonService.questions(quizId).size(), baseUrl + "/quizzes/" + quizId);
    }

    public record QuizFixed(Long quizId, int numero, String correta, int respostasRecorrigidas, String link) {
    }

    @McpTool(name = "corrigir_pergunta_quiz",
            description = "Corrige uma pergunta de quiz (número da pergunta no quiz; veja resultado_quiz): enunciado, "
                    + "tópico, alternativas com explicações e/ou a correta. O que não for enviado fica como está. Para "
                    + "trocar só as explicações, envie 'explicacoes' na ordem das alternativas. As respostas já dadas "
                    + "são recorrigidas pela nova correta. 'conceitos'/'ampliacao' trocam o que a pergunta cobra (use "
                    + "depois de reescrever_aula para trazer uma pergunta \"além da aula\" para dentro dela).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    public QuizFixed fixQuizQuestion(
            @McpToolParam(description = "Id do quiz.") Long quizId,
            @McpToolParam(description = "Número da pergunta no quiz (1 = primeira).") Integer numero,
            @McpToolParam(description = "Novo enunciado.", required = false) String enunciado,
            @McpToolParam(description = "Novo tópico.", required = false) String topico,
            @McpToolParam(description = "Novas alternativas (texto + explicação), substituindo todas.", required = false)
            List<QuizAlternative> alternativas,
            @McpToolParam(description = "Só as explicações novas, na ordem das alternativas atuais.", required = false)
            List<String> explicacoes,
            @McpToolParam(description = "Nova letra correta.", required = false) String correta,
            @McpToolParam(description = "Conceitos da aula que a pergunta cobra ({#id}), substituindo os atuais.",
                    required = false) List<String> conceitos,
            @McpToolParam(description = "true/false: marca ou desmarca a pergunta como além da aula.",
                    required = false) Boolean ampliacao) {
        var q = lessonService.fixQuestion(quizId, numero, enunciado, topico,
                alternativas == null ? null : alternativas.stream().map(QuizAlternative::texto).toList(),
                alternativas != null ? alternativas.stream().map(QuizAlternative::explicacao).toList() : explicacoes,
                correta, conceitos, ampliacao);
        int answered = (int) lessonService.result(quizId).questoes().stream()
                .filter(r -> r.perguntaId().equals(q.getId()) && r.suaResposta() != null).count();
        return new QuizFixed(quizId, q.getPosition(), q.getCorrectAnswer(), answered, baseUrl + "/quizzes/" + quizId);
    }

    /** Sem conceitos (chamadas antigas). */
    public QuizFixed fixQuizQuestion(Long quizId, Integer numero, String enunciado, String topico,
                                     List<QuizAlternative> alternativas, List<String> explicacoes, String correta) {
        return fixQuizQuestion(quizId, numero, enunciado, topico, alternativas, explicacoes, correta, null, null);
    }

    public record QuizDeleted(Long quizId, String titulo, int perguntas, int respostas) {
    }

    @McpTool(name = "excluir_quiz",
            description = "Apaga um quiz de vez: as perguntas e todas as respostas dadas (saem do desempenho, dos "
                    + "erros anteriores e de perguntas_ja_feitas). Não tem volta: confirme com o usuário antes e "
                    + "envie confirmar=true.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false))
    public QuizDeleted deleteQuiz(
            @McpToolParam(description = "Id do quiz.") Long quizId,
            @McpToolParam(description = "true depois que o usuário confirmou a exclusão.") Boolean confirmar) {
        if (!Boolean.TRUE.equals(confirmar)) {
            throw new IllegalArgumentException("Confirme com o usuário e envie confirmar=true para apagar o quiz "
                    + quizId + ".");
        }
        var d = lessonService.deleteQuiz(quizId);
        return new QuizDeleted(d.quizId(), d.title(), d.questions(), d.answers());
    }

    @McpTool(name = "resultado_quiz",
            description = "Resultado de um quiz (o que o usuário respondeu, acertos e a explicação da alternativa que "
                    + "ele marcou). Sem id, o quiz mais recente.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public QuizResult quizResult(
            @McpToolParam(description = "Id do quiz (vazio = o mais recente).", required = false) Long quizId) {
        Long id = quizId != null ? quizId : lessonService.allQuizzes().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Nenhum quiz ainda.")).getId();
        return lessonService.result(id);
    }

    public record QuizSummary(Long quizId, String titulo, String nivel, boolean concluido) {
    }

    public record LessonSummary(Long aulaId, String titulo, String disciplina, String topicoEdital, LocalDate criadaEm,
                                List<QuizSummary> quizzes, String conteudo, String link) {
    }

    @McpTool(name = "listar_aulas",
            description = "Aulas já criadas, as mais recentes primeiro, com os quizzes de cada uma. Com aulaId, traz "
                    + "também o conteúdo da aula (para criar um quiz baseado nela).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public List<LessonSummary> listLessons(
            @McpToolParam(description = "Id de uma aula (vazio = todas, sem conteúdo).", required = false) Long aulaId,
            @McpToolParam(description = McpConcurso.PARAM, required = false) String concurso) {
        return (aulaId != null ? lessonService.all() : mcpConcurso.in(concurso, lessonService::visible)).stream().filter(l -> aulaId == null || aulaId.equals(l.getId())).limit(30)
                .map(l -> new LessonSummary(l.getId(), l.getTitle(), l.getSubject(), l.getEditalTopic(),
                        l.getCreatedAt().toLocalDate(), lessonService.quizzes(l.getId()).stream()
                        .map(q -> new QuizSummary(q.getId(), q.getTitle(), q.getLevel(), q.getFinishedAt() != null)).toList(),
                        aulaId == null ? null : l.getContent(), baseUrl + "/aulas/" + l.getId())).toList();
    }

    @McpTool(name = "cobertura_aula",
            description = "Cobertura de uma aula pelos quizzes dela (fixação incluída): cada conceito {#id} com o slide "
                    + "e quantas perguntas o cobram, os conceitos sem nenhuma pergunta, as perguntas sem conceito "
                    + "(quizzes antigos) e as perguntas além da aula. Use antes de criar um quiz da aula (escolha os "
                    + "conceitos ainda não cobrados) e depois de resultado_quiz.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public LessonService.LessonCoverage lessonCoverage(@McpToolParam(description = "Id da aula.") Long aulaId) {
        return lessonService.coverage(aulaId);
    }

    public record QuizPerformance(Totals geral, List<TopicStat> porTopico) {
    }

    @McpTool(name = "desempenho_quiz",
            description = "Acertos x erros nos quizzes: geral e por disciplina/tópico (última resposta de cada "
                    + "pergunta), piores primeiro. Separado do desempenho em provas/simulados (desempenho_por_disciplina).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public QuizPerformance quizPerformance(
            @McpToolParam(description = McpConcurso.PARAM, required = false) String concurso) {
        return mcpConcurso.in(concurso, () -> new QuizPerformance(lessonService.totals(), lessonService.stats()));
    }
}
