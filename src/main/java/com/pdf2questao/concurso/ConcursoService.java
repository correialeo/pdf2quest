package com.pdf2questao.concurso;

import com.pdf2questao.scoring.ScoringCalculator;
import com.pdf2questao.study.StudyTask;
import com.pdf2questao.study.StudyTaskRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Concursos e cronogramas: cadastro, achar o concurso pelo nome (MCP) e herdar o concurso pela tarefa. */
@Service
public class ConcursoService {

    /** Tabelas com concurso_id: apagar o concurso so desfaz o vinculo nelas. */
    private static final List<String> LINKED_TABLES = List.of("study_block", "generated_exam", "study_session",
            "lesson", "quiz", "essay", "import_job", "study_page");

    private final ConcursoRepository concursoRepository;
    private final CronogramaRepository cronogramaRepository;
    private final StudyTaskRepository taskRepository;
    private final Workspace workspace;
    private final JdbcTemplate jdbc;

    public ConcursoService(ConcursoRepository concursoRepository, CronogramaRepository cronogramaRepository,
                           StudyTaskRepository taskRepository, Workspace workspace, JdbcTemplate jdbc) {
        this.concursoRepository = concursoRepository;
        this.cronogramaRepository = cronogramaRepository;
        this.taskRepository = taskRepository;
        this.workspace = workspace;
        this.jdbc = jdbc;
    }

    public record ConcursoForm(String nome, String orgao, String banca, String cargo, LocalDate dataProva,
                               LocalDate dataInscricaoFim, String linkEdital, ConcursoStatus status,
                               Long scoringProfileId, String cor, String notas) {
    }

    // ---- concursos ----

    /** Concursos nao arquivados, prova mais proxima primeiro. */
    public List<Concurso> active() {
        return concursoRepository.findByArquivadoEmIsNullOrderByDataProvaAscNomeAsc();
    }

    public List<Concurso> all() {
        return concursoRepository.findAllByOrderByArquivadoEmAscDataProvaAscNomeAsc();
    }

    public Optional<Concurso> find(Long id) {
        return id == null ? Optional.empty() : concursoRepository.findById(id);
    }

    public Concurso get(Long id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("Concurso " + id + " não existe."));
    }

    public Optional<Concurso> current() {
        return find(workspace.currentId());
    }

    public Map<Long, Concurso> byId() {
        return concursoRepository.findAll().stream().collect(Collectors.toMap(Concurso::getId, Function.identity()));
    }

    @Transactional
    public Concurso create(ConcursoForm form) {
        Concurso concurso = new Concurso();
        apply(concurso, form);
        concurso = concursoRepository.save(concurso);
        Cronograma geral = new Cronograma();
        geral.setConcursoId(concurso.getId());
        geral.setNome("Geral");
        cronogramaRepository.save(geral);
        return concurso;
    }

    @Transactional
    public Concurso update(Long id, ConcursoForm form) {
        Concurso concurso = get(id);
        apply(concurso, form);
        return concursoRepository.save(concurso);
    }

    private void apply(Concurso c, ConcursoForm f) {
        String nome = blank(f.nome());
        if (nome == null) {
            throw new IllegalArgumentException("Dê um nome para o concurso.");
        }
        if (f.dataInscricaoFim() != null && f.dataProva() != null && f.dataInscricaoFim().isAfter(f.dataProva())) {
            throw new IllegalArgumentException("O fim das inscrições não pode ser depois da prova.");
        }
        c.setNome(nome);
        c.setOrgao(blank(f.orgao()));
        c.setBanca(blank(f.banca()));
        c.setCargo(blank(f.cargo()));
        c.setDataProva(f.dataProva());
        c.setDataInscricaoFim(f.dataInscricaoFim());
        c.setLinkEdital(blank(f.linkEdital()));
        c.setStatus(f.status() == null ? ConcursoStatus.ESTUDANDO : f.status());
        c.setScoringProfileId(f.scoringProfileId());
        c.setCor(blank(f.cor()));
        c.setNotas(blank(f.notas()));
        c.setArquivadoEm(c.getStatus() == ConcursoStatus.ARQUIVADO
                ? (c.getArquivadoEm() == null ? LocalDateTime.now() : c.getArquivadoEm()) : null);
    }

    /** Liga o perfil de pontuacao (edital) ao concurso. */
    @Transactional
    public Concurso linkProfile(Long concursoId, Long profileId) {
        Concurso c = get(concursoId);
        c.setScoringProfileId(profileId);
        return concursoRepository.save(c);
    }

    /** Arquiva (some da barra lateral, os dados ficam) ou desarquiva. */
    @Transactional
    public Concurso archive(Long id, boolean archived) {
        Concurso c = get(id);
        c.setArquivadoEm(archived ? LocalDateTime.now() : null);
        c.setStatus(archived ? ConcursoStatus.ARQUIVADO
                : c.getStatus() == ConcursoStatus.ARQUIVADO ? ConcursoStatus.ESTUDANDO : c.getStatus());
        if (archived) {
            workspace.forget(id);
        }
        return concursoRepository.save(c);
    }

    /** Apaga o concurso. Nada mais e apagado: cronogramas, aulas, simulados etc. ficam sem concurso. */
    @Transactional
    public void delete(Long id) {
        get(id);
        LINKED_TABLES.forEach(t -> jdbc.update("UPDATE " + t + " SET concurso_id = NULL WHERE concurso_id = ?", id));
        cronogramaRepository.unlinkConcurso(id);
        workspace.forget(id);
        concursoRepository.deleteById(id);
    }

    /**
     * Acha o concurso pelo id ou por parte do nome/orgao/cargo (sem acento/caixa), como o usuario escreve no chat
     * ("sefaz", "concurso x"). Vazio = nulo. Ambiguo ou desconhecido = erro com a lista de concursos.
     */
    public Concurso resolve(String ref) {
        String r = blank(ref);
        if (r == null) {
            return null;
        }
        if (r.matches("\\d+")) {
            return find(Long.valueOf(r)).orElseThrow(() -> unknown(r));
        }
        String wanted = ScoringCalculator.normalize(r);
        List<Concurso> candidates = all();
        List<Concurso> exact = candidates.stream().filter(c -> ScoringCalculator.normalize(c.getNome()).equals(wanted)).toList();
        if (exact.size() == 1) {
            return exact.get(0);
        }
        List<Concurso> hits = candidates.stream().filter(c -> haystack(c).contains(wanted)).toList();
        if (hits.size() > 1) {
            List<Concurso> open = hits.stream().filter(c -> !c.isArquivado()).toList();
            hits = open.isEmpty() ? hits : open;
        }
        if (hits.size() == 1) {
            return hits.get(0);
        }
        if (hits.isEmpty()) {
            throw unknown(r);
        }
        throw new IllegalArgumentException("Mais de um concurso combina com \"" + r + "\": " + names(hits)
                + ". Use o id.");
    }

    private static String haystack(Concurso c) {
        return ScoringCalculator.normalize(String.join(" ", c.getNome(), c.getOrgao() == null ? "" : c.getOrgao(),
                c.getCargo() == null ? "" : c.getCargo(), c.getBanca() == null ? "" : c.getBanca()));
    }

    private IllegalArgumentException unknown(String ref) {
        List<Concurso> all = all();
        return new IllegalArgumentException("Concurso \"" + ref + "\" não encontrado. "
                + (all.isEmpty() ? "Nenhum concurso cadastrado ainda (use criar_concurso)." : "Concursos: " + names(all) + "."));
    }

    private static String names(List<Concurso> list) {
        return list.stream().map(c -> c.getId() + " = " + c.getNome()).collect(Collectors.joining("; "));
    }

    /**
     * Concurso de algo novo ligado a um perfil de pontuacao (simulado): o em vigor ou, em "Todos", o unico concurso
     * aberto que usa esse perfil.
     */
    public Long forProfile(Long profileId) {
        Long current = workspace.currentId();
        if (current != null || profileId == null) {
            return current;
        }
        List<Concurso> using = active().stream().filter(c -> profileId.equals(c.getScoringProfileId())).toList();
        return using.size() == 1 ? using.get(0).getId() : null;
    }

    // ---- cronogramas ----

    /** Cronogramas do concurso em vigor (em "Todos", todos), ativos primeiro. */
    public List<Cronograma> cronogramas() {
        Long current = workspace.currentId();
        return current == null ? cronogramaRepository.findAllByOrderByAtivoDescCreatedAtDesc()
                : cronogramaRepository.findByConcursoIdOrderByAtivoDescCreatedAtDesc(current);
    }

    public List<Cronograma> allCronogramas() {
        return cronogramaRepository.findAllByOrderByAtivoDescCreatedAtDesc();
    }

    public Optional<Cronograma> cronograma(Long id) {
        return id == null ? Optional.empty() : cronogramaRepository.findById(id);
    }

    public Map<Long, Cronograma> cronogramasById() {
        return cronogramaRepository.findAll().stream().collect(Collectors.toMap(Cronograma::getId, Function.identity()));
    }

    @Transactional
    public Cronograma saveCronograma(Long id, Long concursoId, String nome, LocalDate inicio, LocalDate fim, boolean ativo) {
        String n = blank(nome);
        if (n == null) {
            throw new IllegalArgumentException("Dê um nome para o cronograma.");
        }
        if (inicio != null && fim != null && fim.isBefore(inicio)) {
            throw new IllegalArgumentException("O fim do cronograma é antes do início.");
        }
        if (concursoId != null) {
            get(concursoId);
        }
        Cronograma c = id == null ? new Cronograma() : cronograma(id)
                .orElseThrow(() -> new IllegalArgumentException("Cronograma " + id + " não existe."));
        c.setConcursoId(concursoId);
        c.setNome(n);
        c.setInicio(inicio);
        c.setFim(fim);
        c.setAtivo(ativo);
        return cronogramaRepository.save(c);
    }

    /** Apaga o cronograma; as tarefas continuam, como avulsas. */
    @Transactional
    public void deleteCronograma(Long id) {
        jdbc.update("UPDATE study_task SET cronograma_id = NULL WHERE cronograma_id = ?", id);
        cronogramaRepository.deleteById(id);
    }

    /** Cronograma em que entra uma tarefa nova do concurso: o ativo mais recente; sem nenhum, cria o "Geral". */
    @Transactional
    public Cronograma defaultCronograma(Long concursoId) {
        List<Cronograma> list = cronogramaRepository.findByConcursoIdOrderByAtivoDescCreatedAtDesc(concursoId);
        if (!list.isEmpty()) {
            return list.get(0);
        }
        return saveCronograma(null, concursoId, "Geral", null, null, true);
    }

    /** Concurso de uma tarefa: o do cronograma dela (nulo na tarefa avulsa). */
    public Long concursoOfTask(StudyTask task) {
        return task == null || task.getCronogramaId() == null ? null
                : cronograma(task.getCronogramaId()).map(Cronograma::getConcursoId).orElse(null);
    }

    public Long concursoOfTask(Long taskId) {
        return taskId == null ? null : concursoOfTask(taskRepository.findById(taskId).orElse(null));
    }

    /** Tarefas por cronograma, para os contadores da tela de cronogramas: [total, concluidas]. */
    public Map<Long, long[]> taskCounts() {
        Map<Long, long[]> out = new java.util.HashMap<>();
        jdbc.query("SELECT cronograma_id, COUNT(*), SUM(CASE WHEN done = 1 THEN 1 ELSE 0 END) FROM study_task "
                + "WHERE cronograma_id IS NOT NULL AND review_date IS NULL GROUP BY cronograma_id",
                rs -> {
                    out.put(rs.getLong(1), new long[]{rs.getLong(2), rs.getLong(3)});
                });
        return out;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
