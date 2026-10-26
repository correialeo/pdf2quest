package com.pdf2questao.question;

import com.pdf2questao.session.AttemptRepository;
import com.pdf2questao.session.StudySession;
import com.pdf2questao.session.StudySessionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import com.pdf2questao.importer.ImportJobRepository;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class ErrorNotebookController {

    private final QuestionRepository questionRepository;
    private final StudySessionService sessionService;
    private final AttemptRepository attemptRepository;
    private final ImportJobRepository importJobRepository;

    private final com.pdf2questao.concurso.Workspace workspace;

    public ErrorNotebookController(QuestionRepository questionRepository, StudySessionService sessionService,
                                   AttemptRepository attemptRepository, ImportJobRepository importJobRepository,
                                   com.pdf2questao.concurso.Workspace workspace) {
        this.workspace = workspace;
        this.importJobRepository = importJobRepository;
        this.questionRepository = questionRepository;
        this.sessionService = sessionService;
        this.attemptRepository = attemptRepository;
    }

    @GetMapping("/caderno-erros")
    public String list(@org.springframework.web.bind.annotation.RequestParam(required = false) Long prova, Model model) {
        List<Question> all = questionRepository.findErrorNotebook(workspace.currentId());
        // Contagem por prova para os filtros no topo da tela.
        Map<Long, Long> perProva = new LinkedHashMap<>();
        for (Question q : all) {
            if (q.getImportJobId() != null) {
                perProva.merge(q.getImportJobId(), 1L, Long::sum);
            }
        }
        model.addAttribute("provas", importJobRepository.findAllById(perProva.keySet()).stream()
                .sorted(java.util.Comparator.comparing(com.pdf2questao.importer.ImportJob::getId)).toList());
        model.addAttribute("perProva", perProva);
        model.addAttribute("totalErrors", all.size());
        model.addAttribute("selectedProva", prova);
        model.addAttribute("questions", prova == null ? all
                : all.stream().filter(q -> prova.equals(q.getImportJobId())).toList());
        Map<Long, String> wrongAnswers = new HashMap<>();
        for (Object[] row : attemptRepository.findLatestWrongAnswers()) {
            wrongAnswers.put(((Number) row[0]).longValue(), (String) row[1]);
        }
        model.addAttribute("wrongAnswers", wrongAnswers);
        return "caderno-erros";
    }

    @PostMapping("/caderno-erros/refazer")
    public String refazer(@org.springframework.web.bind.annotation.RequestParam(required = false) Long prova) {
        java.util.List<Long> ids = questionRepository.findErrorNotebook(workspace.currentId()).stream()
                .filter(q -> prova == null || prova.equals(q.getImportJobId()))
                .map(Question::getId)
                .toList();
        StudySession session = sessionService.startRefazer(ids);
        return "redirect:/resolver/" + session.getId() + "?index=0";
    }
}
