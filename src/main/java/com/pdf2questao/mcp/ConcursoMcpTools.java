package com.pdf2questao.mcp;

import com.pdf2questao.concurso.Concurso;
import com.pdf2questao.concurso.ConcursoService;
import com.pdf2questao.concurso.ConcursoStatus;
import com.pdf2questao.concurso.Cronograma;
import com.pdf2questao.concurso.Workspace;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Concursos (workspace) e cronogramas pelo MCP. As outras ferramentas aceitam o parametro "concurso" para trabalhar
 * num concurso especifico ("monte as aulas do cronograma de hoje do concurso X").
 */
@Component
public class ConcursoMcpTools {

    private final ConcursoService concursoService;
    private final Workspace workspace;
    private final McpConcurso mcpConcurso;
    private final String baseUrl;

    public ConcursoMcpTools(ConcursoService concursoService, Workspace workspace, McpConcurso mcpConcurso,
                            @Value("${server.port:8080}") int port) {
        this.concursoService = concursoService;
        this.workspace = workspace;
        this.mcpConcurso = mcpConcurso;
        this.baseUrl = "http://localhost:" + port;
    }

    public record CronogramaView(Long id, String nome, LocalDate inicio, LocalDate fim, boolean ativo,
                                 long tarefas, long concluidas) {
    }

    public record ConcursoView(Long id, String nome, String orgao, String cargo, String banca, String situacao,
                               LocalDate dataProva, Long diasParaProva, Long perfilId, boolean ativo,
                               boolean arquivado, List<CronogramaView> cronogramas, String link) {
    }

    @McpTool(name = "listar_concursos",
            description = "Concursos do usuário (workspaces) com data da prova, perfil de pontuação (perfilId, para "
                    + "estrutura_do_edital/criar_simulado) e cronogramas. 'ativo' = o selecionado na tela, usado "
                    + "quando uma ferramenta não recebe 'concurso'.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public List<ConcursoView> listConcursos(
            @McpToolParam(description = "true para incluir os arquivados.", required = false) Boolean arquivados) {
        var counts = concursoService.taskCounts();
        Long selected = workspace.selectedId();
        LocalDate today = LocalDate.now();
        var cronogramas = concursoService.allCronogramas();
        return (Boolean.TRUE.equals(arquivados) ? concursoService.all() : concursoService.active()).stream()
                .map(c -> new ConcursoView(c.getId(), c.getNome(), c.getOrgao(), c.getCargo(), c.getBanca(),
                        c.getStatus().getLabel(), c.getDataProva(), c.diasParaProva(today), c.getScoringProfileId(),
                        c.getId().equals(selected), c.isArquivado(),
                        cronogramas.stream().filter(cr -> c.getId().equals(cr.getConcursoId()))
                                .map(cr -> view(cr, counts.get(cr.getId()))).toList(),
                        baseUrl + "/concursos/" + c.getId()))
                .toList();
    }

    private static CronogramaView view(Cronograma c, long[] counts) {
        return new CronogramaView(c.getId(), c.getNome(), c.getInicio(), c.getFim(), c.isAtivo(),
                counts == null ? 0 : counts[0], counts == null ? 0 : counts[1]);
    }

    public record ConcursoSaved(Long id, String nome, boolean ativo, String link) {
    }

    @McpTool(name = "criar_concurso",
            description = "Cadastra um concurso (já com um cronograma \"Geral\"). Confirme com o usuário antes. Para "
                    + "ligar o edital, informe perfilId (estrutura_do_edital) ou depois salve o perfil com "
                    + "salvar_perfil_edital(concurso=...).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public ConcursoSaved createConcurso(
            @McpToolParam(description = "Nome, ex.: \"SEFAZ-SP 2026 – AFR\".") String nome,
            @McpToolParam(description = "Órgão.", required = false) String orgao,
            @McpToolParam(description = "Cargo.", required = false) String cargo,
            @McpToolParam(description = "Banca, ex.: FCC.", required = false) String banca,
            @McpToolParam(description = "Data da prova (AAAA-MM-DD).", required = false) String dataProva,
            @McpToolParam(description = "Fim das inscrições (AAAA-MM-DD).", required = false) String fimInscricoes,
            @McpToolParam(description = "Link do edital.", required = false) String linkEdital,
            @McpToolParam(description = "Id do perfil de pontuação do edital.", required = false) Long perfilId,
            @McpToolParam(description = "true para já deixar como concurso ativo.", required = false) Boolean ativar) {
        Concurso c = concursoService.create(new ConcursoService.ConcursoForm(nome, orgao, banca, cargo, date(dataProva),
                date(fimInscricoes), linkEdital, ConcursoStatus.ESTUDANDO, perfilId, null, null));
        if (Boolean.TRUE.equals(ativar)) {
            workspace.select(c.getId());
        }
        return new ConcursoSaved(c.getId(), c.getNome(), Boolean.TRUE.equals(ativar), baseUrl + "/concursos/" + c.getId());
    }

    @McpTool(name = "selecionar_concurso",
            description = "Troca o concurso ativo (o mesmo da barra lateral da tela). Vazio = todos os concursos. Só "
                    + "use se o usuário pedir para trocar; para uma chamada só, passe 'concurso' na ferramenta.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    public ConcursoSaved selectConcurso(
            @McpToolParam(description = McpConcurso.PARAM, required = false) String concurso) {
        Concurso c = concursoService.resolve(concurso);
        workspace.select(c == null ? null : c.getId());
        return c == null ? new ConcursoSaved(null, "Todos os concursos", true, baseUrl + "/concursos")
                : new ConcursoSaved(c.getId(), c.getNome(), true, baseUrl + "/concursos/" + c.getId());
    }

    @McpTool(name = "criar_cronograma",
            description = "Cria um cronograma (ex.: \"Reta final\") no concurso. As tarefas novas do concurso entram no "
                    + "cronograma ativo mais recente; use criar_tarefa(cronograma=...) para escolher outro.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public CronogramaView createCronograma(
            @McpToolParam(description = "Nome do cronograma.") String nome,
            @McpToolParam(description = McpConcurso.PARAM + " \"nenhum\" = cronograma avulso.", required = false)
            String concurso,
            @McpToolParam(description = "Início (AAAA-MM-DD).", required = false) String inicio,
            @McpToolParam(description = "Fim (AAAA-MM-DD).", required = false) String fim) {
        Long concursoId = concurso != null && concurso.strip().toLowerCase(Locale.ROOT).equals("nenhum") ? null
                : java.util.Optional.ofNullable(mcpConcurso.resolveOrCurrent(concurso)).map(Concurso::getId).orElse(null);
        return view(concursoService.saveCronograma(null, concursoId, nome, date(inicio), date(fim), true), null);
    }

    private static LocalDate date(String s) {
        return McpText.blank(s) ? null : LocalDate.parse(s.strip());
    }
}
