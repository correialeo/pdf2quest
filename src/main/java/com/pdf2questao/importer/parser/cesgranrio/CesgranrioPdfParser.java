package com.pdf2questao.importer.parser.cesgranrio;

import com.pdf2questao.importer.Banca;
import com.pdf2questao.importer.ParseFailure;
import com.pdf2questao.importer.ParseResult;
import com.pdf2questao.importer.ParsedQuestion;
import com.pdf2questao.importer.parser.ExamParser;
import com.pdf2questao.importer.parser.support.PdfMetadataDetector;
import com.pdf2questao.importer.parser.support.PdfNoiseFilter;
import com.pdf2questao.importer.parser.support.PdfTextUtils;
import com.pdf2questao.question.QuestionCategory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser de provas CESGRANRIO. O caderno costuma identificar as questoes com o
 * numero isolado acima do enunciado e alternativas no formato "(A)".
 */
@Component
public class CesgranrioPdfParser implements ExamParser {

    private static final Pattern QUESTION_START =
            Pattern.compile("^\\s*(\\d{1,3})(?:\\s*[.\\-\\u2013\\u2014\\)]\\s*(.*))?\\s*$");

    private static final Pattern ALTERNATIVE_LINE =
            Pattern.compile("(?m)^\\s*\\(([A-E])\\)\\s+(.*)$");

    private static final Pattern EDITAL_YEAR = Pattern.compile("(?i)EDITAL\\s+N[oº]?\\s+\\d{1,3}/((?:19|20)\\d{2})");
    private static final Pattern PROVAS_APLICADAS_YEAR =
            Pattern.compile("(?i)PROVAS\\s+APLICADAS\\s+EM\\s+\\d{1,2}\\s+DE\\s+\\p{L}+\\s+DE\\s+((?:19|20)\\d{2})");

    @Override
    public Banca banca() {
        return Banca.CESGRANRIO;
    }

    @Override
    public ParseResult parse(String rawText) {
        String text = rawText.replace("\r\n", "\n").replace("\r", "\n").replace("\f", "\n");
        String organization = PdfMetadataDetector.detectOrganization(text);
        Integer year = detectYear(text);
        Set<String> repeatedBrandingLines = PdfNoiseFilter.detectRepeatedBrandingLines(text);

        List<ParsedQuestion> questions = new ArrayList<>();
        List<ParseFailure> failures = new ArrayList<>();

        String currentSubject = null;
        QuestionCategory currentCategory = null;
        boolean canReadQuestions = false;

        List<String> blockLines = null;
        int blockNumber = -1;
        String blockSubject = null;
        QuestionCategory blockCategory = null;
        int expectedNumber = -1;

        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.trim();

            if (PdfNoiseFilter.isPageNoise(line, repeatedBrandingLines) || isCesgranrioPageNoise(line)) {
                continue;
            }

            Section section = detectSection(line);
            if (section != null) {
                if (blockLines != null) {
                    flush(blockNumber, blockLines, blockSubject, blockCategory, questions, failures);
                    blockLines = null;
                }
                currentCategory = section.category();
                currentSubject = section.subject();
                canReadQuestions = true;
                continue;
            }

            if (!canReadQuestions) {
                continue;
            }

            Matcher qm = QUESTION_START.matcher(line);
            if (qm.matches()) {
                int candidateNumber = Integer.parseInt(qm.group(1));
                String remainder = qm.group(2) == null ? "" : qm.group(2).trim();
                if (candidateNumber >= 1 && (expectedNumber == -1 || candidateNumber == expectedNumber)
                        && (blockLines == null || hasAnyAlternative(blockLines))) {
                    if (blockLines != null) {
                        flush(blockNumber, blockLines, blockSubject, blockCategory, questions, failures);
                    }
                    blockNumber = candidateNumber;
                    expectedNumber = candidateNumber + 1;
                    blockSubject = currentSubject;
                    blockCategory = currentCategory;
                    blockLines = new ArrayList<>();
                    if (!remainder.isBlank()) {
                        blockLines.add(remainder);
                    }
                    continue;
                }
            }

            if (blockLines != null) {
                blockLines.add(line);
            }
        }

        if (blockLines != null) {
            flush(blockNumber, blockLines, blockSubject, blockCategory, questions, failures);
        }

        return new ParseResult(organization, year, questions, failures);
    }

    private void flush(int number, List<String> lines, String subject, QuestionCategory category,
                       List<ParsedQuestion> questions, List<ParseFailure> failures) {
        String blockText = String.join("\n", lines).trim();
        if (blockText.isBlank()) {
            failures.add(new ParseFailure(number, "bloco vazio"));
            return;
        }

        record AltMatch(String letter, int contentStart, int matchStart) {
        }

        Matcher am = ALTERNATIVE_LINE.matcher(blockText);
        List<AltMatch> matches = new ArrayList<>();
        while (am.find()) {
            matches.add(new AltMatch(am.group(1), am.start(2), am.start()));
        }

        Map<String, String> alternatives = new LinkedHashMap<>();
        for (int i = 0; i < matches.size(); i++) {
            AltMatch m = matches.get(i);
            int end = (i + 1 < matches.size()) ? matches.get(i + 1).matchStart() : blockText.length();
            String text = blockText.substring(m.contentStart(), end).trim().replaceAll("\\s+", " ");
            alternatives.putIfAbsent(m.letter(), text);
        }

        int firstAltStart = matches.isEmpty() ? -1 : matches.get(0).matchStart();
        String statement = firstAltStart > 0 ? blockText.substring(0, firstAltStart).trim() : blockText;

        if (statement.isBlank() || alternatives.size() < 2) {
            failures.add(new ParseFailure(number,
                    "enunciado ou alternativas insuficientes (" + alternatives.size() + " alternativas encontradas)"));
            return;
        }

        questions.add(new ParsedQuestion(number, statement,
                alternatives.get("A"), alternatives.get("B"), alternatives.get("C"),
                alternatives.get("D"), alternatives.get("E"),
                null, subject, category));
    }

    private boolean hasAnyAlternative(List<String> lines) {
        return ALTERNATIVE_LINE.matcher(String.join("\n", lines)).find();
    }

    private Integer detectYear(String text) {
        Matcher editalMatcher = EDITAL_YEAR.matcher(text);
        if (editalMatcher.find()) {
            return Integer.parseInt(editalMatcher.group(1));
        }
        Matcher provasMatcher = PROVAS_APLICADAS_YEAR.matcher(text);
        if (provasMatcher.find()) {
            return Integer.parseInt(provasMatcher.group(1));
        }
        return PdfMetadataDetector.detectYear(text);
    }

    private Section detectSection(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        if (!line.equals(upper)) {
            return null;
        }
        if (upper.equals("CONHECIMENTOS BÁSICOS") || upper.equals("CONHECIMENTOS BASICOS")) {
            return new Section(null, QuestionCategory.GERAL);
        }
        if (upper.equals("CONHECIMENTOS TRANSVERSAIS")) {
            return new Section("Conhecimentos Transversais", QuestionCategory.GERAL);
        }
        if (upper.equals("LÍNGUA PORTUGUESA") || upper.equals("LINGUA PORTUGUESA")) {
            return new Section("Lingua Portuguesa", QuestionCategory.GERAL);
        }
        if (upper.equals("LÍNGUA INGLESA") || upper.equals("LINGUA INGLESA")) {
            return new Section("Lingua Inglesa", QuestionCategory.GERAL);
        }
        if (upper.equals("CONHECIMENTOS ESPECÍFICOS") || upper.equals("CONHECIMENTOS ESPECIFICOS")) {
            return new Section("Conhecimentos Especificos", QuestionCategory.ESPECIFICO);
        }
        return null;
    }

    private boolean isCesgranrioPageNoise(String line) {
        if (line.isBlank()) {
            return false;
        }
        String upper = line.toUpperCase(Locale.ROOT);
        return upper.equals("BNDES")
                || upper.equals("MANHÃ")
                || upper.equals("MANHA")
                || upper.equals("RASCUNHO")
                || upper.startsWith("PROVA ")
                || upper.startsWith("- DESENVOLVIMENTO")
                || upper.equals("A C D E")
                || PdfTextUtils.toTitleCase(line).equals("Provas Objetivas");
    }

    private record Section(String subject, QuestionCategory category) {
    }
}
