package com.pdf2questao.flashcard;

import com.pdf2questao.aula.Lesson;
import com.pdf2questao.aula.LessonService;
import com.pdf2questao.aula.LessonSlides;
import com.pdf2questao.aula.Quiz;
import com.pdf2questao.aula.QuizRepository;
import com.pdf2questao.scoring.ScoringCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Flashcards da revisao: recordacao ativa (sem alternativas) antes das questoes. Cada cartao e de um conceito
 * ({#id}) de uma aula, e o verso leva ao slide dele. A autoavaliacao (errei, dificil, facil) so ordena as questoes e
 * orienta o agente: quem decide se a revisao passou continua sendo o quiz.
 */
@Service
public class FlashcardService {

    public static final List<String> RATINGS = List.of("errei", "dificil", "facil");
    static final int MAX_BACK = 220;
    static final int MAX_CARDS_PER_CONCEPT = 3;
    static final int MAX_CARDS_PER_QUIZ = 40;
    static final String CLOZE = "___";

    private final FlashcardRepository cards;
    private final FlashcardRatingRepository ratings;
    private final LessonService lessonService;
    private final QuizRepository quizRepository;

    public FlashcardService(FlashcardRepository cards, FlashcardRatingRepository ratings, LessonService lessonService,
                            QuizRepository quizRepository) {
        this.cards = cards;
        this.ratings = ratings;
        this.lessonService = lessonService;
        this.quizRepository = quizRepository;
    }

    public record NewCard(String conceito, String tipo, String frente, String verso) {
    }

    /** Conceitos da aula que precisam de cartao: os dos slides de conteudo (resumo e quadro final nao). */
    static List<String> requiredConcepts(String content) {
        return LessonSlides.parse(content).stream()
                .filter(s -> s.concept() != null)
                .filter(s -> !ScoringCalculator.normalize(s.title()).matches("(resumo|quadro|fontes).*"))
                .map(LessonSlides.Slide::concept).toList();
    }

    /**
     * Troca os cartoes da aula pelos enviados (as autoavaliacoes antigas saem junto). Todo conceito de slide de
     * conteudo precisa de ao menos um cartao; o verso e curto e a frente nao entrega a resposta.
     */
    @Transactional
    public List<Flashcard> replace(Long lessonId, List<NewCard> sent) {
        Lesson lesson = lessonService.find(lessonId);
        Map<String, LessonSlides.Concept> concepts = LessonSlides.concepts(lesson.getContent());
        if (concepts.isEmpty()) {
            throw new IllegalArgumentException("A aula " + lessonId + " não declara conceitos ({#id} nos títulos dos "
                    + "slides). Reescreva-a com reescrever_aula antes de criar os flashcards.");
        }
        if (sent == null || sent.isEmpty()) {
            throw new IllegalArgumentException("Envie os cartões (ao menos um por conceito da aula).");
        }
        List<String> problems = new ArrayList<>();
        Map<String, Integer> perConcept = new LinkedHashMap<>();
        Set<String> fronts = new LinkedHashSet<>();
        for (int i = 0; i < sent.size(); i++) {
            NewCard c = sent.get(i);
            String label = "Cartão " + (i + 1);
            String concept = c.conceito() == null ? "" : c.conceito().strip().replaceAll("^\\{?#?|}$", "");
            String kind = c.tipo() == null || c.tipo().isBlank() ? Flashcard.PERGUNTA
                    : ScoringCalculator.normalize(c.tipo()).strip();
            String front = c.frente() == null ? "" : c.frente().strip();
            String back = c.verso() == null ? "" : c.verso().strip();
            if (!concepts.containsKey(concept)) {
                problems.add(label + ": conceito {#" + concept + "} não está na aula");
                continue;
            }
            if (!List.of(Flashcard.PERGUNTA, Flashcard.LACUNA, Flashcard.CONTRASTE).contains(kind)) {
                problems.add(label + ": tipo é pergunta, lacuna ou contraste");
            }
            if (front.length() < 10 || back.isEmpty()) {
                problems.add(label + ": frente (pergunta de recordação) e verso (resposta) são obrigatórios");
                continue;
            }
            if (back.length() > MAX_BACK) {
                problems.add(label + ": verso com " + back.length() + " caracteres; resposta curta, até " + MAX_BACK
                        + " (o cartão não é resumo)");
            }
            if (Flashcard.LACUNA.equals(kind) && !front.contains(CLOZE)) {
                problems.add(label + ": lacuna precisa de \"" + CLOZE + "\" na frente, no lugar do que o aluno lembra");
            }
            if (givesAway(front, back)) {
                problems.add(label + ": a frente já contém a resposta do verso");
            }
            if (!fronts.add(ScoringCalculator.normalize(front))) {
                problems.add(label + ": frente repetida");
            }
            perConcept.merge(concept, 1, Integer::sum);
        }
        perConcept.forEach((k, n) -> {
            if (n > MAX_CARDS_PER_CONCEPT) {
                problems.add("{#" + k + "} tem " + n + " cartões; no máximo " + MAX_CARDS_PER_CONCEPT
                        + " por conceito (o essencial para lembrar na prova)");
            }
        });
        List<String> missing = requiredConcepts(lesson.getContent()).stream()
                .filter(k -> !perConcept.containsKey(k)).toList();
        if (!missing.isEmpty()) {
            problems.add("faltam cartões para " + missing.stream().map(k -> "{#" + k + "} (slide "
                    + concepts.get(k).slide() + ": " + concepts.get(k).slideTitle() + ")").collect(Collectors.joining(", ")));
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems.stream().limit(8).toList())
                    + (problems.size() > 8 ? "; ..." : "") + ".");
        }
        List<Flashcard> old = cards.findByLessonIdOrderById(lessonId);
        ratings.deleteByFlashcardIdIn(old.stream().map(Flashcard::getId).toList());
        cards.deleteAll(old);
        List<Flashcard> saved = new ArrayList<>();
        for (NewCard c : sent) {
            Flashcard f = new Flashcard();
            f.setConcursoId(lesson.getConcursoId());
            f.setLessonId(lessonId);
            f.setConcept(c.conceito().strip().replaceAll("^\\{?#?|}$", ""));
            f.setKind(c.tipo() == null || c.tipo().isBlank() ? Flashcard.PERGUNTA : ScoringCalculator.normalize(c.tipo()).strip());
            f.setFront(c.frente().strip());
            f.setBack(c.verso().strip());
            saved.add(cards.save(f));
        }
        return saved;
    }

    /** A frente traz o verso inteiro (ou o numero/percentual que e a resposta). */
    static boolean givesAway(String front, String back) {
        String f = ScoringCalculator.normalize(front);
        String b = ScoringCalculator.normalize(back).replaceAll("[.;:!]+$", "").strip();
        if (b.length() >= 3 && f.contains(b)) {
            return true;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+(?:[.,]\\d+)?\\s*%?").matcher(back);
        List<String> numbers = new ArrayList<>();
        while (m.find()) {
            numbers.add(m.group().replace(" ", ""));
        }
        // Resposta so numerica ("60%", "90 dias"): a frente nao pode trazer o numero.
        return !numbers.isEmpty() && b.replaceAll("[\\d.,%\\s]", "").length() <= 12
                && numbers.stream().allMatch(n -> front.replace(" ", "").contains(n));
    }

    public List<Flashcard> ofLesson(Long lessonId) {
        return cards.findByLessonIdOrderById(lessonId);
    }

    /** Liga os cartoes ao quiz (aparecem antes das questoes). */
    @Transactional
    public void attach(Quiz quiz, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.size() > MAX_CARDS_PER_QUIZ) {
            throw new IllegalArgumentException("No máximo " + MAX_CARDS_PER_QUIZ + " flashcards por quiz.");
        }
        List<Long> unknown = distinct.stream().filter(id -> !cards.existsById(id)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Flashcards inexistentes: " + unknown + " (veja flashcards_da_revisao).");
        }
        quiz.setFlashcards(distinct.stream().map(String::valueOf).collect(Collectors.joining(",")));
        quizRepository.save(quiz);
    }

    /** Cartao como a tela e o agente o veem: com o slide do conceito e a ultima autoavaliacao no quiz. */
    public record CardView(Long id, Long aulaId, String aula, String conceito, String tipo, String frente, String verso,
                           Integer slide, String slideTitulo, String link, String avaliacao) {
    }

    public List<CardView> forQuiz(Quiz quiz) {
        List<Long> ids = quiz.flashcardIds();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, String> last = new LinkedHashMap<>();
        ratings.findByQuizIdOrderByRatedAt(quiz.getId()).forEach(r -> last.put(r.getFlashcardId(), r.getRating()));
        Map<Long, Flashcard> byId = cards.findAllById(ids).stream()
                .collect(Collectors.toMap(Flashcard::getId, f -> f));
        return ids.stream().map(byId::get).filter(Objects::nonNull).map(f -> view(f, last.get(f.getId()))).toList();
    }

    CardView view(Flashcard f, String rating) {
        Lesson lesson = lessonService.find(f.getLessonId());
        LessonSlides.Concept c = LessonSlides.concepts(lesson.getContent()).get(f.getConcept());
        return new CardView(f.getId(), f.getLessonId(), lesson.getTitle(), f.getConcept(), f.getKind(), f.getFront(),
                f.getBack(), c == null ? null : c.slide(), c == null ? null : c.slideTitle(),
                "/aulas/" + f.getLessonId() + (c == null ? "" : "#c-" + f.getConcept()), rating);
    }

    @Transactional
    public FlashcardRating rate(Long quizId, Long cardId, String rating) {
        String r = rating == null ? "" : ScoringCalculator.normalize(rating).strip();
        if (!RATINGS.contains(r)) {
            throw new IllegalArgumentException("Avaliação: errei, dificil ou facil.");
        }
        Quiz quiz = lessonService.quiz(quizId);
        if (!quiz.flashcardIds().contains(cardId)) {
            throw new IllegalArgumentException("O cartão " + cardId + " não é deste quiz.");
        }
        FlashcardRating fr = new FlashcardRating();
        fr.setFlashcardId(cardId);
        fr.setQuizId(quizId);
        fr.setRating(r);
        return ratings.save(fr);
    }

    /** Cartoes das aulas da disciplina (ou de uma aula), com a ultima autoavaliacao em qualquer quiz. */
    public record DueCards(List<CardView> cartoes, List<LessonWithout> aulasSemCartoes) {
    }

    public record LessonWithout(Long aulaId, String titulo, boolean temConceitos) {
    }

    public DueCards forReview(Long lessonId, String subject) {
        List<Lesson> lessons = lessonService.visible().stream()
                .filter(l -> lessonId == null || lessonId.equals(l.getId()))
                .filter(l -> sameSubject(subject, l.getSubject()))
                .toList();
        if (lessonId != null && lessons.isEmpty()) {
            lessons = List.of(lessonService.find(lessonId));
        }
        List<Flashcard> found = cards.findByLessonIdInOrderById(lessons.stream().map(Lesson::getId).toList());
        Map<Long, String> last = new LinkedHashMap<>();
        ratings.findByFlashcardIdInOrderByRatedAt(found.stream().map(Flashcard::getId).toList())
                .forEach(r -> last.put(r.getFlashcardId(), r.getRating()));
        Set<Long> withCards = found.stream().map(Flashcard::getLessonId).collect(Collectors.toSet());
        return new DueCards(found.stream().map(f -> view(f, last.get(f.getId()))).toList(),
                lessons.stream().filter(l -> !withCards.contains(l.getId()))
                        .map(l -> new LessonWithout(l.getId(), l.getTitle(),
                                !LessonSlides.concepts(l.getContent()).isEmpty())).toList());
    }

    /** Resumo das autoavaliacoes do quiz, para o resultado: conceito -> avaliacao (a pior, se varios cartoes). */
    public Map<String, String> conceptRatings(Quiz quiz) {
        Map<String, String> out = new LinkedHashMap<>();
        for (CardView c : forQuiz(quiz)) {
            if (c.avaliacao() == null) {
                continue;
            }
            String key = c.aulaId() + "#" + c.conceito();
            String prev = out.get(key);
            if (prev == null || RATINGS.indexOf(c.avaliacao()) < RATINGS.indexOf(prev)) {
                out.put(key, c.avaliacao());
            }
        }
        return out;
    }

    /** Disciplina pedida (vazia = todas) casa com a da aula por parte do nome, sem acento. */
    static boolean sameSubject(String wanted, String subject) {
        if (wanted == null || wanted.isBlank()) {
            return true;
        }
        if (subject == null || subject.isBlank()) {
            return false;
        }
        String w = ScoringCalculator.normalize(wanted);
        String s = ScoringCalculator.normalize(subject);
        return s.contains(w) || w.contains(s);
    }
}
