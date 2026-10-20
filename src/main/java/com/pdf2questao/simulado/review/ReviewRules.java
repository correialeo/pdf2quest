package com.pdf2questao.simulado.review;

import com.pdf2questao.scoring.ScoringCalculator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Regras das revisoes, sem banco: o que aprova, reprova ou deixa pendente uma questao gerada. Funcoes puras para
 * serem testadas sem rede nem dados.
 */
public final class ReviewRules {

    /** Os 8 criterios de fidelidade a banca, na ordem (nota 0 a 4 cada). */
    public static final List<String> CRITERIA = List.of(
            "formato de cobrança compatível com as referências",
            "profundidade de raciocínio/aplicação adequada, sem complexidade artificial",
            "enunciado com informações suficientes e relevantes",
            "distratores plausíveis (erros reais de candidato)",
            "uma única alternativa claramente correta",
            "sem pistas involuntárias nas alternativas",
            "dificuldade compatível com o concurso e as evidências",
            "formulação inédita (não é reprodução de referência)");
    static final int UNIQUE_ANSWER = 4;
    static final int NO_CLUES = 5;
    static final int ORIGINAL = 7;
    /** Sem referencia real valida, a fidelidade nao passa disto (nao ha evidencia para nota alta). */
    static final int MAX_WITHOUT_REFERENCE = 2;
    static final int MIN_JUSTIFICATION = 15;
    /** Similaridade (Jaccard de palavras) a partir da qual o enunciado e reproducao de outro. */
    public static final double DUPLICATE_THRESHOLD = 0.75;

    private ReviewRules() {
    }

    public record Criterion(int nota, String justificativa) {
    }

    public record Outcome(ReviewStatus status, Double score, List<String> issues) {
    }

    /**
     * Fidelidade: 8 notas com justificativa. Sem referencia real valida (questao da banca existente ou prova externa
     * identificada), nenhuma nota passa de 2 e a questao nao e aprovada. Falha grave (nota 0, resposta nao unica,
     * pistas, reproducao) reprova mesmo com media alta.
     */
    public static Outcome fidelity(List<Criterion> criteria, int validReferences, Double maxSimilarity) {
        List<String> issues = new ArrayList<>();
        if (criteria == null || criteria.size() != CRITERIA.size()) {
            return new Outcome(ReviewStatus.EM_REVISAO, null,
                    List.of("Avalie os " + CRITERIA.size() + " critérios, na ordem."));
        }
        List<Integer> notes = new ArrayList<>();
        List<Integer> raw = new ArrayList<>();
        for (int i = 0; i < criteria.size(); i++) {
            Criterion c = criteria.get(i);
            if (c == null || c.nota() < 0 || c.nota() > 4) {
                return new Outcome(ReviewStatus.EM_REVISAO, null, List.of("Critério " + (i + 1) + ": nota de 0 a 4."));
            }
            if (c.justificativa() == null || c.justificativa().strip().length() < MIN_JUSTIFICATION) {
                issues.add("Critério " + (i + 1) + " (" + CRITERIA.get(i) + "): falta justificativa verificável.");
            }
            int note = c.nota();
            raw.add(note);
            if (validReferences == 0 && note > MAX_WITHOUT_REFERENCE) {
                note = MAX_WITHOUT_REFERENCE;
            }
            notes.add(note);
        }
        if (validReferences == 0) {
            issues.add("Sem referência real válida da banca: fidelidade limitada a " + MAX_WITHOUT_REFERENCE
                    + " em cada critério.");
        }
        double score = notes.stream().mapToInt(Integer::intValue).average().orElse(0);
        // Falhas graves pelas notas dadas (o teto por falta de referencia so limita a aprovacao, nao reprova).
        boolean critical = false;
        for (int i = 0; i < raw.size(); i++) {
            if (raw.get(i) == 0) {
                issues.add("Falha grave no critério " + (i + 1) + " (" + CRITERIA.get(i) + ").");
                critical = true;
            }
        }
        if (raw.get(UNIQUE_ANSWER) < 3) {
            issues.add("Resposta não é claramente única (critério 5 < 3).");
            critical = true;
        }
        if (raw.get(NO_CLUES) < 2) {
            issues.add("Alternativas entregam a resposta (critério 6 < 2).");
            critical = true;
        }
        if (raw.get(ORIGINAL) < 3) {
            issues.add("Formulação próxima demais de uma referência (critério 8 < 3).");
            critical = true;
        }
        if (maxSimilarity != null && maxSimilarity >= DUPLICATE_THRESHOLD) {
            issues.add("Enunciado " + Math.round(maxSimilarity * 100) + "% igual a outra questão (duplicata).");
            critical = true;
        }
        ReviewStatus status;
        if (critical) {
            status = ReviewStatus.REPROVADA;
        } else if (validReferences > 0 && issues.isEmpty() && score >= 3
                && notes.stream().allMatch(n -> n >= 2)) {
            status = ReviewStatus.APROVADA_FIDELIDADE;
        } else {
            status = ReviewStatus.EM_REVISAO;
            if (score < 3 || notes.stream().anyMatch(n -> n < 2)) {
                issues.add("Fidelidade insuficiente (média " + Math.round(score * 10) / 10.0
                        + "; mínimo 3, nenhum critério abaixo de 2): reescreva a questão.");
            }
        }
        return new Outcome(status, Math.round(score * 100) / 100.0, issues);
    }

    /** Situacao da fonte consultada para a regra da questao. */
    public enum SourceState {
        /** Redacao vigente confirmada na fonte. */
        VIGENTE,
        /** A regra usada foi alterada por norma posterior. */
        ALTERADA,
        REVOGADA,
        /** Consultou, mas nao conseguiu confirmar a redacao vigente. */
        NAO_CONFIRMADA,
        /** Fonte fora do ar ou sem acesso: nada foi verificado. */
        INDISPONIVEL
    }

    public record Source(String url, boolean primariaOficial, String dispositivo, LocalDate consultadoEm,
                         SourceState situacao, String observacao) {
    }

    /**
     * Correcao material. Reprova: resposta independente diferente do gabarito, mais de uma alternativa defensavel,
     * regra alterada/revogada usada como vigente. Pendente de fonte: a regra depende de norma/versao e nao ha fonte
     * que confirme (inclusive fonte indisponivel, que nunca conta como verificada).
     */
    public static Outcome material(ContentType type, String correctAnswer, String independentAnswer,
                                   List<String> defensible, boolean involvesCalculation, String calculation,
                                   List<Source> sources, boolean historicalRule, String temporalReference) {
        List<String> issues = new ArrayList<>();
        if (type == null) {
            return new Outcome(ReviewStatus.EM_REVISAO, null, List.of("Classifique o tipo de conteúdo."));
        }
        if (independentAnswer == null || independentAnswer.isBlank()) {
            return new Outcome(ReviewStatus.EM_REVISAO, null,
                    List.of("Falta a resolução independente (resolver sem ver o gabarito)."));
        }
        boolean reject = false;
        boolean pending = false;
        String mine = independentAnswer.strip().toUpperCase(Locale.ROOT);
        if (!mine.equalsIgnoreCase(correctAnswer == null ? "" : correctAnswer.strip())) {
            issues.add("Resolução independente deu " + mine + ", gabarito diz " + correctAnswer + ".");
            reject = true;
        }
        Set<String> others = new HashSet<>();
        for (String d : defensible == null ? List.<String>of() : defensible) {
            if (d != null && !d.isBlank() && !d.strip().equalsIgnoreCase(correctAnswer)) {
                others.add(d.strip().toUpperCase(Locale.ROOT));
            }
        }
        if (!others.isEmpty()) {
            issues.add("Alternativa(s) também defensável(is): " + String.join(", ", others) + ".");
            reject = true;
        }
        if (involvesCalculation && (calculation == null || calculation.strip().length() < 10)) {
            issues.add("Questão com cálculo: refaça e registre o cálculo.");
            pending = true;
        }
        List<Source> list = sources == null ? List.of() : sources;
        for (Source s : list) {
            if (s.situacao() == SourceState.ALTERADA || s.situacao() == SourceState.REVOGADA) {
                if (historicalRule && temporalReference != null && !temporalReference.isBlank()) {
                    continue;   // regra historica com o marco temporal explicito no enunciado
                }
                issues.add("Regra " + s.situacao().name().toLowerCase(Locale.ROOT) + " usada como vigente ("
                        + (s.dispositivo() == null ? s.url() : s.dispositivo()) + ").");
                reject = true;
            }
        }
        if (historicalRule && (temporalReference == null || temporalReference.isBlank())) {
            issues.add("Regra histórica sem o marco temporal no enunciado.");
            pending = true;
        }
        if (type.needsSource()) {
            boolean confirmed = list.stream().anyMatch(s -> s.situacao() == SourceState.VIGENTE
                    && (!type.needsPrimarySource() || s.primariaOficial())
                    && s.dispositivo() != null && !s.dispositivo().isBlank() && s.consultadoEm() != null);
            if (!confirmed) {
                boolean unavailable = list.stream().anyMatch(s -> s.situacao() == SourceState.INDISPONIVEL);
                issues.add(unavailable ? "Fonte indisponível: a regra não foi verificada."
                        : "Falta fonte " + (type.needsPrimarySource() ? "primária oficial " : "")
                        + "com dispositivo, data da consulta e vigência confirmada.");
                pending = true;
            }
        }
        ReviewStatus status = reject ? ReviewStatus.REPROVADA : pending ? ReviewStatus.PENDENTE_FONTE
                : ReviewStatus.APROVADA_CORRECAO;
        return new Outcome(status, null, issues);
    }

    /** Estado geral: reprovacao e pendencia prevalecem; aprovada so com as duas revisoes do conteudo atual. */
    public static ReviewStatus overall(ReviewStatus fidelity, ReviewStatus material, boolean outsideEdital) {
        if (fidelity == ReviewStatus.REPROVADA || material == ReviewStatus.REPROVADA) {
            return ReviewStatus.REPROVADA;
        }
        if (material == ReviewStatus.PENDENTE_FONTE) {
            return ReviewStatus.PENDENTE_FONTE;
        }
        boolean f = fidelity == ReviewStatus.APROVADA_FIDELIDADE;
        boolean m = material == ReviewStatus.APROVADA_CORRECAO;
        if (outsideEdital && (f || m)) {
            return ReviewStatus.EM_REVISAO;
        }
        if (f && m) {
            return ReviewStatus.APROVADA;
        }
        if (f) {
            return ReviewStatus.APROVADA_FIDELIDADE;
        }
        if (m) {
            return ReviewStatus.APROVADA_CORRECAO;
        }
        return fidelity == null && material == null ? ReviewStatus.GERADA : ReviewStatus.EM_REVISAO;
    }

    /** Similaridade entre enunciados: Jaccard das palavras com 4+ letras, sem acento e caixa. */
    public static double similarity(String a, String b) {
        Set<String> x = words(a);
        Set<String> y = words(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        return inter.size() / (double) union.size();
    }

    private static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) {
            return out;
        }
        for (String w : ScoringCalculator.normalize(text.replaceAll("<[^>]+>", " ")).split("[^a-z0-9]+")) {
            if (w.length() >= 4) {
                out.add(w);
            }
        }
        return out;
    }
}
