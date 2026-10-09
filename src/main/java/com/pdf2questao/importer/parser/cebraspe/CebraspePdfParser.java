package com.pdf2questao.importer.parser.cebraspe;

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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser de provas CEBRASPE (itens Certo/Errado). Cada item e "51 texto..." com
 * as linhas seguintes recuadas; o que volta para a margem e o comando ("Julgue
 * os itens...") e o texto de apoio dos proximos itens. Os itens viram questoes
 * com as alternativas C (Certo) e E (Errado), que batem com as letras do gabarito.
 */
@Component
public class CebraspePdfParser implements ExamParser {

    public static final String CERTO = "Certo";
    public static final String ERRADO = "Errado";

    private static final Pattern SECTION = Pattern.compile("^--\\s*(.+?)\\s*--$");
    private static final Pattern ITEM_START = Pattern.compile("^(\\d{1,3})\\s+(\\S.*)$");
    private static final Pattern COMMAND = Pattern.compile("(?i)\\b(julgue|judge)\\b");
    private static final Pattern REFERS_TO_TEXT = Pattern.compile("(?i)\\btext[oe]?s?\\b");
    private static final Pattern SENTENCE_END = Pattern.compile("[.:!?>)\\u201D\"\\u2019]$");
    private static final Pattern FREE_SPACE = Pattern.compile("\\s*Espa\\u00E7o livre\\s*$");
    private static final Pattern BOOKLET_CODE = Pattern.compile("^\\d{3}[A-Z0-9]{8,}$|^\\d{10,}$");

    @Override
    public Banca banca() {
        return Banca.CEBRASPE;
    }

    @Override
    public ParseResult parse(String rawText) {
        String text = rawText.replace("\r\n", "\n").replace("\r", "\n").replace("\f", "\n");
        String plainText = RichText.plain(text);
        String organization = PdfMetadataDetector.detectOrganization(plainText);
        Integer year = PdfMetadataDetector.detectYear(plainText);
        Set<String> repeatedBrandingLines = PdfNoiseFilter.detectRepeatedBrandingLines(plainText);

        List<ParsedQuestion> questions = new ArrayList<>();
        List<ParseFailure> failures = new ArrayList<>();

        QuestionCategory category = null;
        String subject = null;
        List<String> pendingSubject = new ArrayList<>();
        Integer currentPage = null;
        int expectedNumber = -1;

        Item item = null;
        List<String> context = new ArrayList<>();
        String command = null;
        String passage = null;

        for (String rawLine : text.split("\n", -1)) {
            Integer page = RichText.pageOf(rawLine);
            if (page != null) {
                currentPage = page;
            }
            String rich = RichText.trim(FREE_SPACE.matcher(rawLine).replaceAll(""));
            String line = RichText.plain(rich);

            if (isNoise(line, repeatedBrandingLines)) {
                continue;
            }

            Matcher section = SECTION.matcher(line);
            if (section.matches()) {
                item = flush(item, questions, failures);
                String name = section.group(1).toUpperCase(Locale.ROOT);
                if (name.contains("ESPEC")) {
                    category = QuestionCategory.ESPECIFICO;
                } else if (name.contains("GERA")) {
                    category = QuestionCategory.GERAL;
                } else if (!name.contains("OBJETIVA")) {
                    category = null;
                }
                subject = null;
                pendingSubject.clear();
                context.clear();
                command = null;
                passage = null;
                expectedNumber = -1;
                continue;
            }
            if (category == null) {
                continue;
            }

            boolean indented = RichText.isIndented(rich);
            // Titulos de disciplina sao centralizados, entao chegam recuados.
            if (isSubjectHeading(line)) {
                item = flush(item, questions, failures);
                pendingSubject.add(line);
                context.clear();
                passage = null;
                continue;
            }
            if (!pendingSubject.isEmpty()) {
                subject = PdfTextUtils.toTitleCase(String.join(" ", pendingSubject));
                pendingSubject.clear();
            }

            Matcher start = ITEM_START.matcher(line);
            if (!indented && start.matches()) {
                int number = Integer.parseInt(start.group(1));
                if (expectedNumber == -1 || number == expectedNumber) {
                    item = flush(item, questions, failures);
                    if (QuestionBlocks.hasVisibleText(context)) {
                        int commandStart = commandStart(context);
                        String newCommand = prose(context.subList(commandStart, context.size()));
                        List<String> passageLines = context.subList(0, commandStart);
                        if (QuestionBlocks.hasVisibleText(passageLines)) {
                            passage = QuestionBlocks.join(passageLines);
                        } else if (!REFERS_TO_TEXT.matcher(RichText.plain(newCommand)).find()) {
                            passage = null;
                        }
                        command = newCommand;
                    }
                    context.clear();
                    expectedNumber = number + 1;
                    item = new Item(number, category, subject, command, passage, currentPage);
                    item.lines.add(RichText.fromVisible(rich, start.start(2)));
                    continue;
                }
            }

            if (item != null && (indented || line.isBlank())) {
                if (!line.isBlank() || rich.contains("⟦img:")) {
                    item.lines.add(rich);
                }
                continue;
            }
            item = flush(item, questions, failures);
            if (!line.isBlank() || rich.contains("⟦img:")) {
                context.add(rich);
            }
        }
        flush(item, questions, failures);

        return new ParseResult(organization, year, questions, failures);
    }

    private Item flush(Item item, List<ParsedQuestion> questions, List<ParseFailure> failures) {
        if (item == null) {
            return null;
        }
        String body = prose(item.lines);
        if (RichText.plain(body).isBlank()) {
            failures.add(new ParseFailure(item.number, "item vazio"));
            return null;
        }
        String statement = item.command == null ? body : item.command + "\n\n" + body;
        questions.add(new ParsedQuestion(item.number, statement, null, null, CERTO, null, ERRADO,
                null, item.subject, item.category, item.passage, item.page));
        return null;
    }

    /** Comando e item quebram linha so pela largura da coluna. */
    private String prose(List<String> lines) {
        return QuestionBlocks.join(lines).replaceAll("[ \t]*\n[ \t]*(?!\u27E6img:)", " ")
                .replaceAll("[ \t]*(\u27E6img:)", "\n$1");
    }

    /**
     * Comando = do inicio da frase que contem "julgue" ate o fim; o que vem antes e
     * texto de apoio. Sem "julgue", tudo e tratado como comando.
     */
    private int commandStart(List<String> context) {
        int julgue = -1;
        for (int i = context.size() - 1; i >= 0; i--) {
            if (COMMAND.matcher(RichText.plain(context.get(i))).find()) {
                julgue = i;
                break;
            }
        }
        if (julgue < 0) {
            return 0;
        }
        int start = julgue;
        while (start > 0 && !SENTENCE_END.matcher(RichText.plain(context.get(start - 1)).trim()).find()) {
            start--;
        }
        return start;
    }

    private boolean isSubjectHeading(String line) {
        if (line.length() < 4 || line.length() > 60 || !line.equals(line.toUpperCase(Locale.ROOT))) {
            return false;
        }
        if (SENTENCE_END.matcher(line).find() || line.chars().anyMatch(Character::isDigit)) {
            return false;
        }
        return line.chars().filter(Character::isLetter).count() >= 4;
    }

    private boolean isNoise(String line, Set<String> repeatedBrandingLines) {
        return PdfNoiseFilter.isPageNoise(line, repeatedBrandingLines)
                || BOOKLET_CODE.matcher(line).matches()
                || line.startsWith("CEBRASPE \u2013")
                || line.contains(" CEBRASPE \u2013 ");
    }

    private static final class Item {
        final int number;
        final QuestionCategory category;
        final String subject;
        final String command;
        final String passage;
        final Integer page;
        final List<String> lines = new ArrayList<>();

        Item(int number, QuestionCategory category, String subject, String command, String passage,
             Integer page) {
            this.number = number;
            this.category = category;
            this.subject = subject;
            this.command = command;
            this.passage = passage;
            this.page = page;
        }
    }
}
