package com.pdf2questao.question;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BancaReferencesTest {

    @Test
    void mesmaDisciplinaComNomesDiferentes() {
        assertTrue(BancaReferences.sameSubject("Português", "Língua Portuguesa"));
        assertTrue(BancaReferences.sameSubject("Direito Tributário", "Direito Tributário Avançado"));
        assertTrue(BancaReferences.sameSubject("AFO", "Administração Financeira e Orçamentária"));
        assertTrue(BancaReferences.sameSubject("contabilidade geral", "Contabilidade Geral"));
        assertFalse(BancaReferences.sameSubject("Direito Tributário", "Direito Constitucional"));
        assertFalse(BancaReferences.sameSubject("Contabilidade Geral", "Contabilidade Pública"));
        assertFalse(BancaReferences.sameSubject("Português", null));
    }

    @Test
    void idsCitadosNasReferencias() {
        String refs = "questão local #12\nQuestao local #7 (mesmo assunto)\n#30\nprova local #5\n"
                + "https://www.qconcursos.com/questoes/123";
        assertEquals(List.of(12L, 7L, 30L), BancaReferences.questionIds(refs));
        assertEquals(List.of(5L), BancaReferences.examIds(refs));
        assertEquals(List.of(), BancaReferences.questionIds(null));
    }
}
