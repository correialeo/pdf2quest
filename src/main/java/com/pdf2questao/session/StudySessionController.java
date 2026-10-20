package com.pdf2questao.session;

import com.pdf2questao.importer.ImportJobRepository;
import com.pdf2questao.importer.PdfStorage;
import com.pdf2questao.question.PassageRepository;
import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Controller
public class StudySessionController {

    private final StudySessionService sessionService;
    private final QuestionRepository questionRepository;
    private final PassageRepository passageRepository;
    private final ImportJobRepository importJobRepository;
    private final PdfStorage pdfStorage;

    public StudySessionController(StudySessionService sessionService, QuestionRepository questionRepository,
                                  PassageRepository passageRepository, ImportJobRepository importJobRepository,
                                  PdfStorage pdfStorage) {
        this.sessionService = sessionService;
        this.questionRepository = questionRepository;
        this.passageRepository = passageRepository;
        this.importJobRepository = importJobRepository;
        this.pdfStorage = pdfStorage;
    }

    @GetMapping("/simulado")
    public String simuladoForm() {
        return "simulado";
    }

    @PostMapping("/simulado/iniciar")
    public String iniciarSimulado(@RequestParam int qtyGerais, @RequestParam int qtyEspecificas,
                                   @RequestParam(defaultValue = "false") boolean keepExamOrder) {
        StudySession session = sessionService.startSimulado(qtyGerais, qtyEspecificas, keepExamOrder);
        return "redirect:/resolver/" + session.getId() + "?index=0";
    }

    @PostMapping("/import/{importJobId}/simulado")
    public String iniciarProva(@PathVariable Long importJobId) {
        StudySession session = sessionService.startProva(importJobId);
        return "redirect:/resolver/" + session.getId() + "?index=0";
    }

    @GetMapping("/resolver/{id}")
    public String resolver(@PathVariable Long id, @RequestParam(defaultValue = "0") int index, Model model) {
        StudySession session = sessionService.getSession(id);
        if (session.getFinishedAt() != null) {
            return "redirect:/resultado/" + id;   // voltar no navegador depois de finalizar nao reabre a prova
        }
        List<Long> ids = session.questionIds();
        if (index < 0) {
            index = 0;
        }
        if (index >= ids.size()) {
            index = ids.size() - 1;
        }
        Question question = questionRepository.findById(ids.get(index)).orElseThrow();

        model.addAttribute("studySession", session);
        model.addAttribute("elapsedMillis", session.activeMillisOrZero());
        model.addAttribute("limitMillis", session.getTimeLimitMinutes() == null ? null
                : session.getTimeLimitMinutes() * 60_000L);
        model.addAttribute("question", question);
        model.addAttribute("attempt", sessionService.attemptOf(id, question.getId()).orElse(null));
        model.addAttribute("passage", question.getPassageId() == null ? null
                : passageRepository.findById(question.getPassageId()).orElse(null));
        model.addAttribute("hasPdf", question.getImportJobId() != null && importJobRepository
                .findById(question.getImportJobId()).map(pdfStorage::exists).orElse(false));
        model.addAttribute("index", index);
        model.addAttribute("questionIds", ids);
        model.addAttribute("answered", sessionService.answeredQuestionIds(id));
        model.addAttribute("total", ids.size());
        model.addAttribute("hasPrevious", index > 0);
        model.addAttribute("hasNext", index < ids.size() - 1);
        return "resolver";
    }

    @PostMapping("/resolver/{id}/responder")
    @ResponseBody
    public void responder(@PathVariable Long id, @RequestBody AnswerRequest request) {
        sessionService.answer(id, request.questionId(), request.selectedAnswer(), request.elapsedMillis(),
                request.sessionMillis(), request.guess());
    }

    /** Tempo contado pelo navegador (ao sair da questao, a cada meio minuto e antes de finalizar). */
    @PostMapping("/resolver/{id}/tempo")
    @ResponseBody
    public void tempo(@PathVariable Long id, @RequestBody TimeRequest request) {
        sessionService.addTime(id, request.questionId(), request.elapsedMillis(), request.sessionMillis());
    }

    @PostMapping("/resolver/{id}/finalizar")
    public String finalizar(@PathVariable Long id) {
        sessionService.finish(id);
        return "redirect:/resultado/" + id;
    }

    /** Acertos por disciplina das questoes corrigidas da sessao, piores primeiro (o que estudar). */
    private static List<com.pdf2questao.dashboard.SubjectStat> bySubject(List<ReviewItem> review) {
        java.util.Map<String, long[]> counts = new java.util.LinkedHashMap<>();
        for (ReviewItem r : review) {
            if (r.correct() == null) {
                continue;
            }
            String subject = r.question().getSubject() == null ? "Sem disciplina" : r.question().getSubject();
            long[] c = counts.computeIfAbsent(subject, k -> new long[2]);
            c[0] += r.correct() ? 1 : 0;
            c[1]++;
        }
        return counts.entrySet().stream()
                .map(e -> new com.pdf2questao.dashboard.SubjectStat(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted(java.util.Comparator.comparingDouble(com.pdf2questao.dashboard.SubjectStat::percent))
                .toList();
    }

    @PostMapping("/resolver/{id}/descartar")
    public String descartar(@PathVariable Long id) {
        sessionService.discard(id);
        return "redirect:/dashboard";
    }

    @GetMapping("/resultado/{id}")
    public String resultado(@PathVariable Long id, Model model) {
        StudySession session = sessionService.getSession(id);
        long totalQuestions = session.questionIds().size();
        long avgMillis = (totalQuestions == 0 || session.getTotalElapsedMillis() == null)
                ? 0 : session.getTotalElapsedMillis() / totalQuestions;

        model.addAttribute("studySession", session);
        model.addAttribute("total", totalQuestions);
        model.addAttribute("avgMillis", avgMillis);
        List<ReviewItem> review = sessionService.review(id);
        model.addAttribute("review", review);
        model.addAttribute("guessedCorrect", review.stream()
                .filter(r -> Boolean.TRUE.equals(r.guessed()) && Boolean.TRUE.equals(r.correct())).count());
        model.addAttribute("guessedWrong", review.stream()
                .filter(r -> Boolean.TRUE.equals(r.guessed()) && Boolean.FALSE.equals(r.correct())).count());
        model.addAttribute("score", sessionService.scoreResult(session));
        model.addAttribute("bySubject", bySubject(review));
        model.addAttribute("blank", review.stream().filter(r -> r.selectedAnswer() == null).count());
        model.addAttribute("overtime", sessionService.overtimeCount(id));
        return "resultado";
    }

    public record AnswerRequest(Long questionId, String selectedAnswer, Long elapsedMillis, Long sessionMillis,
                                Boolean guess) {
    }

    public record TimeRequest(Long questionId, Long elapsedMillis, Long sessionMillis) {
    }
}
