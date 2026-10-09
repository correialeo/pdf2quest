package com.pdf2questao.importer;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sugere a disciplina de questoes de Conhecimentos Especificos quando a prova
 * nao traz o titulo (caso da FGV), pela disciplina com mais palavras-chave.
 */
@Component
public class SpecificSubjectClassifier {

    public static final String FALLBACK = "Conhecimentos Específicos";

    private static final Map<String, List<String>> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put("Desenvolvimento de Software", List.of(
                "spring", "hibernate", "java", "framework", "xml", "xslt", "json", "android", "ios",
                "aplicativos moveis", "react", "javascript", "typescript", "python", "api", "rest",
                "orientacao a objetos", "classe", "metodo", "heranca", "polimorfismo", "codigo"));
        KEYWORDS.put("Engenharia e Arquitetura de Software", List.of(
                "arquitetura de software", "design e arquitetura", "padrao de projeto", "padroes de projeto",
                "solid", "liskov", "microsservicos", "monolit", "spa", "pwa", "servidor de aplicacoes",
                "mensuracao de tamanho", "pontos de funcao", "requisitos", "uml", "testes", "teste unitario",
                "arquitetura de aplicacoes", "orientada a servicos", "soa", "mensageria", "acoplamento"));
        KEYWORDS.put("Banco de Dados", List.of(
                "banco de dados", "bancos de dados", "sql", "nosql", "relacional", "relacionais",
                "multidimensional", "multidimensionais", "normalizacao", "chave primaria", "indice", "transacao"));
        KEYWORDS.put("Ciência de Dados e BI", List.of(
                "etl", "elt", "data warehouse", "data lake", "olap", "business intelligence", "bi",
                "inteligencia artificial", "aprendizado de maquina", "machine learning", "ingestao de dados",
                "suporte a decisao", "ssd", "mineracao de dados", "fontes de dados", "big data"));
        KEYWORDS.put("Segurança da Informação", List.of(
                "seguranca", "owasp", "vulnerabilidade", "criptografia", "tls", "ssl", "https",
                "controle de acesso", "autenticacao", "x.800", "firewall", "ataque", "malware", "blockchain",
                "hash", "assinatura digital", "integridade"));
        KEYWORDS.put("Redes e Infraestrutura", List.of(
                "redes", "rede", "lan", "wan", "man", "tcp", "udp", "protocolo", "dns", "roteador",
                "switch", "nuvem", "cloud", "virtualizacao"));
        KEYWORDS.put("DevOps", List.of(
                "devops", "integracao continua", "entrega continua", "ci/cd", "pipeline",
                "gerenciamento de configuracao", "docker", "kubernetes", "infraestrutura como codigo"));
        KEYWORDS.put("Gestão de Projetos e Métodos Ágeis", List.of(
                "scrum", "agil", "ageis", "kanban", "sprint", "product owner", "scrum master",
                "gerenciamento de projetos", "pmbok", "backlog", "retrospectiva"));
    }

    private static final Map<String, List<Pattern>> PATTERNS = new LinkedHashMap<>();

    static {
        KEYWORDS.forEach((subject, words) -> PATTERNS.put(subject, words.stream()
                .map(w -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(w) + "(?![\\p{L}\\p{N}])"))
                .toList()));
    }

    public String classify(String text) {
        String normalized = normalize(text);
        String best = FALLBACK;
        int bestScore = 0;
        for (Map.Entry<String, List<Pattern>> e : PATTERNS.entrySet()) {
            int score = 0;
            for (Pattern p : e.getValue()) {
                score += (int) p.matcher(normalized).results().count();
            }
            if (score > bestScore) {
                bestScore = score;
                best = e.getKey();
            }
        }
        return best;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }
}
