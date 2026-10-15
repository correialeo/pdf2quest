package com.pdf2questao.study;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Paginas da area de Estudos: arvore de paginas e subpaginas, gravacao do editor e exclusao em cascata. */
@Service
public class StudyPageService {

    /** Limite do titulo (coluna varchar(255)). */
    static final int MAX_TITLE = 255;

    private final StudyPageRepository pageRepository;
    private final StudyTaskRepository taskRepository;

    public StudyPageService(StudyPageRepository pageRepository, StudyTaskRepository taskRepository) {
        this.pageRepository = pageRepository;
        this.taskRepository = taskRepository;
    }

    /** Pagina na arvore, com as filhas ja ordenadas. */
    public record PageNode(StudyPage page, int depth, List<PageNode> children) {
    }

    /** Outra aba gravou a pagina depois que esta abriu. */
    public static class StaleVersionException extends RuntimeException {
        private final int currentVersion;

        StaleVersionException(int currentVersion) {
            super("A página foi alterada em outro lugar.");
            this.currentVersion = currentVersion;
        }

        public int getCurrentVersion() {
            return currentVersion;
        }
    }

    public record SaveResult(int version, LocalDateTime savedAt) {
    }

    private static final Comparator<StudyPage> ORDER = Comparator.comparingInt(StudyPage::getPosition)
            .thenComparing(StudyPage::getDisplayTitle, String.CASE_INSENSITIVE_ORDER);

    public List<PageNode> tree() {
        Map<Long, List<StudyPage>> byParent = new HashMap<>();
        for (StudyPage p : pageRepository.findAll()) {
            byParent.computeIfAbsent(p.getParentId() == null ? 0L : p.getParentId(), k -> new ArrayList<>()).add(p);
        }
        return children(0L, 0, byParent);
    }

    private List<PageNode> children(Long parentId, int depth, Map<Long, List<StudyPage>> byParent) {
        return byParent.getOrDefault(parentId, List.of()).stream().sorted(ORDER)
                .map(p -> new PageNode(p, depth, children(p.getId(), depth + 1, byParent))).toList();
    }

    /** Arvore achatada, na ordem de exibicao (para listas e selects). */
    public List<PageNode> flatTree() {
        List<PageNode> flat = new ArrayList<>();
        flatten(tree(), flat);
        return flat;
    }

    private void flatten(List<PageNode> nodes, List<PageNode> out) {
        for (PageNode n : nodes) {
            out.add(n);
            flatten(n.children(), out);
        }
    }

    public Optional<StudyPage> find(Long id) {
        return pageRepository.findById(id);
    }

    public List<StudyPage> recent() {
        return pageRepository.findTop6ByOrderByUpdatedAtDesc();
    }

    public List<StudyPage> children(Long id) {
        return pageRepository.findByParentId(id).stream().sorted(ORDER).toList();
    }

    /** Caminho da raiz ate a pagina (inclusive). */
    public List<StudyPage> breadcrumbs(StudyPage page) {
        List<StudyPage> path = new ArrayList<>();
        StudyPage current = page;
        int guard = 0;
        while (current != null && guard++ < 100) {
            path.add(0, current);
            current = current.getParentId() == null ? null : pageRepository.findById(current.getParentId()).orElse(null);
        }
        return path;
    }

    @Transactional
    public StudyPage create(Long parentId, String title, String subject, String content) {
        if (parentId != null && !pageRepository.existsById(parentId)) {
            throw new IllegalArgumentException("Página-mãe não encontrada.");
        }
        StudyPage page = new StudyPage();
        page.setParentId(parentId);
        page.setTitle(title(title));
        page.setSubject(blankToNull(subject));
        page.setContent(HtmlSanitizer.sanitize(content));
        page.setPosition(pageRepository.maxPosition(parentId) + 1);
        return pageRepository.save(page);
    }

    /**
     * Gravacao do editor (salvamento automatico). {@code expectedVersion} e a versao que o editor carregou; se
     * outra gravacao entrou no meio, recusa para nao apagar as alteracoes da outra aba.
     */
    @Transactional
    public SaveResult saveContent(Long id, String title, String content, int expectedVersion) {
        StudyPage page = pageRepository.findById(id).orElseThrow();
        if (page.getVersion() != expectedVersion) {
            throw new StaleVersionException(page.getVersion());
        }
        page.setTitle(title(title));
        page.setContent(HtmlSanitizer.sanitize(content));
        page.setVersion(page.getVersion() + 1);
        page.setUpdatedAt(LocalDateTime.now());
        pageRepository.save(page);
        return new SaveResult(page.getVersion(), page.getUpdatedAt());
    }

    /** Disciplina e pagina-mae. Mover uma pagina para dentro dela mesma (ou de uma filha) e recusado. */
    @Transactional
    public StudyPage updateDetails(Long id, String subject, Long parentId) {
        StudyPage page = pageRepository.findById(id).orElseThrow();
        if (!Objects.equals(page.getParentId(), parentId)) {
            if (parentId != null && descendantIds(id).contains(parentId)) {
                throw new IllegalArgumentException("Uma página não pode ficar dentro dela mesma ou de uma subpágina sua.");
            }
            if (parentId != null && !pageRepository.existsById(parentId)) {
                throw new IllegalArgumentException("Página-mãe não encontrada.");
            }
            page.setParentId(parentId);
            page.setPosition(pageRepository.maxPosition(parentId) + 1);
        }
        page.setSubject(blankToNull(subject));
        page.setUpdatedAt(LocalDateTime.now());
        return pageRepository.save(page);
    }

    /** Sobe ou desce a pagina entre as irmas. */
    @Transactional
    public void move(Long id, int direction) {
        StudyPage page = pageRepository.findById(id).orElseThrow();
        List<StudyPage> siblings = new ArrayList<>(page.getParentId() == null
                ? pageRepository.findAll().stream().filter(p -> p.getParentId() == null).toList()
                : pageRepository.findByParentId(page.getParentId()));
        siblings.sort(ORDER);
        int index = siblings.indexOf(page);
        int target = index + Integer.signum(direction);
        if (index < 0 || target < 0 || target >= siblings.size()) {
            return;
        }
        siblings.add(target, siblings.remove(index));
        for (int i = 0; i < siblings.size(); i++) {
            siblings.get(i).setPosition(i + 1);
        }
        pageRepository.saveAll(siblings);
    }

    /** Pagina e todas as subpaginas, em qualquer nivel (sem incluir a propria). */
    public List<Long> descendantIds(Long id) {
        Map<Long, List<Long>> byParent = new HashMap<>();
        for (StudyPage p : pageRepository.findAll()) {
            if (p.getParentId() != null) {
                byParent.computeIfAbsent(p.getParentId(), k -> new ArrayList<>()).add(p.getId());
            }
        }
        List<Long> result = new ArrayList<>();
        List<Long> pending = new ArrayList<>(byParent.getOrDefault(id, List.of()));
        while (!pending.isEmpty()) {
            Long next = pending.remove(0);
            if (!result.contains(next)) {
                result.add(next);
                pending.addAll(byParent.getOrDefault(next, List.of()));
            }
        }
        return result;
    }

    /** Apaga a pagina com as subpaginas; tarefas ligadas a elas continuam, sem o vinculo. */
    @Transactional
    public int delete(Long id) {
        List<Long> ids = new ArrayList<>(descendantIds(id));
        ids.add(id);
        taskRepository.unlinkPages(ids);
        pageRepository.deleteAllById(ids);
        return ids.size();
    }

    private static String title(String title) {
        String t = title == null ? "" : title.strip();
        return t.length() > MAX_TITLE ? t.substring(0, MAX_TITLE) : t;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
