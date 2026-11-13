package com.pdf2questao.question;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Questoes REAIS da banca importadas no sistema (provas e listas de questoes com gabarito oficial), usadas como
 * modelo para escrever questoes ineditas. Quiz e simulado consultam primeiro o que esta aqui: quando o sistema tem
 * modelos da banca para a disciplina, as ferramentas exigem que a questao nova cite os ids dos modelos lidos.
 */
@Service
public class BancaReferences {

    /** Fontes das questoes escritas pelo Claude: nao contam como questao real da banca. */
    public static final Set<String> GENERATED_SOURCES = Set.of("Simulado do Claude", "Treino no Claude");

    private static final Set<String> STOPWORDS = Set.of("de", "da", "do", "das", "dos", "e", "a", "o", "as", "os",
            "em", "na", "no", "nas", "nos", "para", "por", "com", "sem", "um", "uma", "ao", "aos", "que", "se", "sua",
            "seu", "the", "and", "of", "lei", "nº", "n");
    private static final Pattern LOCAL_QUESTION = Pattern.compile(
            "(?i)(?:quest[aã]o\\s+(?:local\\s+)?|q\\s*)?#\\s*(\\d+)");
    private static final Pattern LOCAL_EXAM = Pattern.compile("(?i)prova\\s+local\\s*#\\s*(\\d+)");

    private final QuestionRepository questionRepository;

    public BancaReferences(QuestionRepository questionRepository) {
        this.questionRepository = questionRepository;
    }

    /** Questao real candidata a modelo, com a relevancia para o assunto pedido (0 = so da disciplina). */
    public record Match(Question question, int relevancia) {
    }

    public List<Question> realOfBanca(String banca) {
        if (banca == null || banca.isBlank()) {
            return List.of();
        }
        return questionRepository.findRealOfBanca(banca.strip());
    }

    /** Quantas questoes reais da banca o sistema tem na disciplina (nome, apelido ou sigla). */
    public long available(String banca, String subject) {
        if (subject == null || subject.isBlank()) {
            return realOfBanca(banca).size();
        }
        return realOfBanca(banca).stream().filter(q -> sameSubject(subject, q.getSubject())).count();
    }

    /**
     * As questoes reais mais parecidas com o assunto: da disciplina (se informada), ordenadas pelas palavras do
     * assunto que aparecem no assunto/subtopicos (peso 3) e no enunciado/texto (peso 1).
     */
    public List<Match> search(String banca, String subject, String topic, int limit, Collection<Long> exclude) {
        List<String> all = tokens(topic);
        // Expressoes de duas palavras do assunto ("substituição tributária", "base cálculo"): valem mais que palavras
        // soltas, que aparecem em qualquer questao da disciplina.
        List<Pattern> phrases = new ArrayList<>();
        for (int i = 0; i + 1 < all.size(); i++) {
            phrases.add(Pattern.compile("\\b" + Pattern.quote(root(all.get(i))) + "\\w*(?: \\w+)? "
                    + Pattern.quote(root(all.get(i + 1)))));
        }
        List<String> terms = new ArrayList<>(all);
        if (subject != null) {
            // Palavras do nome da disciplina nao distinguem assunto dentro dela ("tributária" em Direito Tributário).
            List<String> subjectWords = tokens(subject);
            terms.removeIf(t -> contains(subjectWords, t));
        }
        List<Match> out = new ArrayList<>();
        for (Question q : realOfBanca(banca)) {
            if (exclude != null && exclude.contains(q.getId())) {
                continue;
            }
            if (subject != null && !subject.isBlank() && !sameSubject(subject, q.getSubject())) {
                continue;
            }
            int score = 0;
            if (!terms.isEmpty()) {
                List<String> label = tokens(String.join(" ", nz(q.getTopic()), nz(q.getSubtopics()), nz(q.getTags())));
                List<String> body = tokens(nz(q.getStatement()));
                for (String t : terms) {
                    if (contains(label, t)) {
                        score += 3;
                    } else if (contains(body, t)) {
                        score += 1;
                    }
                }
                String text = norm(String.join(" ", nz(q.getTopic()), nz(q.getSubtopics()), nz(q.getStatement())));
                for (Pattern phrase : phrases) {
                    if (phrase.matcher(text).find()) {
                        score += 4;
                    }
                }
                if (score == 0 && subject == null) {
                    continue;   // sem disciplina, so entra o que tem a ver com o assunto
                }
            }
            out.add(new Match(q, score));
        }
        java.util.Collections.shuffle(out);   // empates em ordem variada: cada consulta traz modelos diferentes
        out.sort(Comparator.comparingInt(Match::relevancia).reversed());
        return out.subList(0, Math.min(limit, out.size()));
    }

    /** Ids "questão local #123" (ou "#123") citados num texto de referencias. "prova local #id" nao conta. */
    public static List<Long> questionIds(String text) {
        Set<Long> ids = new LinkedHashSet<>();
        if (text == null) {
            return List.of();
        }
        String withoutExams = LOCAL_EXAM.matcher(text).replaceAll(" ");
        Matcher m = LOCAL_QUESTION.matcher(withoutExams);
        while (m.find()) {
            ids.add(Long.parseLong(m.group(1)));
        }
        return new ArrayList<>(ids);
    }

    /** Ids "prova local #12" citados em fontes. */
    public static List<Long> examIds(String text) {
        Set<Long> ids = new LinkedHashSet<>();
        if (text != null) {
            Matcher m = LOCAL_EXAM.matcher(text);
            while (m.find()) {
                ids.add(Long.parseLong(m.group(1)));
            }
        }
        return new ArrayList<>(ids);
    }

    /** Dos ids informados, os que sao questao real da banca (os demais nao valem como modelo). */
    public List<Long> validQuestions(String banca, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return questionRepository.findAllById(ids).stream()
                .filter(q -> isReal(q) && banca != null && banca.strip().equalsIgnoreCase(nz(q.getOrganization()).strip()))
                .map(Question::getId).toList();
    }

    /** Provas reais da banca importadas (ids de import_job). */
    public List<Long> realExams(String banca) {
        if (banca == null || banca.isBlank()) {
            return List.of();
        }
        return questionRepository.bancaJobs(banca.strip()).stream().map(r -> ((Number) r[0]).longValue()).toList();
    }

    public static boolean isReal(Question q) {
        return q.getCorrectAnswer() != null && (q.getSource() == null || !GENERATED_SOURCES.contains(q.getSource()));
    }

    // ---- disciplina e palavras ----

    /**
     * Mesma disciplina com nomes diferentes: "Português" = "Língua Portuguesa", "AFO" = "Administração Financeira e
     * Orçamentária", "Direito Tributário" = "Direito Tributário Avançado"; "Direito Tributário" != "Direito
     * Constitucional" (todas as palavras do nome mais curto precisam aparecer no outro).
     */
    public static boolean sameSubject(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        List<String> ta = tokens(a);
        List<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return false;
        }
        // Sigla de uma palavra so ("AFO") contra as iniciais do nome longo.
        if (ta.size() == 1 && tb.size() >= 2 && ta.get(0).equals(acronym(tb))
                || tb.size() == 1 && ta.size() >= 2 && tb.get(0).equals(acronym(ta))) {
            return true;
        }
        List<String> shorter = ta.size() <= tb.size() ? ta : tb;
        List<String> longer = shorter == ta ? tb : ta;
        return shorter.stream().allMatch(t -> contains(longer, t));
    }

    private static String acronym(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        tokens.forEach(t -> sb.append(t.charAt(0)));
        return sb.toString();
    }

    /** Mesma raiz: as 5 primeiras letras ("portug" casa "português" e "portuguesa"). */
    private static boolean contains(List<String> words, String t) {
        String root = root(t);
        return words.stream().anyMatch(w -> w.equals(t) || (root.length() >= 4 && w.startsWith(root)));
    }

    private static String root(String t) {
        return t.length() > 5 ? t.substring(0, 5) : t;
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String w : norm(s).split(" ")) {
            if (w.length() >= 3 && !STOPWORDS.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    static String norm(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
