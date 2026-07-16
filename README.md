# Concursos — ferramenta local de estudos

Ferramenta local (single-user, sem login) para importar provas em PDF, resolver
questões, montar simulados e acompanhar evolução nos estudos. Feita para o
edital da Dataprev (Conhecimentos Gerais peso 1, Conhecimentos Específicos peso 2,5).

## Rodando

```
mvn spring-boot:run
```

Acesse http://localhost:8080 — abre direto no dashboard.

Requer Java 21. Se `java -version` não mostrar 21, ajuste `JAVA_HOME`.

## Fluxo

1. **Importar PDF** (`/import`): sobe uma prova em PDF. Banca, ano, disciplina
   e categoria (Conhecimentos Gerais/Específicos) são detectados automaticamente
   a partir dos títulos de seção do próprio documento. "Assunto" não é
   detectado (raramente aparece no texto da prova).
2. **Questões** (`/questoes`): filtra por disciplina/assunto/ano/banca e inicia
   uma sessão de prática.
3. **Simulado** (`/simulado`): monta um simulado por quantidade de questões de
   Gerais/Específicas e calcula a nota do edital ao final.
4. **Caderno de Erros** (`/caderno-erros`): lista questões cuja última tentativa
   foi errada; permite refazer todas de uma vez.
5. **Dashboard** (`/dashboard`): totais, percentual por disciplina/assunto e
   histórico de simulados.

## Limitações conhecidas

- Parsing de PDF é heurístico (regex); provas com formatação incomum podem
  gerar falhas de importação (registradas no relatório, sem travar o processo).
- "Assunto" e correções de disciplina/categoria mal detectadas ainda não têm
  tela de edição — hoje só é possível ajustar direto no banco SQLite
  (`concursos.db`).
