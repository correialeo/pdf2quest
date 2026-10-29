package com.pdf2questao.study;

import com.pdf2questao.concurso.ConcursoService;
import com.pdf2questao.concurso.Workspace;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resumo do dia: uma pagina por data do cronograma <b>de cada concurso</b> (workspace), criada na primeira vez que o
 * usuario abre e guardada dentro do "Resumos do dia" daquele concurso. Ja nasce com um titulo para cada tarefa do dia
 * do concurso, para anotar o que foi estudado em cada uma. Em "Todos" (sem concurso) o resumo e o geral, com as
 * tarefas de todos os concursos.
 */
@Service
public class DaySummaryService {

    static final String PARENT_TITLE = "Resumos do dia";
    private static final DateTimeFormatter TITLE = DateTimeFormatter.ofPattern("EEE, dd/MM/yyyy", Locale.of("pt", "BR"));

    private final StudyPageRepository pageRepository;
    private final StudyPageService pageService;
    private final StudyTaskRepository taskRepository;
    private final ConcursoService concursoService;
    private final Workspace workspace;

    public DaySummaryService(StudyPageRepository pageRepository, StudyPageService pageService,
                             StudyTaskRepository taskRepository, ConcursoService concursoService,
                             Workspace workspace) {
        this.pageRepository = pageRepository;
        this.pageService = pageService;
        this.taskRepository = taskRepository;
        this.concursoService = concursoService;
        this.workspace = workspace;
    }

    /** Resumo ja existente de cada dia do intervalo no concurso em vigor (data -> id da pagina), para os botoes. */
    public Map<LocalDate, Long> existing(LocalDate from, LocalDate to) {
        return pageRepository.summariesBetween(from, to, workspace.currentId()).stream()
                .collect(Collectors.toMap(StudyPage::getSummaryDate, StudyPage::getId));
    }

    /** Tarefas do dia do concurso (nulo = todas); a tarefa diaria de revisao tambem e de um concurso so. */
    public List<StudyTask> tasks(LocalDate date, Long concursoId) {
        Map<Long, Long> concursoBy = new java.util.HashMap<>();
        concursoService.cronogramasById().forEach((id, c) -> concursoBy.put(id, c.getConcursoId()));
        return taskRepository.findByPlannedDate(date).stream()
                .filter(t -> concursoId == null
                        || concursoId.equals(t.getCronogramaId() == null ? null : concursoBy.get(t.getCronogramaId())))
                .sorted(Comparator.comparing(StudyTask::getId)).toList();
    }

    /** Resumo ja escrito mais proximo antes/depois do dia, no mesmo concurso (navegar nao cria resumos vazios). */
    public java.util.Optional<StudyPage> previous(LocalDate date, Long concursoId) {
        return pageRepository.summariesBefore(date, concursoId, org.springframework.data.domain.PageRequest.of(0, 1))
                .stream().findFirst();
    }

    public java.util.Optional<StudyPage> next(LocalDate date, Long concursoId) {
        return pageRepository.summariesAfter(date, concursoId, org.springframework.data.domain.PageRequest.of(0, 1))
                .stream().findFirst();
    }

    /** Abre o resumo do dia do concurso em vigor, criando se ainda nao existe. */
    @Transactional
    public StudyPage open(LocalDate date) {
        Long concursoId = workspace.currentId();
        return pageRepository.findSummary(date, concursoId).orElseGet(() -> create(date, concursoId));
    }

    private StudyPage create(LocalDate date, Long concursoId) {
        StringBuilder html = new StringBuilder();
        for (StudyTask task : tasks(date, concursoId)) {
            html.append("<h2>").append(escape(task.getTitle())).append("</h2><p><br></p>");
        }
        html.append("<h2>Dúvidas e erros do dia</h2><ul><li><br></li></ul>");
        StudyPage page = pageService.create(parent(concursoId).getId(), "Resumo – " + title(date), null,
                html.toString());
        page.setSummaryDate(date);
        // Posicao = dia: os resumos ficam em ordem de data dentro de "Resumos do dia".
        page.setPosition((int) date.toEpochDay());
        return pageRepository.save(page);
    }

    private StudyPage parent(Long concursoId) {
        for (Long id : pageRepository.findSummaryParents(concursoId)) {
            var parent = pageRepository.findById(id);
            if (parent.isPresent()) {
                return parent.get();
            }
        }
        StudyPage created = pageService.create(null, PARENT_TITLE, null,
                "<p>Um resumo para cada dia do cronograma. Abra pelo botão <strong>Resumo do dia</strong> no cronograma.</p>");
        // Cada concurso tem a sua pasta de resumos (a pagina nasce no concurso em vigor).
        created.setConcursoId(concursoId);
        return pageRepository.save(created);
    }

    static String title(LocalDate date) {
        String t = TITLE.format(date).replace(".,", ",");
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
