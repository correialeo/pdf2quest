# Formato JSON de questões do PDF2Questão (versão 1)

Formato nativo para importar questões sem PDF (menu **Importar planilha/JSON**). Serve para gerar bancos de
questões em outros programas, compartilhar questões entre pessoas ou migrar de outra ferramenta.

- Exemplo completo: [`exemplo-questoes.json`](exemplo-questoes.json) (o mesmo que a tela oferece para baixar).
- Esquema JSON (para validar no seu programa): [`schema-questoes-v1.json`](schema-questoes-v1.json).
- Arquivo em UTF-8. Campos com valor `null` ou texto vazio contam como ausentes.

## Estrutura

```json
{
  "formato": "pdf2questao-questoes",
  "versao": 1,
  "prova": { "titulo": "...", "banca": "...", "concurso": "...", "ano": 2024, "disciplina": "..." },
  "questoes": [ { ... }, { ... } ]
}
```

| Campo      | Obrigatório | Descrição |
|------------|-------------|-----------|
| `formato`  | recomendado | Sempre `"pdf2questao-questoes"`. Ausente: aviso; diferente: erro. |
| `versao`   | sim         | Versão do formato. Esta versão do programa lê `1`; outra versão é recusada. |
| `prova`    | não         | Valores padrão para todas as questões (cada questão pode sobrescrever). `titulo` vira o nome da prova na lista. |
| `questoes` | sim         | Lista com pelo menos uma questão. |

## Questão

| Campo         | Tipo                      | Descrição |
|---------------|---------------------------|-----------|
| `id`          | texto                     | Identificador estável, único no banco (até 255 caracteres). Recomendado: permite reimportar sem duplicar e importar o gabarito depois. Ex.: `"fgv-2024-tjsp-q12"`. |
| `numero`      | inteiro                   | Número na prova original. Ausente: a posição na lista. Usado na ordem e nas faixas dos blocos. |
| `tipo`        | texto                     | `"multipla_escolha"` ou `"certo_errado"`. Ausente: certo/errado se não houver alternativas, múltipla escolha se houver. |
| `enunciado`   | texto                     | **Obrigatório.** Quebras de linha com `\n`. |
| `textoBase`   | texto                     | Texto de apoio. Questões com o mesmo texto base no arquivo compartilham o mesmo texto. |
| `alternativas`| lista                     | Múltipla escolha: de 2 a 5. Cada item é um texto (letras A, B, C... pela ordem) ou `{ "letra": "A", "texto": "..." }`. Certo/errado: omitir. |
| `resposta`    | texto                     | Letra correta (`"B"`) ou, em certo/errado, `"Certo"`/`"Errado"` (`C`/`E`, `V`/`F` também valem). Ausente: questão sem gabarito (pode ser importado depois). Uma lista com mais de uma resposta é recusada nesta versão. |
| `anulada`     | booleano                  | `true`: questão anulada (ponto para todos, sem letra correta). |
| `explicacao`  | texto                     | Comentário/justificativa da resposta. |
| `banca`, `concurso`, `ano`, `disciplina` | texto/inteiro | Sobrescrevem os valores de `prova`. `ano` entre 1900 e 2100. |
| `assunto`     | texto                     | Assunto dentro da disciplina. |
| `subtopicos`, `tags`, `referencias` | lista de textos | Listas livres. |
| `dificuldade` | texto                     | Livre (ex.: `"fácil"`). |
| `categoria`   | texto                     | `"geral"` ou `"especifico"` (conhecimentos gerais/específicos). |
| `fonte`       | texto                     | De onde a questão veio (livro, site...). |
| `metadados`   | objeto                    | Qualquer informação extra; é guardada como está. |

Campos desconhecidos são ignorados com aviso na prévia.

## Validação e prévia

Nada é gravado sem confirmação. A prévia mostra cada questão com um destes estados:

- **nova**: será criada.
- **já cadastrada**: já existe questão com o mesmo `id`. Por padrão fica como está; o usuário pode marcar
  "atualizar" (o conteúdo é substituído, o id interno, a prova e o histórico de respostas são mantidos).
- **possível duplicata**: sem `id` em comum, mas o enunciado é igual (ignorando acentos, maiúsculas e espaços) ao de
  uma questão cadastrada ou de outra do mesmo arquivo. Só entra se o usuário pedir.
- **com erro**: nunca é gravada. Exemplos: sem enunciado, alternativa vazia ou repetida, mais de 5 alternativas,
  resposta que não é uma das alternativas, `id` repetido no arquivo (nenhuma das cópias entra), tipo desconhecido.

A gravação é feita numa transação: se algo falhar, nenhuma questão da importação fica no banco.

## Gabarito separado

Menu **Importar gabarito › Gabarito em planilha/texto**. Uma resposta por linha (`id;resposta`, `12,C`, `12 - E`;
anulada: `anulada` ou `X`) ou JSON:

```json
{
  "formato": "pdf2questao-gabarito",
  "versao": 1,
  "respostas": [
    { "id": "fgv-2024-tjsp-q12", "resposta": "C" },
    { "id": "fgv-2024-tjsp-q13", "anulada": true }
  ]
}
```

Por padrão as respostas são ligadas pelo `id`. Ligar pelo número da questão exige escolher a prova, que pelo
menos metade das respostas exista nela e a confirmação explícita do usuário; números repetidos na prova não são
associados. Respostas diferentes das já gravadas aparecem como conflito e só são trocadas se o usuário pedir.

## CSV

A tela de planilha aceita qualquer layout: o separador (`;`, `,`, tabulação ou `|`) é reconhecido, e o usuário
escolhe qual coluna é cada campo. Alternativas podem estar uma por coluna (A–E) ou todas numa coluna com um
separador. Listas (subtópicos, tags) dentro de uma célula são separadas por `|`. Arquivos em UTF-8 ou no padrão
do Excel em português (Windows-1252) são lidos. Exemplo: `src/main/resources/static/exemplos/questoes-exemplo.csv`.
