package com.pdf2questao.study;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Caixa de entrada: anotar rapido agora, organizar depois. */
@Service
public class InboxService {

    private final InboxNoteRepository noteRepository;
    private final StudyPageService pageService;

    public InboxService(InboxNoteRepository noteRepository, StudyPageService pageService) {
        this.noteRepository = noteRepository;
        this.pageService = pageService;
    }

    public List<InboxNote> all() {
        return noteRepository.findAllByOrderByCreatedAtDesc();
    }

    public long count() {
        return noteRepository.count();
    }

    @Transactional
    public InboxNote add(String content, String subject) {
        String text = StudyPageService.blankToNull(content);
        if (text == null) {
            throw new IllegalArgumentException("Escreva alguma coisa na anotação.");
        }
        InboxNote note = new InboxNote();
        note.setContent(text);
        note.setSubject(StudyPageService.blankToNull(subject));
        return noteRepository.save(note);
    }

    @Transactional
    public void setSubject(Long id, String subject) {
        InboxNote note = noteRepository.findById(id).orElseThrow();
        note.setSubject(StudyPageService.blankToNull(subject));
        noteRepository.save(note);
    }

    /**
     * Transforma a anotacao numa pagina (raiz ou dentro de {@code parentId}). A primeira linha vira o titulo;
     * a anotacao sai da caixa de entrada na mesma transacao.
     */
    @Transactional
    public StudyPage toPage(Long id, Long parentId) {
        InboxNote note = noteRepository.findById(id).orElseThrow();
        String text = note.getContent().strip();
        String firstLine = text.lines().findFirst().orElse("").strip();
        String title = firstLine.length() > 80 ? firstLine.substring(0, 80).strip() + "…" : firstLine;
        String body = firstLine.length() > 80 ? text : text.substring(text.indexOf(firstLine) + firstLine.length());
        StudyPage page = pageService.create(parentId, title, note.getSubject(), HtmlSanitizer.fromPlainText(body));
        noteRepository.delete(note);
        return page;
    }

    @Transactional
    public void delete(Long id) {
        noteRepository.deleteById(id);
    }
}
