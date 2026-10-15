package com.pdf2questao.study;

import com.pdf2questao.question.QuestionRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.TreeSet;

/** Disciplinas sugeridas nos campos de disciplina: as das questoes, paginas e tarefas (o campo continua livre). */
@Component
public class StudySubjects {

    private final QuestionRepository questionRepository;
    private final StudyPageRepository pageRepository;
    private final StudyTaskRepository taskRepository;

    public StudySubjects(QuestionRepository questionRepository, StudyPageRepository pageRepository,
                         StudyTaskRepository taskRepository) {
        this.questionRepository = questionRepository;
        this.pageRepository = pageRepository;
        this.taskRepository = taskRepository;
    }

    public List<String> all() {
        TreeSet<String> subjects = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        subjects.addAll(questionRepository.findDistinctSubjects());
        subjects.addAll(pageRepository.findDistinctSubjects());
        subjects.addAll(taskRepository.findDistinctSubjects());
        return List.copyOf(subjects);
    }
}
