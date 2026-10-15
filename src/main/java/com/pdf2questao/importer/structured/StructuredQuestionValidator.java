package com.pdf2questao.importer.structured;

import com.pdf2questao.importer.parser.support.RichText;
import com.pdf2questao.question.QuestionCategory;
import com.pdf2questao.question.QuestionType;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Regras de conteudo das questoes importadas por arquivo, iguais para JSON e CSV. Nada e descartado em silencio:
 * cada questao sai com status, erros e avisos para o usuario revisar antes de gravar.
 */
public class StructuredQuestionValidator {

    /** O que ja esta no banco, para detectar reimportacao e possiveis duplicatas. */
    public interface ExistingQuestions {
        Long idByExternalId(String externalId);

        Long idByStatement(String normalizedStatement);
    }

    private static final String LETTERS = "ABCDE";
    static final int MAX_ID_LENGTH = 255;

    public List<PreviewItem> validate(List<StructuredQuestion> questions, ExistingQuestions existing) {
        List<PreviewItem> items = new ArrayList<>();
        Map<String, List<PreviewItem>> byId = new LinkedHashMap<>();
        Map<String, PreviewItem> byStatement = new HashMap<>();

        for (StructuredQuestion q : questions) {
            PreviewItem item = new PreviewItem(q);
            items.add(item);
            q.readErrors.forEach(item::error);
            item.getWarnings().addAll(q.readWarnings);
            content(item);
            if (q.externalId != null) {
                byId.computeIfAbsent(q.externalId, k -> new ArrayList<>()).add(item);
            }
        }

        // IDs repetidos no proprio arquivo: nenhuma das copias entra, para o usuario decidir qual vale.
        byId.forEach((id, same) -> {
            if (same.size() > 1) {
                String where = String.join(", ", same.stream().map(i -> i.getSource().label).toList());
                same.forEach(i -> i.error("O ID \"" + id + "\" aparece mais de uma vez no arquivo (" + where + ")."));
            }
        });

        for (PreviewItem item : items) {
            if (item.getStatus() == PreviewItem.Status.INVALIDA) {
                continue;
            }
            StructuredQuestion q = item.getSource();
            if (q.externalId != null) {
                Long id = existing.idByExternalId(q.externalId);
                if (id != null) {
                    item.setStatus(PreviewItem.Status.JA_EXISTE);
                    item.setExistingQuestionId(id);
                    continue;
                }
            }
            String key = normalizeStatement(q.statement);
            Long sameStatement = existing.idByStatement(key);
            PreviewItem earlier = byStatement.putIfAbsent(key, item);
            if (sameStatement != null) {
                item.setStatus(PreviewItem.Status.POSSIVEL_DUPLICATA);
                item.setExistingQuestionId(sameStatement);
                item.setDuplicateOf("questão #" + sameStatement + " já cadastrada");
            } else if (earlier != null) {
                item.setStatus(PreviewItem.Status.POSSIVEL_DUPLICATA);
                item.setDuplicateOf(earlier.getSource().label + " deste arquivo");
            }
        }
        return items;
    }

    private void content(PreviewItem item) {
        StructuredQuestion q = item.getSource();
        if (q.statement == null) {
            item.error("Falta o enunciado.");
        }
        if (q.externalId != null && q.externalId.length() > MAX_ID_LENGTH) {
            item.error("O ID tem mais de " + MAX_ID_LENGTH + " caracteres.");
        }
        if (q.year != null && (q.year < 1900 || q.year > 2100)) {
            item.error("Ano " + q.year + " fora do esperado (1900 a 2100).");
        }
        if (q.category != null && category(q.category) == null) {
            item.error("Categoria \"" + q.category + "\" desconhecida: use \"geral\" ou \"especifico\".");
        }

        QuestionType type = type(q, item);
        if (type == null) {
            return;
        }
        item.setType(type);
        Map<String, String> alternatives = type == QuestionType.CERTO_ERRADO
                ? certoErrado(q, item) : multipleChoice(q, item);
        if (alternatives == null) {
            return;
        }
        item.setAlternatives(alternatives);

        if (q.annulled) {
            if (q.answer != null && !Answers.isAnnulledMarker(q.answer)) {
                item.getWarnings().add("Questão anulada: a resposta \"" + q.answer + "\" foi ignorada.");
            }
            return;
        }
        if (q.answer == null) {
            item.getWarnings().add("Sem resposta: a questão entra sem gabarito (dá para importar o gabarito depois).");
            return;
        }
        if (Answers.isAnnulledMarker(q.answer)) {
            q.annulled = true;
            return;
        }
        String letter = Answers.normalize(q.answer, type, alternatives.keySet());
        if (letter == null) {
            item.error(type == QuestionType.CERTO_ERRADO
                    ? "Resposta \"" + q.answer + "\" inválida para Certo/Errado: use Certo, Errado, C ou E."
                    : "Resposta \"" + q.answer + "\" não corresponde a nenhuma alternativa ("
                    + String.join(", ", alternatives.keySet()) + ").");
            return;
        }
        item.setAnswer(letter);
    }

    private QuestionType type(StructuredQuestion q, PreviewItem item) {
        if (q.type != null) {
            String t = Normalizer.normalize(q.type, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                    .toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            return switch (t) {
                case "multiplaescolha", "multipla", "objetiva", "me" -> QuestionType.MULTIPLA_ESCOLHA;
                case "certoerrado", "ce", "certoouerrado", "verdadeirofalso", "vf" -> QuestionType.CERTO_ERRADO;
                default -> {
                    item.error("Tipo \"" + q.type + "\" desconhecido: use \"multipla_escolha\" ou \"certo_errado\".");
                    yield null;
                }
            };
        }
        // Sem tipo: sem alternativas (ou so Certo/Errado) e item de Certo/Errado; com alternativas, multipla escolha.
        if (q.alternatives.isEmpty() || isCertoErradoPair(q.alternatives)) {
            return QuestionType.CERTO_ERRADO;
        }
        return QuestionType.MULTIPLA_ESCOLHA;
    }

    private Map<String, String> certoErrado(StructuredQuestion q, PreviewItem item) {
        if (!q.alternatives.isEmpty() && !isCertoErradoPair(q.alternatives)) {
            item.error("Questão Certo/Errado não deve ter alternativas próprias (encontradas "
                    + q.alternatives.size() + ").");
            return null;
        }
        Map<String, String> map = new LinkedHashMap<>();
        map.put("C", Answers.CERTO);
        map.put("E", Answers.ERRADO);
        return map;
    }

    private Map<String, String> multipleChoice(StructuredQuestion q, PreviewItem item) {
        if (q.alternatives.size() < 2) {
            item.error("Questão de múltipla escolha precisa de pelo menos 2 alternativas.");
            return null;
        }
        if (q.alternatives.size() > LETTERS.length()) {
            item.error("São aceitas no máximo 5 alternativas (A a E); encontradas " + q.alternatives.size() + ".");
            return null;
        }
        Map<String, String> map = new LinkedHashMap<>();
        boolean explicit = q.alternatives.stream().anyMatch(a -> a[0] != null);
        for (int i = 0; i < q.alternatives.size(); i++) {
            String[] alt = q.alternatives.get(i);
            String letter = explicit ? (alt[0] == null ? null : Answers.clean(alt[0])) : String.valueOf(LETTERS.charAt(i));
            if (letter == null) {
                item.error("Alternativa " + (i + 1) + " está sem letra (as outras têm).");
                return null;
            }
            if (letter.length() != 1 || LETTERS.indexOf(letter) < 0) {
                item.error("Letra de alternativa \"" + alt[0] + "\" inválida: use A, B, C, D ou E.");
                return null;
            }
            if (map.containsKey(letter)) {
                item.error("A alternativa " + letter + " aparece duas vezes.");
                return null;
            }
            String text = JsonQuestionReader.blankToNull(alt[1]);
            if (text == null) {
                item.error("A alternativa " + letter + " está vazia.");
                return null;
            }
            map.put(letter, text);
        }
        return map;
    }

    private static boolean isCertoErradoPair(List<String[]> alternatives) {
        if (alternatives.size() != 2) {
            return false;
        }
        Set<String> texts = Set.of(Answers.clean(alternatives.get(0)[1]), Answers.clean(alternatives.get(1)[1]));
        return texts.equals(Set.of("CERTO", "ERRADO"));
    }

    static QuestionCategory category(String raw) {
        String c = Answers.clean(raw);
        return switch (c) {
            case "GERAL", "GERAIS", "BASICO", "BASICOS", "CONHECIMENTOS GERAIS" -> QuestionCategory.GERAL;
            case "ESPECIFICO", "ESPECIFICOS", "CONHECIMENTOS ESPECIFICOS" -> QuestionCategory.ESPECIFICO;
            default -> null;
        };
    }

    /** Enunciado comparavel: sem marcacao, sem acento, minusculo e com espacos simples. */
    public static String normalizeStatement(String statement) {
        if (statement == null) {
            return "";
        }
        String plain = RichText.plain(statement);
        return Normalizer.normalize(plain, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
