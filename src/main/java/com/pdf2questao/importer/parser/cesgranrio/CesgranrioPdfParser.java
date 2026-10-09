package com.pdf2questao.importer.parser.cesgranrio;

import com.pdf2questao.importer.Banca;
import com.pdf2questao.importer.ParseFailure;
import com.pdf2questao.importer.ParseResult;
import com.pdf2questao.importer.ParsedQuestion;
import com.pdf2questao.importer.parser.ExamParser;
import com.pdf2questao.importer.parser.support.PdfMetadataDetector;
import com.pdf2questao.importer.parser.support.PdfNoiseFilter;
import com.pdf2questao.importer.parser.support.PdfTextUtils;
import com.pdf2questao.importer.parser.support.QuestionBlocks;
import com.pdf2questao.importer.parser.support.RichText;
import com.pdf2questao.question.QuestionCategory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
        String plainText = RichText.plain(text);
        String organization = PdfMetadataDetector.detectOrganization(plainText);
        Integer year = detectYear(plainText);
        Set<String> repeatedBrandingLines = PdfNoiseFilter.detectRepeatedBrandingLines(plainText);

        List<ParsedQuestion> questions = new ArrayList<>();
        List<ParseFailure> failures = new ArrayList<>();

        String currentSubject = null;
        QuestionCategory currentCategory = null;
        Integer currentPage = null;
        boolean canReadQuestions = false;

        List<String> blockLines = null;
        int blockNumber = -1;
        String blockSubject = null;
        QuestionCategory blockCategory = null;
        String blockPassage = null;
        Integer blockPage = null;
        int expectedNumber = -1;

        // Linhas fora de qualquer questao formam o texto de apoio das questoes seguintes.
        List<String> passageLines = new ArrayList<>();
        String activePassage = null;

        for (String rawLine : text.split("\n", -1)) {
            Integer page = RichText.pageOf(rawLine);
            if (page != null) {
                currentPage = page;
            }
            String rich = RichText.trim(rawLine);
            String line = RichText.plain(rich);

            if (PdfNoiseFilter.isPageNoise(line, repeatedBrandingLines) || isCesgranrioPageNoise(line)) {
                continue;
            }

            Section section = detectSection(line);
            if (section != null) {
                if (blockLines != null) {
                    flush(blockNumber, blockLines, blockSubject, blockCategory, blockPassage, blockPage,
                            questions, failures);
                    blockLines = null;
                }
                currentCategory = section.category();
                currentSubject = section.subject();
                canReadQuestions = true;
                passageLines.clear();
                activePassage = null;
                continue;
            }

            if (!canReadQuestions) {
                continue;
            }

            if (blockLines != null && hasAnyAlternative(blockLines)
                    && QuestionBlocks.PASSAGE_TITLE.matcher(line).matches()) {
                flush(blockNumber, blockLines, blockSubject, blockCategory, blockPassage, blockPage,
                        questions, failures);
                blockLines = null;
            }

            Matcher qm = QUESTION_START.matcher(line);
            if (qm.matches()) {
                int candidateNumber = Integer.parseInt(qm.group(1));
                String remainder = qm.group(2) == null ? "" : RichText.fromVisible(rich, qm.start(2));
                if (candidateNumber >= 1 && (expectedNumber == -1 || candidateNumber == expectedNumber)
                        && (blockLines == null || hasAnyAlternative(blockLines))) {
                    if (blockLines != null) {
                        flush(blockNumber, blockLines, blockSubject, blockCategory, blockPassage, blockPage,
                                questions, failures);
                    }
                    if (QuestionBlocks.hasVisibleText(passageLines)) {
                        activePassage = QuestionBlocks.join(passageLines);
                    }
                    passageLines.clear();
                    blockNumber = candidateNumber;
                    expectedNumber = candidateNumber + 1;
                    blockSubject = currentSubject;
                    blockCategory = currentCategory;
                    blockPassage = activePassage;
                    blockPage = currentPage;
                    blockLines = new ArrayList<>();
                    if (!RichText.plain(remainder).isBlank()) {
                        blockLines.add(remainder);
                    }
                    continue;
                }
            }

            if (blockLines != null) {
                blockLines.add(rich);
            } else {
                passageLines.add(rich);
            }
        }

        if (blockLines != null) {
            flush(blockNumber, blockLines, blockSubject, blockCategory, blockPassage, blockPage, questions, failures);
        }

        return new ParseResult(organization, year, questions, failures);
    }

    private void flush(int number, List<String> lines, String subject, QuestionCategory category, String passage,
                       Integer page, List<ParsedQuestion> questions, List<ParseFailure> failures) {
        if (!QuestionBlocks.hasVisibleText(lines)) {
            failures.add(new ParseFailure(number, "bloco vazio"));
            return;
        }

        QuestionBlocks.Split split = QuestionBlocks.split(lines, ALTERNATIVE_LINE);
        Map<String, String> alternatives = split.alternatives();
        String statement = split.statement();

        if (RichText.plain(statement).isBlank() || alternatives.size() < 2) {
            failures.add(new ParseFailure(number,
                    "enunciado ou alternativas insuficientes (" + alternatives.size() + " alternativas encontradas)"));
            return;
        }

        questions.add(new ParsedQuestion(number, statement,
                alternatives.get("A"), alternatives.get("B"), alternatives.get("C"),
                alternatives.get("D"), alternatives.get("E"),
                null, subject, category, passage, page));
    }

    private boolean hasAnyAlternative(List<String> lines) {
        return ALTERNATIVE_LINE.matcher(RichText.plain(String.join("\n", lines))).find();
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
