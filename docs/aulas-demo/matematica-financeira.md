=== Juros compostos: juros sobre juros {#jc-montante}
**Definição.** No regime de juros compostos, o juro de cada período é calculado sobre o montante do período anterior, e não só sobre o capital inicial.

**Por quê.** É assim que se remunera um capital que fica aplicado: o juro do 1º mês também rende no 2º. Por isso, para o mesmo prazo e taxa, o montante composto supera o simples sempre que n > 1.

```formula {#jc-formula}
{"titulo": "Montante a juros compostos",
 "formula": "M = C\\,(1+i)^{n}",
 "simbolos": [
   {"simbolo": "M", "significado": "montante: capital mais juros no fim do prazo", "unidade": "R$"},
   {"simbolo": "C", "significado": "capital inicial (valor aplicado ou emprestado)", "unidade": "R$"},
   {"simbolo": "i", "significado": "taxa de juros por período, em forma decimal (2% = 0,02)", "unidade": "por período"},
   {"simbolo": "n", "significado": "número de períodos, na MESMA unidade da taxa", "unidade": "períodos"}],
 "quandoUsar": "Sempre que o enunciado falar em juros compostos, capitalização composta, ou juros que incidem sobre juros já acumulados.",
 "pegadinha": "Taxa e prazo em unidades diferentes: com taxa ao mês e prazo em anos, converta o prazo para meses (ou a taxa para anual equivalente) antes de aplicar a fórmula."}
```

:::exemplo Do capital ao montante
| Dado | Valor |
|---|---|
| Capital C | R$ 10.000,00 |
| Taxa i | 2% ao mês = 0,02 |
| Prazo n | 6 meses |
+++
| Conta | Resultado |
|---|---|
| (1 + 0,02)^6 | 1,126162 |
| M = 10.000 × 1,126162 | **R$ 11.261,62** |
| Juros = M − C | R$ 1.261,62 |
:::

A juros simples seriam 10.000 × 0,02 × 6 = R$ 1.200,00 de juros: o composto rendeu R$ 61,62 a mais porque o juro de cada mês também rendeu.

[[questão 1]]

Agora aplique a fórmula com outros números (eles mudam a cada tentativa):

```calculo
{"titulo": "Montante com prazo em anos",
 "conceito": "jc-montante",
 "enunciado": "Um capital de R$ {C} é aplicado a juros compostos de {taxa}% ao mês durante {anos} ano(s). Calcule o montante.",
 "variaveis": {"C": {"min": 5000, "max": 50000, "passo": 1000},
               "taxa": {"min": 1, "max": 3, "passo": 0.5},
               "anos": {"min": 1, "max": 3, "passo": 1}},
 "exemplo": {"C": 10000, "taxa": 2, "anos": 0.5},
 "etapas": [
   {"titulo": "Identificar os dados", "pergunta": "Qual é o capital C?", "resposta": "C", "unidade": "R$",
    "dica": "O capital é o valor aplicado no início."},
   {"titulo": "Converter a taxa", "pergunta": "Qual é a taxa mensal em forma decimal?", "resposta": "taxa/100",
    "tolerancia": 0.00001, "dica": "Divida a taxa percentual por 100.",
    "errosTipicos": [{"resposta": "taxa", "mensagem": "Você usou a taxa em porcentagem; na fórmula ela entra em decimal (divida por 100)."}]},
   {"titulo": "Acertar o prazo", "pergunta": "Quantos períodos n, na mesma unidade da taxa?", "resposta": "anos*12", "unidade": "meses",
    "tolerancia": 0, "dica": "A taxa é mensal: converta os anos em meses.",
    "errosTipicos": [{"resposta": "anos", "mensagem": "Você usou taxa mensal com prazo em anos. Converta o prazo para meses."}]},
   {"titulo": "Montar a fórmula", "pergunta": "Quanto vale o fator (1 + i)^n?", "resposta": "(1+e2)^e3", "tolerancia": 0.0005,
    "dica": "Some 1 à taxa decimal e eleve ao número de meses.",
    "errosTipicos": [{"resposta": "1+e2*e3", "mensagem": "Isso é o fator de juros simples (1 + i·n). Em juros compostos, eleve (1 + i) a n."}]},
   {"titulo": "Calcular", "pergunta": "Qual é o montante M?", "resposta": "e1*e4", "unidade": "R$", "tolerancia": 1,
    "dica": "Multiplique o capital pelo fator.",
    "errosTipicos": [{"resposta": "C*(1+e2*e3)", "mensagem": "Esse é o montante a juros simples."},
                     {"resposta": "C*(1+e2)^anos", "mensagem": "Você elevou ao número de anos com taxa mensal."}]}],
 "resposta": "C*(1+taxa/100)^(anos*12)",
 "interpretacao": "Em {anos} ano(s), R$ {C} viram R$ {resposta}: os juros de cada mês passaram a render nos meses seguintes."}
```

=== Taxas equivalentes x proporcionais {#jc-taxa-equivalente}
**Definição.** Duas taxas são **equivalentes** quando, aplicadas ao mesmo capital pelo mesmo prazo, produzem o mesmo montante a juros compostos.

**Por quê.** A banca dá a taxa numa unidade e o prazo em outra; a conversão correta em regime composto é por potência, não por multiplicação.

```formula {#jc-formula-equivalente}
{"titulo": "Taxa anual equivalente a uma taxa mensal",
 "formula": "1 + i_a = (1 + i_m)^{12}",
 "simbolos": [
   {"simbolo": "i_a", "significado": "taxa anual equivalente, em decimal", "unidade": "ao ano"},
   {"simbolo": "i_m", "significado": "taxa mensal, em decimal", "unidade": "ao mês"}],
 "quandoUsar": "Para trocar a unidade da taxa em juros compostos (mês ↔ ano, mês ↔ trimestre).",
 "pegadinha": "Multiplicar a taxa mensal por 12 dá a taxa PROPORCIONAL (nominal), que só vale em juros simples."}
```

:::exemplo 1% ao mês em taxa anual
| Conta | Resultado |
|---|---|
| (1 + 0,01)^12 | 1,126825 |
| i_a = 1,126825 − 1 | **12,68% ao ano** |
| Proporcional: 1% × 12 | 12% ao ano (não é equivalente) |
:::

:::pegadinha Taxa nominal com capitalização {#jc-nominal}
"24% ao ano com capitalização mensal" é taxa **nominal**: a taxa efetiva mensal é 24% ÷ 12 = 2% ao mês, e a efetiva anual é (1,02)^12 − 1 ≈ 26,82%. A banca troca nominal por efetiva nas alternativas.
:::

[[questão 2]]

```calculo
{"titulo": "Taxa anual equivalente",
 "conceito": "jc-taxa-equivalente",
 "enunciado": "Uma aplicação rende {m}% ao mês, a juros compostos. Qual é a taxa anual equivalente?",
 "variaveis": {"m": {"min": 0.5, "max": 2.5, "passo": 0.25}},
 "exemplo": {"m": 1},
 "etapas": [
   {"titulo": "Converter a taxa", "pergunta": "Taxa mensal em decimal?", "resposta": "m/100", "tolerancia": 0.00001,
    "dica": "Divida a taxa percentual por 100.",
    "errosTipicos": [{"resposta": "m", "mensagem": "A taxa entra em decimal na fórmula (divida por 100)."}]},
   {"titulo": "Capitalizar 12 meses", "pergunta": "Quanto vale (1 + i_m)^12?", "resposta": "(1+e1)^12", "tolerancia": 0.0005,
    "dica": "Some 1 à taxa decimal e eleve a 12.",
    "errosTipicos": [{"resposta": "1+12*e1", "mensagem": "Isso é capitalização simples (1 + 12·i). Use a potência."}]},
   {"titulo": "Interpretar", "pergunta": "Taxa anual equivalente, em %?", "resposta": "(e2-1)*100", "unidade": "% ao ano",
    "tolerancia": 0.02, "dica": "Subtraia 1 do fator e multiplique por 100.",
    "errosTipicos": [{"resposta": "12*m", "mensagem": "Essa é a taxa proporcional (m × 12), que não é equivalente em juros compostos."},
                     {"resposta": "e2*100", "mensagem": "Faltou subtrair 1 do fator antes de passar para porcentagem."}]}],
 "resposta": "((1+m/100)^12-1)*100",
 "interpretacao": "{m}% ao mês equivalem a {resposta}% ao ano: mais que {m} × 12, porque os juros rendem sobre juros."}
```

[[questão 3]]

=== Resumo {#jc-resumo}
| Situação | Juros simples | Juros compostos |
|---|---|---|
| Montante | M = C(1 + i·n) | M = C(1 + i)^n |
| Trocar a unidade da taxa | proporcional: i × k | equivalente: (1 + i)^k − 1 |
| n > 1 período | rende menos | rende mais |
| Pegadinha | — | taxa e prazo em unidades diferentes; nominal × efetiva |
