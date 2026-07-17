# Contribuindo

## Arquitetura de importação

A importação de provas e gabaritos segue um padrão de plugin por banca
organizadora, pensado pra crescer sem bagunçar o pacote conforme mais bancas
forem implementadas:

```
com.concursos.study.importer                    # orquestração
    Banca, ImportJob, ImportService, ImportController,
    ParseResult, ParsedQuestion, ParseFailure, GabaritoLinkResult

com.concursos.study.importer.parser              # contratos + descoberta
    ExamParser, GabaritoTableParser,
    ExamParserRegistry, GabaritoParserRegistry

com.concursos.study.importer.parser.support      # utilitários genéricos
    PdfTextUtils, PdfNoiseFilter, PdfMetadataDetector

com.concursos.study.importer.parser.<banca>      # implementação por banca
    ex.: importer.parser.fgv.FgvPdfParser, FgvGabaritoParser
```

`ImportService` depende só de `ExamParserRegistry` e `GabaritoParserRegistry`
— nunca de uma implementação de banca específica. Os registries são
populados automaticamente pelo Spring: cada parser é um `@Component` que
expõe `banca()`, e o Spring injeta `List<ExamParser>` /
`List<GabaritoTableParser>` no construtor do registry correspondente, que
indexa a lista por `Banca`. **Nenhum registry precisa ser editado
manualmente** ao adicionar uma banca nova — essa é a proposta central da
arquitetura.

## Como adicionar uma banca nova

1. Confirme que a banca já existe no enum `Banca` (`importer/Banca.java`).
   Se não existir, adicione o valor.
2. Crie o pacote `com.concursos.study.importer.parser.<banca>` (nome em
   minúsculo, ex.: `cesgranrio`).
3. Implemente `ExamParser`: uma classe `@Component` com `banca()` retornando
   o valor do enum e `parse(String rawText)` retornando um `ParseResult`.
   Essa é a leitura do PDF da prova.
4. Se a banca publicar o gabarito num PDF separado da prova, implemente
   também `GabaritoTableParser` (`banca()` + `parse(String rawText, String
   titulo)`). Se não tiver PDF de gabarito separado, pode pular esse passo —
   `ImportService.linkGabarito` já reporta "banca ainda não suportada" de
   forma graciosa quando não existe implementação registrada.
5. Reaproveite `PdfTextUtils`, `PdfNoiseFilter` e `PdfMetadataDetector` de
   `importer.parser.support` para lógica que não depende do layout
   específico da banca (formatação de título, ruído de rodapé/marca d'água,
   detecção de organização/ano) em vez de copiar código do `FgvPdfParser`.
   Só extraia lógica nova pra `support` quando um segundo consumidor
   realmente precisar dela — evite generalizar cedo demais.
6. Espelhe os testes de `FgvPdfParserTest` / `FgvGabaritoParserTest` pra
   banca nova, testando contra provas reais sempre que possível (o parser é
   heurístico/regex, então casos reais pegam formatações que exemplos
   sintéticos não cobrem).
7. Rode `mvn test` e confirme que a suíte inteira continua verde — os testes
   das outras bancas não devem quebrar com a adição de uma nova.

## Convenções gerais

- Composição em vez de herança: cada parser de banca é uma classe
  independente (não existe uma classe base abstrata de parser). O
  estado-máquina linha-a-linha de leitura de PDF varia demais de banca pra
  banca pra se beneficiar de um template method; lógica realmente
  compartilhada vai pra `importer.parser.support` como funções estáticas.
- `git mv` ao mover/renomear arquivos, pra preservar histórico.
- Sem comentários explicando o óbvio — só quando o porquê não é evidente
  pelo código (uma constante ajustada empiricamente contra um PDF real, por
  exemplo).
