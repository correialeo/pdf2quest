package com.pdf2questao.mcp;

import com.pdf2questao.question.PassageRepository;
import com.pdf2questao.simulado.review.ContentType;
import com.pdf2questao.simulado.review.QuestionReviewService;
import com.pdf2questao.simulado.review.QuestionReviewService.Audit;
import com.pdf2questao.simulado.review.QuestionReviewService.BlindQuestion;
import com.pdf2questao.simulado.review.QuestionReviewService.Reference;
import com.pdf2questao.simulado.review.QuestionReviewService.ReviewView;
import com.pdf2questao.simulado.review.ReviewRules.Criterion;
import com.pdf2questao.simulado.review.ReviewRules.Source;
import com.pdf2questao.simulado.review.ReviewRules.SourceState;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Revisao em camadas dos simulados gerados: conteudo programatico, questao sem gabarito para resolucao
 * independente, revisao de fidelidade (B), validacao material (C) e auditoria do simulado.
 */
@Component
public class ReviewMcpTools {

    private final QuestionReviewService reviewService;
    private final PassageRepository passageRepository;

    public ReviewMcpTools(QuestionReviewService reviewService, PassageRepository passageRepository) {
        this.reviewService = reviewService;
        this.passageRepository = passageRepository;
    }

    @McpPrompt(name = "revisar_simulado", title = "Revisar e auditar um simulado gerado",
            description = "Roteiro para revisar um simulado já gerado (fidelidade à banca, correção jurídica/técnica "
                    + "com vigência, cobertura do edital) e produzir o relatório de auditoria.")
    public String reviewPrompt(
            @McpArg(name = "simulados", description = "Ids dos simulados (status_simulado / auditar_simulado), ex.: 1,2.",
                    required = true) String simulados) {
        return """
                Revise os simulados %s questão a questão. O objetivo é encontrar erros, não aprovar: uma questão \\
                que parece FCC pode ter gabarito errado ou regra desatualizada (ex.: prazo de precatórios anterior \\
                à EC 136/2025 tratado como vigente).

                0. Rode auditar_simulado para ver a estrutura e o estado atual. Se a cobertura não for verificável, \\
                leia o conteúdo programático no edital (ler_edital, Anexo II) e grave com \\
                registrar_conteudo_programatico, disciplina por disciplina.

                Para cada questão:
                C) CORREÇÃO MATERIAL (independente). Use um subagente que recebe só questao_para_revisao (sem \\
                gabarito e sem comentário) e devolve a alternativa que ele marcaria, as que também seriam defensáveis \\
                e o fundamento. Classifique o tipo de conteúdo. Se depende de lei, norma, jurisprudência, AFO/\\
                contabilidade pública ou versão de tecnologia: abra a fonte primária oficial (Planalto com a redação \\
                atualizada, DOU, STF/STJ/TCU, STN/MCASP, documentação oficial da ferramenta), localize o dispositivo \\
                exato, confira emendas, leis posteriores, revogações e regras de transição, e registre data da \\
                consulta e situação (VIGENTE, ALTERADA, REVOGADA, NAO_CONFIRMADA; INDISPONIVEL se não conseguiu \\
                acessar). Resumos de terceiros e a memória do modelo não confirmam vigência. Refaça cálculos. \\
                Registre com validar_correcao.
                B) FIDELIDADE. Compare com 1 a 3 questões reais pertinentes que você LEU (exemplos_da_banca, ids \\
                locais; ou link de prova + o que foi lido) e dê as 8 notas de 0 a 4 com justificativa concreta \\
                (o que na referência sustenta a nota). Informe o tópico do edital. Registre com revisar_fidelidade.

                Se reprovar ou ficar pendente: corrija com corrigir_questao_simulado informando o motivo (a versão \\
                anterior fica guardada) e revise de novo; se não houver como confirmar a regra, deixe pendente.

                No fim, rode auditar_simulado e apresente ao usuário: aprovadas/reprovadas/pendentes, falhas \\
                críticas por número, problemas de vigência, cálculo, gabarito e ambiguidade, fidelidade média por \\
                disciplina com a confiança, cobertura do edital e tópicos sem questão, referências usadas, questões \\
                corrigidas e motivos, e as limitações. Não declare o simulado validado se houver reprovadas ou \\
                pendentes. Não mostre gabaritos de questões que o usuário ainda não resolveu sem ele pedir.
                """.formatted(simulados);
    }

    @McpTool(name = "registrar_conteudo_programatico",
            description = "Grava (substitui) os tópicos do conteúdo programático do edital para uma disciplina do "
                    + "perfil, para conferir a cobertura dos simulados e sinalizar questões fora do edital.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    public String saveTopics(
            @McpToolParam(description = "Id do perfil (estrutura_do_edital).") Long perfilId,
            @McpToolParam(description = "Disciplina (nome ou apelido do perfil).") String disciplina,
            @McpToolParam(description = "Tópicos como no edital, um por item.") List<String> topicos) {
        return reviewService.saveTopics(perfilId, disciplina, topicos) + " tópicos gravados para " + disciplina + ".";
    }

    @McpTool(name = "questao_para_revisao",
            description = "Questão de um simulado SEM gabarito e SEM comentário, para um revisor resolver de forma "
                    + "independente antes de validar_correcao. Dê isto a um subagente que não viu o gabarito.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public BlindQuestion blindQuestion(
            @McpToolParam(description = "Id do simulado.") Long simuladoId,
            @McpToolParam(description = "Número da questão.") Integer numero) {
        return reviewService.blind(simuladoId, numero, id -> passageRepository.findById(id)
                .map(p -> McpText.plain(p.getContent())).orElse(null));
    }

    public record CriterionInput(
            @McpToolParam(description = "Nota de 0 a 4.") Integer nota,
            @McpToolParam(description = "Justificativa curta e verificável, citando a referência.") String justificativa) {
    }

    public record ReferenceInput(
            @McpToolParam(description = "Id de questão real local (exemplos_da_banca).", required = false) Long questaoId,
            @McpToolParam(description = "Link da prova externa.", required = false) String url,
            @McpToolParam(description = "Para link: qual questão foi lida e o que ela mostra.", required = false)
            String descricao) {
    }

    @McpTool(name = "revisar_fidelidade",
            description = "Registra a revisão de fidelidade à banca de uma questão: 8 critérios de 0 a 4, na ordem — "
                    + "1 formato de cobrança vs referências; 2 profundidade sem complexidade artificial; 3 enunciado "
                    + "suficiente; 4 distratores plausíveis; 5 uma única correta; 6 sem pistas nas alternativas; "
                    + "7 dificuldade compatível; 8 formulação inédita. Exige referências reais lidas (sem elas a nota "
                    + "fica limitada a 2). Falha grave reprova mesmo com média alta.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    public ReviewView reviewFidelity(
            @McpToolParam(description = "Id do simulado.") Long simuladoId,
            @McpToolParam(description = "Número da questão.") Integer numero,
            @McpToolParam(description = "As 8 notas, na ordem dos critérios.") List<CriterionInput> criterios,
            @McpToolParam(description = "Questões reais usadas como referência (1 a 3).") List<ReferenceInput> referencias,
            @McpToolParam(description = "Tópico do conteúdo programático que a questão cobra.", required = false)
            String topicoEdital) {
        List<Criterion> criteria = criterios == null ? null : criterios.stream()
                .map(c -> new Criterion(c.nota() == null ? -1 : c.nota(), c.justificativa())).toList();
        List<Reference> refs = referencias == null ? List.of() : referencias.stream()
                .map(r -> new Reference(r.questaoId(), r.url(), r.descricao())).toList();
        return reviewService.reviewFidelity(simuladoId, numero, criteria, refs, topicoEdital);
    }

    public record SourceInput(
            @McpToolParam(description = "Link da fonte consultada.") String url,
            @McpToolParam(description = "true se é fonte primária oficial (Planalto, DOU, tribunal, órgão, doc. oficial).")
            Boolean primariaOficial,
            @McpToolParam(description = "Dispositivo exato, ex.: \"CF, art. 100, § 5º (redação da EC 136/2025)\".",
                    required = false) String dispositivo,
            @McpToolParam(description = "Data da consulta (AAAA-MM-DD).", required = false) String consultadoEm,
            @McpToolParam(description = "VIGENTE, ALTERADA, REVOGADA, NAO_CONFIRMADA ou INDISPONIVEL (sem acesso).")
            String situacao,
            @McpToolParam(description = "Ressalvas: transição, vigência futura, entendimento divergente.",
                    required = false) String observacao) {
    }

    @McpTool(name = "validar_correcao",
            description = "Registra a validação material de uma questão, independente do estilo. Reprova se a "
                    + "resolução independente diverge do gabarito, se há outra alternativa defensável ou se a regra "
                    + "usada foi alterada/revogada. Fica PENDENTE se o conteúdo depende de norma/versão e não há fonte "
                    + "que confirme (fonte indisponível nunca conta como verificada). Tipos: "
                    + "LEGISLACAO, JURISPRUDENCIA, NORMA_REGULAMENTAR, FINANCAS_PUBLICAS_CONTABILIDADE, NORMA_TECNICA, "
                    + "TECNOLOGIA_VERSIONADA, DADOS_ESTATISTICA_ML, METODOLOGIA_MODELO, CONCEITO_ESTAVEL.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    public ReviewView validateMaterial(
            @McpToolParam(description = "Id do simulado.") Long simuladoId,
            @McpToolParam(description = "Número da questão.") Integer numero,
            @McpToolParam(description = "Tipo de conteúdo.") String tipoConteudo,
            @McpToolParam(description = "Letra que o revisor independente marcou SEM ver o gabarito.") String respostaIndependente,
            @McpToolParam(description = "Outras letras defensáveis sob interpretação razoável (vazio = nenhuma).",
                    required = false) List<String> alternativasDefensaveis,
            @McpToolParam(description = "true se a questão envolve cálculo.", required = false) Boolean envolveCalculo,
            @McpToolParam(description = "Cálculo refeito passo a passo.", required = false) String calculo,
            @McpToolParam(description = "Fontes consultadas.", required = false) List<SourceInput> fontes,
            @McpToolParam(description = "true se a questão cobra, de propósito, a regra de uma data passada.",
                    required = false) Boolean regraHistorica,
            @McpToolParam(description = "Marco temporal da regra (data/norma do enunciado ou da prova-alvo).",
                    required = false) String marcoTemporal,
            @McpToolParam(description = "Fundamento da resposta e observações.", required = false) String observacoes) {
        ContentType type;
        try {
            type = ContentType.valueOf(tipoConteudo.strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Tipo de conteúdo desconhecido: " + tipoConteudo);
        }
        List<Source> sources = fontes == null ? List.of() : fontes.stream().map(f -> new Source(f.url(),
                Boolean.TRUE.equals(f.primariaOficial()), f.dispositivo(),
                McpText.blank(f.consultadoEm()) ? null : LocalDate.parse(f.consultadoEm().strip()), state(f.situacao()),
                f.observacao())).toList();
        return reviewService.reviewMaterial(simuladoId, numero, type, respostaIndependente, alternativasDefensaveis,
                Boolean.TRUE.equals(envolveCalculo), calculo, sources, Boolean.TRUE.equals(regraHistorica),
                marcoTemporal, observacoes);
    }

    private static SourceState state(String s) {
        try {
            return SourceState.valueOf(s.strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return SourceState.NAO_CONFIRMADA;
        }
    }

    @McpTool(name = "situacao_revisao",
            description = "Estado das revisões de uma questão (fidelidade, correção, tópico do edital e problemas).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ReviewView reviewState(
            @McpToolParam(description = "Id do simulado.") Long simuladoId,
            @McpToolParam(description = "Número da questão.") Integer numero) {
        return reviewService.view(simuladoId, numero);
    }

    @McpTool(name = "auditar_simulado",
            description = "Relatório de auditoria de um ou mais simulados: estrutura (quantidades, 5 alternativas, "
                    + "gabarito, comentário), estado de cada questão, falhas críticas e pendências por número, "
                    + "fidelidade por disciplina com a confiança, fontes verificadas, cobertura do edital, duplicatas, "
                    + "referências usadas e limitações. Não aprova nada sozinho.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public Audit audit(@McpToolParam(description = "Ids dos simulados.") List<Long> simulados) {
        return reviewService.audit(simulados);
    }
}
