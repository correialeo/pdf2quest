# Aulas de demonstração do formato interativo

Duas aulas para avaliar na tela o formato novo:

| Aula | Mostra |
|---|---|
| `sql-pandas` | `:::dados`, `sql run` + `saida`, `tarefa sql`, `python run` (pandas), `tarefa python`, `pyspark run` (simulação) |
| `matematica-financeira` | `formula` (cartão com KaTeX), `calculo` (exercício guiado com 5 etapas, erros típicos e números sorteados) |

Cada uma tem o conteúdo (`.md`), os demais parâmetros de `criar_aula` (`.json`: questões de fixação com `conceitos`,
fontes etc.) e os trechos literais das fontes em `trechos.json`, baixados com `curl` das páginas oficiais em
2026-11-02 (SQLite, pandas, Spark, Wikipédia "Juro").

**Atenção:** `estiloDaBanca` e `referencias` são de **demonstração**, sem pesquisa de questões reais da FCC (o texto
diz isso). Numa aula de verdade, o agente pesquisa a banca antes.

## Criar no sistema

Com o pdf2questao rodando (código novo, depois de `./instalar-runtimes.sh`):

```bash
node docs/aulas-demo/criar-demos.mjs            # http://localhost:8080
node docs/aulas-demo/criar-demos.mjs http://localhost:8099
```

O script faz o que o agente faz pelo MCP: `registrar_trecho` para cada trecho e `criar_aula` com os `trechos`. Ele
imprime o link de cada aula. O teste `InteractiveLessonIntegrationTest` cria as duas a partir destes mesmos arquivos.
