=== GROUP BY: uma linha por grupo {#sql-group-by}
**Definição.** `GROUP BY` junta as linhas que têm o mesmo valor numa coluna e devolve **uma linha por grupo**; as funções de agregação (`SUM`, `COUNT`, `AVG`, `MAX`, `MIN`) resumem cada grupo.

**Por quê.** A pergunta de negócio quase nunca é "quais são os débitos", e sim "quanto cada contribuinte deve": o total é por grupo, não por linha.

:::dados debitos
contribuinte,municipio,valor
Alfa Ltda,Santos,1200
Beta SA,Campinas,800
Alfa Ltda,Santos,450
Gama ME,Santos,300
:::

```sql run {#sql-group-by-exemplo}
SELECT contribuinte, SUM(valor) AS total
FROM debitos
GROUP BY contribuinte
ORDER BY total DESC;
```

```saida
contribuinte | total
Alfa Ltda | 1650
Beta SA | 800
Gama ME | 300
```

Leia o resultado a partir dos dados: as duas linhas da Alfa Ltda (1200 e 450) viraram **uma** linha com 1650. Mude a consulta acima (ex.: troque `SUM` por `COUNT(*)`) e rode de novo.

:::pegadinha Coluna fora do GROUP BY {#sql-coluna-fora}
Toda coluna do `SELECT` que não está dentro de uma agregação tem de estar no `GROUP BY`. `SELECT contribuinte, municipio, SUM(valor) ... GROUP BY contribuinte` é **erro** no padrão SQL (o SQLite aceita e pega um valor qualquer, o que a banca trata como incorreto).
:::

[[questão 1]]

=== WHERE filtra linhas, HAVING filtra grupos {#sql-having}
**Regra.** `WHERE` age **antes** do agrupamento, linha a linha; `HAVING` age **depois**, sobre o resultado da agregação. Por isso `WHERE SUM(valor) > 1000` é inválido: na hora do `WHERE` o `SUM` ainda não existe.

```sql run {#sql-having-exemplo}
SELECT municipio, COUNT(*) AS qtd, SUM(valor) AS total
FROM debitos
WHERE valor >= 400
GROUP BY municipio
HAVING SUM(valor) > 1000;
```

```saida
municipio | qtd | total
Santos | 2 | 1650
```

Siga a ordem: o `WHERE` tirou o débito de 300 (Gama ME); sobraram Santos (1200 + 450) e Campinas (800); o `HAVING` tirou Campinas, cujo total não passa de 1000.

:::pegadinha Ordem lógica {#sql-ordem-logica}
FROM → WHERE → GROUP BY → HAVING → SELECT → ORDER BY. Por isso o apelido criado no `SELECT` (`total`) pode ser usado no `ORDER BY`, mas não no `WHERE`.
:::

[[questão 2]]

Agora com outra tabela, para aplicar a regra a um caso novo:

:::dados parcelamentos
cpf,regime,parcela
111,Simples,250
222,Normal,900
333,Simples,410
444,Normal,120
555,Simples,90
:::

```tarefa sql
### enunciado
Mostre cada **regime** com a **média das parcelas** (coluna `media`), só dos regimes com **mais de dois** parcelamentos.
### codigo
SELECT regime
FROM parcelamentos;
### esperado
regime | media
Simples | 250
### dica
Agrupe por regime, use AVG(parcela) AS media e filtre os grupos com HAVING COUNT(*) > 2.
### solucao
SELECT regime, AVG(parcela) AS media
FROM parcelamentos
GROUP BY regime
HAVING COUNT(*) > 2;
```

=== pandas: groupby {#pd-groupby}
**Mesma ideia em pandas.** `df.groupby("coluna")` separa as linhas em grupos; em seguida escolhe-se a coluna e a agregação. O resultado é uma `Series` indexada pelo grupo.

```python run {#pd-groupby-exemplo}
import pandas as pd
df = pd.read_csv("dados/debitos.csv")
df.groupby("contribuinte")["valor"].sum().sort_values(ascending=False)
```

```saida
contribuinte
Alfa Ltda    1650
Beta SA       800
Gama ME       300
```

:::pegadinha groupby sem agregação {#pd-groupby-lazy}
`df.groupby("contribuinte")` sozinho **não calcula nada**: devolve um objeto `DataFrameGroupBy`. Só depois de `.sum()`, `.mean()`, `.agg(...)` aparece o resultado. E `.count()` conta valores **não nulos** por coluna, diferente de `.size()`, que conta linhas.
:::

[[questão 3]]

```tarefa python
### enunciado
Com a tabela `parcelamentos` (arquivo `dados/parcelamentos.csv`), crie `resultado`: a **soma das parcelas por regime**, como Series indexada pelo regime.
### codigo
import pandas as pd
df = pd.read_csv("dados/parcelamentos.csv")
resultado = None  # TODO: agrupe por regime e some a parcela
### teste
assert resultado is not None, "crie a variável resultado"
assert resultado.loc["Simples"] == 750, "a soma do Simples deveria ser 750"
assert resultado.loc["Normal"] == 1020, "a soma do Normal deveria ser 1020"
### dica
Agrupe pela coluna regime, escolha a coluna parcela e some: df.groupby(...)[...].sum()
### solucao
import pandas as pd
df = pd.read_csv("dados/parcelamentos.csv")
resultado = df.groupby("regime")["parcela"].sum()
```

=== PySpark: transformação x ação {#spark-lazy}
**Regra.** No Spark, `filter`, `select`, `groupBy`, `orderBy` são **transformações**: só montam o plano (avaliação preguiçosa). Nada roda até uma **ação** (`show`, `count`, `collect`), e cada ação dispara um **job**. Cada *shuffle* (redistribuição dos dados entre partições, causada por `groupBy`, `join`, `orderBy`, `distinct`) abre um novo **stage**.

**Por quê.** Adiar a execução permite ao Spark otimizar o plano inteiro antes de mexer nos dados.

```pyspark run {#spark-lazy-exemplo}
from pyspark.sql import SparkSession, functions as F
spark = SparkSession.builder.appName("aula").getOrCreate()
df = spark.read.csv("dados/debitos.csv", header=True, inferSchema=True)
por_municipio = (df.filter(F.col("valor") >= 400)
                   .groupBy("municipio")
                   .agg(F.sum("valor").alias("total")))
por_municipio.explain()
print("Até aqui nenhum job rodou.")
por_municipio.orderBy("municipio").show()
```

```saida
== Plano físico (SIMULAÇÃO didática, não é o Spark) ==
HashAggregate keys=[municipio], funções=[sum(valor) AS total]
   +- Exchange (shuffle: novo stage, 200 partições)
      +- Filter (valor >= 400)
         +- FileScan csv [dados/debitos.csv]
Até aqui nenhum job rodou.
+---------+-----+
|municipio|total|
+---------+-----+
| Campinas|  800|
|   Santos| 1650|
+---------+-----+
```

Veja o painel abaixo da saída ao rodar: **1 job** (só o `show` é ação) com **3 stages** (leitura/filtro, depois do shuffle do `groupBy`, depois do shuffle do `orderBy`).

:::pegadinha cache() também é preguiçoso {#spark-cache}
`df.cache()` só **marca** o DataFrame; ele é guardado na primeira ação. A partir da segunda ação, os stages anteriores ao cache não rodam de novo. Teste: acrescente `por_municipio.cache()` antes do `show` e um `por_municipio.count()` depois.
:::

=== Resumo {#resumo-agregacao}
| | SQL | pandas | PySpark |
|---|---|---|---|
| Agrupar | `GROUP BY col` | `df.groupby("col")` | `df.groupBy("col")` |
| Somar | `SUM(valor)` | `["valor"].sum()` | `.agg(F.sum("valor"))` |
| Filtrar linha | `WHERE` | `df[df.valor >= 400]` | `.filter(...)` |
| Filtrar grupo | `HAVING` | filtro no resultado | `.filter(...)` depois do `agg` |
| Executa quando | na hora | na hora | só na **ação** |
