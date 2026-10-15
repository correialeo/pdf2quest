package com.pdf2questao.importer.structured;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Como ler a planilha: separador, se a primeira linha e cabecalho, qual coluna e cada campo e como as
 * alternativas estao escritas. Montado pela tela de mapeamento; {@link #guess} sugere a partir do cabecalho.
 */
public class CsvMapping {

    /** Campos do sistema que podem vir de uma coluna, na ordem da tela de mapeamento. */
    public enum Field {
        ID("ID da questão", "id", "codigo", "identificador", "id externo", "cod"),
        NUMERO("Número na prova", "numero", "num", "n", "questao", "nº", "no"),
        TIPO("Tipo (múltipla escolha / certo-errado)", "tipo", "tipo de questao"),
        ENUNCIADO("Enunciado", "enunciado", "pergunta", "texto", "questao texto", "comando"),
        TEXTO_BASE("Texto base", "texto base", "texto de apoio", "textobase"),
        ALTERNATIVAS("Alternativas (todas numa coluna)", "alternativas", "opcoes"),
        A("Alternativa A", "a", "alternativa a", "alt a", "opcao a"),
        B("Alternativa B", "b", "alternativa b", "alt b", "opcao b"),
        C("Alternativa C", "c", "alternativa c", "alt c", "opcao c"),
        D("Alternativa D", "d", "alternativa d", "alt d", "opcao d"),
        E("Alternativa E", "e", "alternativa e", "alt e", "opcao e"),
        RESPOSTA("Resposta / gabarito", "resposta", "gabarito", "correta", "resposta correta", "alternativa correta"),
        EXPLICACAO("Explicação / comentário", "explicacao", "comentario", "justificativa", "resolucao"),
        BANCA("Banca", "banca", "organizadora"),
        CONCURSO("Concurso / prova", "concurso", "prova", "orgao", "cargo"),
        ANO("Ano", "ano"),
        DISCIPLINA("Disciplina", "disciplina", "materia"),
        ASSUNTO("Assunto", "assunto", "topico", "tema"),
        SUBTOPICOS("Subtópicos (separados por |)", "subtopicos", "subtopico", "subassunto"),
        TAGS("Tags (separadas por |)", "tags", "etiquetas", "marcadores"),
        DIFICULDADE("Dificuldade", "dificuldade", "nivel");

        private final String label;
        private final List<String> aliases;

        Field(String label, String... aliases) {
            this.label = label;
            this.aliases = List.of(aliases);
        }

        public String getLabel() {
            return label;
        }
    }

    public enum AlternativesMode {
        /** Uma coluna para cada alternativa (A, B, C, D, E). */
        COLUNAS,
        /** Todas as alternativas numa coluna so, separadas por um caractere. */
        UMA_COLUNA
    }

    private char delimiter = ';';
    private boolean header = true;
    private AlternativesMode alternativesMode = AlternativesMode.COLUNAS;
    private String alternativesSeparator = "|";
    /** Campo -> indice da coluna (0 = primeira). Campo ausente = nao vem da planilha. */
    private final Map<Field, Integer> columns = new LinkedHashMap<>();

    public char getDelimiter() { return delimiter; }
    public void setDelimiter(char delimiter) { this.delimiter = delimiter; }
    public boolean isHeader() { return header; }
    public void setHeader(boolean header) { this.header = header; }
    public AlternativesMode getAlternativesMode() { return alternativesMode; }
    public void setAlternativesMode(AlternativesMode mode) { this.alternativesMode = mode; }
    public String getAlternativesSeparator() { return alternativesSeparator; }
    public void setAlternativesSeparator(String separator) { this.alternativesSeparator = separator; }
    public Map<Field, Integer> getColumns() { return columns; }

    public Integer column(Field field) {
        return columns.get(field);
    }

    public void map(Field field, Integer column) {
        if (column == null || column < 0) {
            columns.remove(field);
        } else {
            columns.put(field, column);
        }
    }

    /** Sugestao inicial: cada cabecalho reconhecido vira o campo correspondente. */
    public static CsvMapping guess(List<String> headerRow, char delimiter) {
        CsvMapping mapping = new CsvMapping();
        mapping.setDelimiter(delimiter);
        for (int i = 0; i < headerRow.size(); i++) {
            String h = normalize(headerRow.get(i));
            for (Field f : Field.values()) {
                if (!mapping.columns.containsKey(f) && f.aliases.contains(h)) {
                    mapping.columns.put(f, i);
                    break;
                }
            }
        }
        // Cabecalho sem nenhum nome conhecido: provavelmente a primeira linha ja e uma questao.
        mapping.setHeader(!mapping.columns.isEmpty());
        if (mapping.columns.containsKey(Field.ALTERNATIVAS) && !mapping.columns.containsKey(Field.A)) {
            mapping.setAlternativesMode(AlternativesMode.UMA_COLUNA);
        }
        return mapping;
    }

    static String normalize(String header) {
        return Normalizer.normalize(header == null ? "" : header, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[_\\-.:)]+", " ").replaceAll("\\s+", " ").trim();
    }
}
