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

    private final String baseUrl;

    public LessonMcpTools(LessonService lessonService, McpConcurso mcpConcurso, @Value("${server.port:8080}") int port) {
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

                1. PLANEJAMENTO. Consulte tarefas_de_hoje, revisoes_de_hoje, desempenho_quiz (quizzes, por tópico), \\
                resumo_de_erros e desempenho_por_disciplina (questões de prova/simulado). Escolha o tópico principal \\
                (tarefa do cronograma de hoje) e, se couber, um reforço do que o usuário vem errando. Confira o \\
                tópico no conteúdo programático do edital (estrutura_do_edital / ler_edital) e diga ao usuário, em \\
                3 a 5 linhas, o plano do dia e por quê.

                2. AULA. Antes de escrever, leia o item do edital (ler_edital), pesquise na web fontes atuais do \\
                tópico (norma vigente, súmulas, temas e jurisprudência, autores e conceitos; prefira fontes \\
                oficiais) e veja o que o usuário já anotou (buscar_paginas / ler_pagina) e como a \\
                banca cobra o tópico (exemplos_da_banca). Escreva a aula com criar_aula (tarefaId = a tarefa de \\
                hoje que ela cobre; em 'fontes', só o que você LEU, cada fonte com o link (URL)), no nível dele (use o \\
                desempenho): explicação direta, exemplos, "como a FCC cobra" (formatos e pegadinhas observados nas \\
                questões reais), quadro de diferenças entre conceitos que se confundem e um resumo final. Para lei \\
                ou norma, use a redação vigente confirmada na fonte oficial e cite o dispositivo.
                FONTE QUE NÃO ABRE OU VEM SÓ EM RESUMO. Se a página vier truncada, em resumo, ou o PDF vier ilegível, \\
                NÃO escreva a aula ainda: baixe o arquivo e extraia o texto (o WebFetch guarda PDFs em disco; use \\
                Bash com pdftotext ou curl), procure o texto em outro site (Câmara, Senado, LexML, normas.leg.br, \\
                documentação oficial do produto) e leia em partes (offset). Só dê algo por não confirmado depois de \\
                pelo menos duas tentativas diferentes, e então use 'naoConfirmado' e 'tentativas' em criar_aula. \\
                Nunca escreva da memória o que a fonte deveria dizer, nem leve isso para as perguntas do quiz.

                3. QUIZ. Veja erros_quiz_anteriores do assunto e, com criar_quiz (aulaId da aula; o quiz herda a tarefa), crie de 6 a 10 \
                perguntas baseadas no que a aula ensinou (não em outra \\
                coisa), da mais simples à aplicada, com 4 ou 5 alternativas. Para CADA alternativa, a explicação de \\
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

    public record LessonCreated(Long aulaId, String titulo, String concurso, String link) {
    }

    @McpTool(name = "criar_aula",
            description = "Cria uma aula no sistema (Markdown: ## títulos, listas, **negrito**, `código`, blocos ``` , "
                    + "citações > e listas numeradas). Vincule à disciplina e ao tópico do edital; depois crie o quiz "
                    + "com criar_quiz. ANTES de escrever, confira o tópico no edital (ler_edital / estrutura_do_edital) "
                    + "e pesquise fontes atuais na web (norma vigente, súmulas, temas e jurisprudência, autores e "
                    + "conceitos). 'fontes' lista só o que foi LIDO, com o link de cada um; o que não foi confirmado vai "
                    + "em 'naoConfirmado', e então 'tentativas' (o que você fez para confirmar: outras fontes, "
                    + "download e extração do texto) é obrigatório. Se a fonte vier truncada ou em resumo, busque o "
                    + "texto por outro caminho antes de criar a aula. Tudo é anexado ao fim dela.",
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
                    required = false) String tentativas) {
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
        String content = conteudo + "\n\n---\n\n## Fontes conferidas\n" + fontes.strip() + "\n"
                + (hasUnconfirmed ? "\n### Não confirmado\n" + naoConfirmado.strip() + "\n\n### O que foi tentado\n"
                + tentativas.strip() + "\n" : "");
        Lesson lesson = mcpConcurso.in(concurso,
                () -> lessonService.create(titulo, disciplina, topicoEdital, content, tarefaId));
        return new LessonCreated(lesson.getId(), lesson.getTitle(), mcpConcurso.name(lesson.getConcursoId()),
                baseUrl + "/aulas/" + lesson.getId());
    }

    @McpPrompt(name = "novo_quiz", title = "Novo quiz",
            description = "Cria um quiz (de uma aula ou avulso) no nível pedido, trabalhando as dúvidas do usuário e "
                    + "retomando os erros dos quizzes anteriores do assunto.")
    public String newQuizPrompt(
            @McpArg(name = "assunto", description = "Id da aula, ou disciplina/tópico do quiz.", required = true) String assunto,
            @McpArg(name = "nivel", description = "basico, intermediario, avancado ou prova.", required = false) String nivel,
            @McpArg(name = "duvidas", description = "Dúvidas que o quiz deve trabalhar.", required = false) String duvidas) {
        return """
                Crie um quiz novo no pdf2questao sobre: %s. Nível: %s. %s

                1. Chame erros_quiz_anteriores para o mesmo assunto (aulaId, ou disciplina/tópico) e leia cada erro: o \
                que o usuário marcou e por quê estava errado. Veja também desempenho_quiz e perguntas_ja_feitas \
                (o sistema recusa pergunta repetida: cubra pontos ainda não perguntados).
                2. Se for de uma aula, leia o conteúdo dela (listar_aulas) e confira na web os pontos de norma, \
                súmula, tema ou jurisprudência que o quiz vai cobrar (se a fonte vier truncada ou em resumo, baixe e \\
                extraia o texto ou ache outra fonte antes de desistir); se for avulso, leia o tópico no edital \
                (ler_edital), pesquise fontes atuais na web e veja como a banca cobra (exemplos_da_banca). Não \
                cobre o que não conseguiu confirmar.
                3. Escreva de 6 a 10 perguntas: uma ou mais para cada erro anterior (com outro enunciado, cobrando o \
                mesmo ponto), as dúvidas do usuário, e o restante no nível pedido (basico = conceito; \
                intermediario = aplicação; avancado = casos e exceções; prova = estilo e pegadinhas da banca). \
                Toda alternativa com a explicação de por que está certa ou errada.
                4. Grave com criar_quiz informando em errosRetomados os ids das perguntas erradas que o quiz retoma. \
                Passe o link ao usuário e não mostre as respostas na conversa.
                """.formatted(assunto, McpText.blank(nivel) ? "intermediario" : nivel,
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
                estudado ainda se sustenta, e tem que ser MAIS DIFÍCIL que o quiz da aula.

                1. ASSUNTOS. Chame revisoes_de_hoje (só devolve os do concurso; cada concurso tem os seus). Agrupe por \
                disciplina e use a etapa: quanto maior, mais difícil e mais misturado com assuntos parecidos. Para cada \
                disciplina chame erros_quiz_anteriores, desempenho_quiz e perguntas_ja_feitas.

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

                5. GRAVAR. De 3 a 4 perguntas por assunto, nível "prova", um quiz por disciplina (assim o sistema \
                retoma os erros dela; informe errosRetomados) ou um só se o usuário pedir um simulado misturado, e \
                nesse caso leia erros_quiz_anteriores de cada disciplina. Passe o link e não mostre as respostas.

                6. DEPOIS. Leia resultado_quiz. Para cada assunto, marcar_revisao com lembrei=true só se ele acertou \
                pelo menos 70%% das perguntas daquele assunto (e false, voltando para amanhã, se não); explique em \
                poucas linhas o que errou.
                """.formatted(c);
    }

    public record QuizAlternative(
            @McpToolParam(description = "Texto da alternativa.") String texto,
            @McpToolParam(description = "Por que está certa ou errada (mostrado ao clicar).") String explicacao) {
    }

    public record QuizItem(
            @McpToolParam(description = "Enunciado.") String enunciado,
            @McpToolParam(description = "Tópico cobrado (para o desempenho por tópico).", required = false) String topico,
            @McpToolParam(description = "Alternativas na ordem A, B, C...") List<QuizAlternative> alternativas,
            @McpToolParam(description = "Letra correta (no certo_errado: \"C\" ou \"E\").") String correta) {

        NewQuestion toNew() {
            return new NewQuestion(enunciado, topico,
                    alternativas == null ? null : alternativas.stream().map(QuizAlternative::texto).toList(),
                    alternativas == null ? null : alternativas.stream().map(QuizAlternative::explicacao).toList(),
                    correta);
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
                    + "(disciplina/tópico). Nível: basico, intermediario, avancado ou prova. Se houver erros em "
                    + "quizzes anteriores do assunto, é obrigatório retomá-los: leia erros_quiz_anteriores e informe os "
                    + "ids em errosRetomados. Pergunta repetida (já feita no concurso, em qualquer quiz ou revisão) é "
                    + "recusada: veja perguntas_ja_feitas antes. Baseie as perguntas na aula e, em quiz avulso, "
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
            @McpToolParam(description = "Nível pedido.", required = false) String nivel,
            @McpToolParam(description = "Dúvidas do usuário que o quiz trabalha.", required = false) String duvidas,
            @McpToolParam(description = "Ids das perguntas erradas (erros_quiz_anteriores) que este quiz retoma.",
                    required = false) List<Long> errosRetomados,
            @McpToolParam(description = "Perguntas.") List<QuizItem> perguntas,
            @McpToolParam(description = "Id da tarefa do cronograma que o quiz cobre (vazio = a da aula, se houver).",
                    required = false) Long tarefaId,
            @McpToolParam(description = McpConcurso.PARAM + " Vazio também herda o da aula/tarefa.", required = false)
            String concurso,
            @McpToolParam(description = "multipla_escolha (padrão) ou certo_errado (itens C/E, estilo Cebraspe).",
                    required = false) String formato) {
        Quiz quiz = mcpConcurso.in(concurso, () -> lessonService.createQuiz(aulaId, titulo, disciplina, topico, nivel,
                duvidas, errosRetomados, perguntas == null ? null : perguntas.stream().map(QuizItem::toNew).toList(),
                tarefaId, formato));
        return new QuizCreated(quiz.getId(), quiz.getTitle(), quiz.getLessonId(), lessonService.questions(quiz.getId()).size(),
                errosRetomados == null ? 0 : errosRetomados.size(), baseUrl + "/quizzes/" + quiz.getId());
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
                    + "explicação de por que está certa ou errada.",
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
                    + "são recorrigidas pela nova correta.",
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
            @McpToolParam(description = "Nova letra correta.", required = false) String correta) {
        var q = lessonService.fixQuestion(quizId, numero, enunciado, topico,
                alternativas == null ? null : alternativas.stream().map(QuizAlternative::texto).toList(),
                alternativas != null ? alternativas.stream().map(QuizAlternative::explicacao).toList() : explicacoes,
                correta);
        int answered = (int) lessonService.result(quizId).questoes().stream()
                .filter(r -> r.perguntaId().equals(q.getId()) && r.suaResposta() != null).count();
        return new QuizFixed(quizId, q.getPosition(), q.getCorrectAnswer(), answered, baseUrl + "/quizzes/" + quizId);
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
