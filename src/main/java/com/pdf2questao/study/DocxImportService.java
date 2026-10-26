package com.pdf2questao.study;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Importa um .docx como pagina de Estudos e, se o documento tiver tabelas de cronograma, cria as tarefas. Como na
 * importacao de questoes: primeiro a previa (nada gravado), depois a confirmacao grava tudo numa transacao.
 */
@Service
public class DocxImportService {

    private final StudyPageService pageService;
    private final StudyTaskRepository taskRepository;
    private final com.pdf2questao.concurso.ConcursoService concursoService;
    private final com.pdf2questao.concurso.Workspace workspace;
    private final Map<String, Preview> previews = new ConcurrentHashMap<>();

    public DocxImportService(StudyPageService pageService, StudyTaskRepository taskRepository,
                             com.pdf2questao.concurso.ConcursoService concursoService,
                             com.pdf2questao.concurso.Workspace workspace) {
        this.concursoService = concursoService;
        this.workspace = workspace;
        this.pageService = pageService;
        this.taskRepository = taskRepository;
    }

    /** Tarefa encontrada no documento; {@code existing} = ja ha tarefa com o mesmo titulo no mesmo dia. */
    public record TaskPreview(ScheduleExtractor.Item item, boolean existing) {
    }

    public record Preview(String token, String fileName, String title, String html, int year,
                          List<TaskPreview> tasks, LocalDateTime createdAt) {

        public long newTasks() {
            return tasks.stream().filter(t -> !t.existing()).count();
        }

        public long existingTasks() {
            return tasks.size() - newTasks();
        }

        public int totalMinutes() {
            return tasks.stream().filter(t -> !t.existing())
                    .mapToInt(t -> t.item().minutes() == null ? 0 : t.item().minutes()).sum();
        }

        public LocalDate firstDate() {
            return tasks.isEmpty() ? null : tasks.get(0).item().date();
        }

        public LocalDate lastDate() {
            return tasks.isEmpty() ? null : tasks.get(tasks.size() - 1).item().date();
        }
    }

    public record Outcome(Long pageId, int tasksCreated) {
    }

    public Preview preview(String fileName, byte[] bytes, Integer year) throws DocxReader.InvalidDocxException {
        DocxReader.Result doc = DocxReader.read(bytes);
        int y = year != null ? year : ScheduleExtractor.guessYear(doc.text(), LocalDate.now());
        List<ScheduleExtractor.Item> items = ScheduleExtractor.extract(doc.tables(), y);
        Set<String> existing = new HashSet<>();
        for (StudyTask t : taskRepository.findAll()) {
            existing.add(key(t.getPlannedDate(), t.getTitle()));
        }
        List<TaskPreview> tasks = items.stream()
                .map(i -> new TaskPreview(i, existing.contains(key(i.date(), title(i.title()))))).toList();
        String title = doc.title() != null ? doc.title()
                : fileName == null ? "Documento importado" : fileName.replaceAll("(?i)\\.docx$", "");
        Preview preview = new Preview(UUID.randomUUID().toString(), fileName, title, doc.html(), y, tasks,
                LocalDateTime.now());
        LocalDateTime limit = LocalDateTime.now().minusHours(6);
        previews.values().removeIf(p -> p.createdAt().isBefore(limit));
        previews.put(preview.token(), preview);
        return preview;
    }

    public Optional<Preview> get(String token) {
        return Optional.ofNullable(previews.get(token));
    }

    public Outcome confirm(String token, String title, Long parentId, boolean createTasks) {
        return confirm(token, title, parentId, createTasks, null);
    }

    /**
     * Cria a pagina e, se {@code createTasks}, as tarefas novas ligadas a ela (as ja existentes sao puladas), no
     * cronograma escolhido (nulo = o padrao do concurso em vigor; em "Todos", avulsas).
     */
    @Transactional
    public Outcome confirm(String token, String title, Long parentId, boolean createTasks, Long cronogramaId) {
        Preview preview = previews.get(token);
        if (preview == null) {
            throw new IllegalStateException("Esta prévia expirou. Envie o arquivo de novo.");
        }
        String pageTitle = title == null || title.isBlank() ? preview.title() : title;
        StudyPage page = pageService.create(parentId, pageTitle, null, preview.html());
        int created = 0;
        Long cronograma = cronogramaId != null ? cronogramaId : workspace.currentId() == null ? null
                : concursoService.defaultCronograma(workspace.currentId()).getId();
        if (createTasks) {
            for (TaskPreview t : preview.tasks()) {
                if (t.existing()) {
                    continue;
                }
                ScheduleExtractor.Item i = t.item();
                StudyTask task = new StudyTask();
                task.setTitle(title(i.title()));
                // Descricao: o bloco do cabecalho ("B – TI (90 min)") e, se o titulo foi cortado, o texto inteiro.
                String description = (i.block() + "\n"
                        + (i.title().length() > StudyPageService.MAX_TITLE ? i.title() : "")).strip();
                task.setDescription(description.isEmpty() ? null : description);
                task.setSubject(i.subject());
                task.setPlannedDate(i.date());
                task.setEstimatedMinutes(i.minutes());
                task.setPageId(page.getId());
                task.setCronogramaId(cronograma);
                taskRepository.save(task);
                created++;
            }
        }
        previews.remove(token);
        return new Outcome(page.getId(), created);
    }

    private static String title(String text) {
        return text.length() > StudyPageService.MAX_TITLE ? text.substring(0, StudyPageService.MAX_TITLE - 1) + "…" : text;
    }

    private static String key(LocalDate date, String title) {
        return date + "|" + (title == null ? "" : title.strip().toLowerCase(Locale.ROOT));
    }
}
