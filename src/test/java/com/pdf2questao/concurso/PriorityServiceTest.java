package com.pdf2questao.concurso;

import com.pdf2questao.scoring.ScoringCalculator.RuleSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Nomes livres dos quizzes caem na disciplina certa do edital. */
class PriorityServiceTest {

    private static RuleSpec rule(String subject, String... aliases) {
        return new RuleSpec(subject, Set.of(aliases), 1, 10);
    }

    private final List<RuleSpec> rules = List.of(
            rule("Adm. Geral/Pública/Auditoria", "Administração Geral e Pública"),
            rule("Dir. Administrativo", "Direito Administrativo"),
            rule("Adm. Financeira e Orçamentária"),
            rule("Contabilidade Geral e Pública", "Contabilidade Geral"),
            rule("Direito Financeiro", "Finanças Públicas"),
            rule("Mat. Financeira/Estatística"),
            rule("Português", "Língua Portuguesa"));

    @Test
    void casaSiglaAbreviacaoEPrefixo() {
        assertEquals("Adm. Financeira e Orçamentária", PriorityService.match("AFO", rules).subject());
        assertEquals("Dir. Administrativo", PriorityService.match("Dir. Adm.", rules).subject());
        assertEquals("Contabilidade Geral e Pública", PriorityService.match("Contabilidade", rules).subject());
        assertEquals("Direito Financeiro", PriorityService.match("Dir. Financeiro", rules).subject());
        assertEquals("Mat. Financeira/Estatística", PriorityService.match("Mat. Fin.", rules).subject());
        assertEquals("Português", PriorityService.match("Língua Portuguesa", rules).subject());
        assertNull(PriorityService.match("Revisão", rules));
        assertNull(PriorityService.match("FCC", rules));
    }
}
