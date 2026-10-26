package com.pdf2questao.study;

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
 * Resumo do dia: uma pagina por data do cronograma, criada na primeira vez que o usuario abre e guardada dentro de
 * "Resumos do dia". Ja nasce com um titulo para cada tarefa do dia, para anotar o que foi estudado em cada uma.
 */
@Service
public class DaySummaryService {

    static final String PARENT_TITLE = "Resumos do dia";
    private static final DateTimeFormatter TITLE = DateTimeFormatter.ofPattern("EEE, dd/MM/yyyy", Locale.of("pt", "BR"));

    private final StudyPageRepository pageRepository;
    private final StudyPageService pageService;
    private final StudyTaskRepository taskRepository;

    public DaySummaryService(StudyPageRepository pageRepository, StudyPageService pageService,
                             StudyTaskRepository taskRepository) {
        this.pageRepository = pageRepository;
        this.pageService = pageService;
        this.taskRepository = taskRepository;
    }

    /** Resumo ja existente de cada dia do intervalo (data -> id da pagina), para os botoes do cronograma. */
    public Map<LocalDate, Long> existing(LocalDate from, LocalDate to) {
        return pageRepository.findBySummaryDateBetween(from, to).stream()
                .collect(Collectors.toMap(StudyPage::getSummaryDate, StudyPage::getId));
    }

    public List<StudyTask> tasks(LocalDate date) {
        return taskRepository.findByPlannedDate(date).stream()
                .sorted(Comparator.comparing(StudyTask::getId)).toList();
    }

    /** Resumo ja escrito mais proximo antes/depois do dia (navegar nao cria resumos vazios). */
    public java.util.Optional<StudyPage> previous(LocalDate date) {
        return pageRepository.findFirstBySummaryDateLessThanOrderBySummaryDateDesc(date);
    }

    public java.util.Optional<StudyPage> next(LocalDate date) {
        return pageRepository.findFirstBySummaryDateGreaterThanOrderBySummaryDateAsc(date);
    }

    /** Abre o resumo do dia, criando se ainda nao existe. */
    @Transactional
    public StudyPage open(LocalDate date) {
        return pageRepository.findBySummaryDate(date).orElseGet(() -> create(date));
    }

    private StudyPage create(LocalDate date) {
        StringBuilder html = new StringBuilder();
        for (StudyTask task : tasks(date)) {
            html.append("<h2>").append(escape(task.getTitle())).append("</h2><p><br></p>");
        }
        html.append("<h2>Dúvidas e erros do dia</h2><ul><li><br></li></ul>");
        StudyPage page = pageService.create(parent().getId(), "Resumo – " + title(date), null, html.toString());
        page.setSummaryDate(date);
        // Posicao = dia: os resumos ficam em ordem de data dentro de "Resumos do dia".
        page.setPosition((int) date.toEpochDay());
        return pageRepository.save(page);
    }

    private StudyPage parent() {
        for (Long id : pageRepository.findSummaryParents()) {
            var parent = pageRepository.findById(id);
            if (parent.isPresent()) {
                return parent.get();
            }
        }
        StudyPage created = pageService.create(null, PARENT_TITLE, null,
                "<p>Um resumo para cada dia do cronograma. Abra pelo botão <strong>Resumo do dia</strong> no cronograma.</p>");
        // Os resumos do dia sao de todos os concursos (um por dia).
        created.setConcursoId(null);
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
