package com.pdf2questao.study;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Le um .docx (Word, Google Docs, LibreOffice) sem bibliotecas extras: o arquivo e um ZIP com XML. Converte para o
 * HTML do editor de paginas (titulos, paragrafos, negrito/italico/sublinhado, listas, tabelas, links) e guarda as
 * tabelas como texto, para o {@link ScheduleExtractor} achar cronogramas. Imagens e estilos visuais ficam de fora.
 */
public final class DocxReader {

    /** Limite por parte do ZIP: um .docx normal tem bem menos; evita arquivo "bomba" que descomprime gigabytes. */
    private static final long MAX_PART_BYTES = 30L * 1024 * 1024;
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    /** Resultado: titulo (primeiro titulo do documento), HTML e as tabelas (linhas x celulas em texto). */
    public record Result(String title, String html, List<List<List<String>>> tables, String text) {
    }

    public static class InvalidDocxException extends Exception {
        InvalidDocxException(String message) {
            super(message);
        }
    }

    private DocxReader() {
    }

    public static Result read(byte[] bytes) throws InvalidDocxException {
        Map<String, byte[]> parts = unzip(bytes);
        byte[] document = parts.get("word/document.xml");
        if (document == null) {
            throw new InvalidDocxException("O arquivo não é um documento do Word (.docx).");
        }
        Document doc = parse(document);
        Map<String, Boolean> orderedLists = parts.containsKey("word/numbering.xml")
                ? orderedLists(parse(parts.get("word/numbering.xml"))) : Map.of();
        Map<String, String> links = parts.containsKey("word/_rels/document.xml.rels")
                ? links(parse(parts.get("word/_rels/document.xml.rels"))) : Map.of();
        Converter converter = new Converter(orderedLists, links);
        Element body = child(doc.getDocumentElement(), "body");
        if (body == null) {
            throw new InvalidDocxException("O documento está vazio.");
        }
        converter.blocks(body);
        converter.closeList();
        return new Result(converter.title, HtmlSanitizer.sanitize(converter.html.toString()), converter.tables,
                converter.text.toString());
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws InvalidDocxException {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals("word/document.xml") || name.equals("word/numbering.xml")
                        || name.equals("word/_rels/document.xml.rels")) {
                    parts.put(name, readLimited(zip));
                }
            }
        } catch (IOException e) {
            throw new InvalidDocxException("Não foi possível abrir o arquivo. Confira se é um .docx (o .doc antigo não é aceito).");
        }
        return parts;
    }

    private static byte[] readLimited(InputStream in) throws IOException, InvalidDocxException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > MAX_PART_BYTES) {
                throw new InvalidDocxException("O documento é grande demais para importar.");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static Document parse(byte[] xml) throws InvalidDocxException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // XML de arquivo enviado: sem DTD nem entidades externas (XXE).
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new InvalidDocxException("O documento está corrompido e não pôde ser lido.");
        }
    }

    /** numId -> lista numerada? (formato do nivel 0 diferente de "bullet"). */
    private static Map<String, Boolean> orderedLists(Document numbering) {
        Map<String, Boolean> abstractOrdered = new HashMap<>();
        for (Element a : children(numbering.getDocumentElement(), "abstractNum")) {
            for (Element lvl : children(a, "lvl")) {
                if ("0".equals(lvl.getAttributeNS(W, "ilvl"))) {
                    Element fmt = child(lvl, "numFmt");
                    abstractOrdered.put(a.getAttributeNS(W, "abstractNumId"),
                            fmt != null && !"bullet".equals(fmt.getAttributeNS(W, "val")));
                }
            }
        }
        Map<String, Boolean> result = new HashMap<>();
        for (Element num : children(numbering.getDocumentElement(), "num")) {
            Element ref = child(num, "abstractNumId");
            if (ref != null) {
                result.put(num.getAttributeNS(W, "numId"), abstractOrdered.getOrDefault(ref.getAttributeNS(W, "val"), false));
            }
        }
        return result;
    }

    private static Map<String, String> links(Document rels) {
        Map<String, String> result = new HashMap<>();
        Node n = rels.getDocumentElement().getFirstChild();
        for (; n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getAttribute("Type").endsWith("/hyperlink")) {
                result.put(e.getAttribute("Id"), e.getAttribute("Target"));
            }
        }
        return result;
    }

    private static final class Converter {
        final Map<String, Boolean> orderedLists;
        final Map<String, String> links;
        final StringBuilder html = new StringBuilder();
        final StringBuilder text = new StringBuilder();
        final List<List<List<String>>> tables = new ArrayList<>();
        String title;
        /** Lista aberta no momento ("ul"/"ol"), para juntar paragrafos seguidos da mesma lista. */
        String openList;

        Converter(Map<String, Boolean> orderedLists, Map<String, String> links) {
            this.orderedLists = orderedLists;
            this.links = links;
        }

        void blocks(Element container) {
            for (Node n = container.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element e) || !W.equals(e.getNamespaceURI())) {
                    continue;
                }
                switch (e.getLocalName()) {
                    case "p" -> paragraph(e);
                    case "tbl" -> {
                        closeList();
                        table(e);
                    }
                    case "sdt" -> {
                        Element content = child(e, "sdtContent");
                        if (content != null) {
                            blocks(content);
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        void paragraph(Element p) {
            Element props = child(p, "pPr");
            String style = "";
            String numId = null;
            if (props != null) {
                Element ps = child(props, "pStyle");
                style = ps == null ? "" : ps.getAttributeNS(W, "val").toLowerCase();
                Element numPr = child(props, "numPr");
                Element id = numPr == null ? null : child(numPr, "numId");
                numId = id == null || "0".equals(id.getAttributeNS(W, "val")) ? null : id.getAttributeNS(W, "val");
            }
            String inner = runs(p);
            String plain = HtmlSanitizer.sanitize(inner).replaceAll("<[^>]+>", "");
            text.append(plain).append('\n');
            if (numId != null) {
                String tag = orderedLists.getOrDefault(numId, false) ? "ol" : "ul";
                if (!tag.equals(openList)) {
                    closeList();
                    html.append('<').append(tag).append('>');
                    openList = tag;
                }
                html.append("<li>").append(inner).append("</li>");
                return;
            }
            closeList();
            if (plain.isBlank()) {
                return;
            }
            String tag = heading(style);
            if (tag != null && title == null) {
                // O primeiro titulo vira o titulo da pagina, nao se repete no corpo.
                title = plain.strip();
                return;
            }
            tag = tag == null ? "p" : tag;
            html.append('<').append(tag).append('>').append(inner).append("</").append(tag).append('>');
        }

        static String heading(String style) {
            if (style.equals("title")) {
                return "h1";
            }
            if (style.startsWith("heading") || style.startsWith("titulo") || style.startsWith("ttulo")) {
                String level = style.replaceAll("\\D", "");
                return level.equals("1") ? "h1" : level.equals("2") ? "h2" : "h3";
            }
            return null;
        }

        void closeList() {
            if (openList != null) {
                html.append("</").append(openList).append('>');
                openList = null;
            }
        }

        /** Texto do paragrafo em HTML, com negrito/italico/sublinhado/riscado e links. */
        String runs(Element parent) {
            StringBuilder out = new StringBuilder();
            for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element e) || !W.equals(e.getNamespaceURI())) {
                    continue;
                }
                switch (e.getLocalName()) {
                    case "r" -> out.append(run(e));
                    case "hyperlink" -> {
                        String target = links.get(e.getAttributeNS(R, "id"));
                        String inner = runs(e);
                        out.append(target == null ? inner
                                : "<a href=\"" + escape(target).replace("\"", "&quot;") + "\">" + inner + "</a>");
                    }
                    case "ins", "smartTag", "fldSimple", "sdt", "sdtContent" -> out.append(runs(e));
                    default -> {
                    }
                }
            }
            return out.toString();
        }

        String run(Element r) {
            StringBuilder text = new StringBuilder();
            for (Node n = r.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element e)) {
                    continue;
                }
                switch (e.getLocalName()) {
                    case "t" -> text.append(escape(e.getTextContent()));
                    case "tab" -> text.append(' ');
                    case "br", "cr" -> text.append("<br>");
                    default -> {
                    }
                }
            }
            if (text.isEmpty()) {
                return "";
            }
            Element props = child(r, "rPr");
            String s = text.toString();
            if (props != null) {
                if (on(props, "strike")) {
                    s = "<s>" + s + "</s>";
                }
                if (child(props, "u") != null && !"none".equals(child(props, "u").getAttributeNS(W, "val"))) {
                    s = "<u>" + s + "</u>";
                }
                if (on(props, "i")) {
                    s = "<em>" + s + "</em>";
                }
                if (on(props, "b")) {
                    s = "<strong>" + s + "</strong>";
                }
            }
            return s;
        }

        static boolean on(Element props, String name) {
            Element e = child(props, name);
            if (e == null) {
                return false;
            }
            String val = e.getAttributeNS(W, "val");
            return val.isEmpty() || !(val.equals("0") || val.equals("false") || val.equals("none"));
        }

        void table(Element tbl) {
            List<List<String>> rows = new ArrayList<>();
            html.append("<table><tbody>");
            boolean first = true;
            for (Element tr : children(tbl, "tr")) {
                List<String> cells = new ArrayList<>();
                html.append("<tr>");
                for (Element tc : children(tr, "tc")) {
                    StringBuilder cellHtml = new StringBuilder();
                    StringBuilder cellText = new StringBuilder();
                    for (Element p : children(tc, "p")) {
                        String inner = runs(p);
                        if (!cellHtml.isEmpty() && !inner.isEmpty()) {
                            cellHtml.append("<br>");
                            cellText.append(' ');
                        }
                        cellHtml.append(inner);
                        cellText.append(HtmlSanitizer.sanitize(inner).replaceAll("<[^>]+>", ""));
                    }
                    String tag = first ? "th" : "td";
                    html.append('<').append(tag).append('>').append(cellHtml).append("</").append(tag).append('>');
                    String plain = unescape(cellText.toString()).strip();
                    cells.add(plain);
                    // Celula mesclada na horizontal ocupa varias colunas: repete vazio para manter o alinhamento.
                    Element props = child(tc, "tcPr");
                    Element span = props == null ? null : child(props, "gridSpan");
                    int extra = span == null ? 0 : Math.max(0, parseInt(span.getAttributeNS(W, "val")) - 1);
                    for (int i = 0; i < extra; i++) {
                        cells.add("");
                    }
                    text.append(plain).append(" | ");
                }
                text.append('\n');
                html.append("</tr>");
                rows.add(cells);
                first = false;
            }
            html.append("</tbody></table>");
            tables.add(rows);
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }

    private static Element child(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> list = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) {
                list.add(e);
            }
        }
        return list;
    }
}
