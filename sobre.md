# PDF2Questao — ferramenta local de estudos para concursos públicos

Uma ferramenta web **100% local**, feita para rodar na minha própria máquina,
sem login, sem nuvem e sem depender de nenhum serviço externo. A ideia é
simples: jogar provas em PDF lá dentro e estudar por elas — resolvendo
questões avulsas, montando simulados com nota calculada, e acompanhando a
evolução ao longo do tempo.

Feita hoje com foco no edital da Dataprev, mas serve pra qualquer concurso
cujas provas venham em PDF.

## Stack

- **Java 21 + Spring Boot 3** (MVC + Thymeleaf) no back-end e nas telas.
- **SQLite** como banco — um único arquivo (`pdf2questao.db`), sem servidor de
  banco rodando, sem configuração.
- **Apache PDFBox** para extrair o texto dos PDFs de prova e de gabarito.
- **Bootstrap 5** + um pouco de JS puro nas telas.
- Sobe tudo com `mvn spring-boot:run` e sem nenhuma configuração adicional.

## O que dá pra fazer

### 1. Importar a prova (PDF)

Você escolhe a **banca** organizadora (obrigatório) e sobe o PDF da prova. A
banca escolhida define qual heurística de leitura vai ser usada — cada banca
tem seu próprio jeito de formatar prova em PDF, então cada uma tem seu
próprio parser (ver seção "Parsers por banca" abaixo). Hoje só FGV está
implementada; as demais aparecem na lista mas desabilitadas.

A partir do texto extraído, o sistema tenta identificar sozinho:

- o **ano** da prova;
- a **disciplina** de cada questão (a partir dos títulos de seção do próprio
  PDF, ex.: "Língua Portuguesa", "Direito Previdenciário");
- se a questão é de **Conhecimentos Gerais** ou **Conhecimentos
  Específicos**;
- o **número original** da questão na prova (pra depois poder reconstituir a
  ordem exata do PDF);
- o enunciado, as alternativas (A a E) e, quando o gabarito já vem embutido
  no mesmo PDF, a resposta correta.

O parser é baseado em heurísticas/regex (não é um LLM nem nada "mágico") e
foi ajustado em cima de provas reais pra lidar com formatos diferentes:
questões numeradas com separador (`1 - texto`) ou só o número sozinho numa
linha, cabeçalhos de disciplina em CAIXA ALTA ou em Title Case, alternativas
que quebram em várias linhas no PDF, etc. Quando alguma questão não pode ser
importada (por exemplo, faltam alternativas), ela não trava o processo — só
entra numa lista de falhas no relatório final da importação.

### 2. Importar o gabarito separado (PDF)

Muita banca solta o gabarito num PDF **diferente** do da prova, e esse PDF
geralmente cobre vários cargos/perfis de uma vez só (ex.: "TIPO 1", "TIPO 2",
"TIPO 3"...). Pra esse caso, tem uma tela separada onde você:

1. Escolhe a **banca** (obrigatório — mesma lógica da importação de prova,
   define qual parser lê a tabela de respostas);
2. Sobe o PDF do gabarito;
3. Digita o título exato do perfil que quer buscar dentro do documento (ex.:
   `ATI - DESENVOLVIMENTO DE SOFTWARE – PROVA TIPO 1`);
4. Escolhe qual importação de prova (já feita antes) quer vincular.

O sistema localiza a tabela de respostas daquele perfil dentro do PDF,
extrai as respostas (a formatação da tabela varia — às vezes vem tudo numa
linha só, às vezes uma letra por linha — o parser lida com os dois casos) e
vincula cada resposta à questão correspondente pelo número original dela na
prova. No final mostra quantas questões foram vinculadas com sucesso e quais
números não bateram com nada.

### 3. Questões (prática livre)

Filtra o banco de questões por disciplina, assunto, ano e banca, escolhe a
quantidade, e começa a resolver. Tem a opção de **manter a ordem original da
prova** — o que, como as disciplinas ocupam faixas contínuas de número
dentro da prova, acaba tendo o efeito colateral bacana de também agrupar as
questões por disciplina.

### 4. Simulado (nota do edital)

Monta um simulado escolhendo quantas questões de Conhecimentos Gerais e
quantas de Conhecimentos Específicos entram, com a mesma opção de manter a
ordem/agrupamento da prova. Ao final, calcula a nota seguindo o peso do
edital da Dataprev (Gerais peso 1, Específicas peso 2,5).

### 5. Resolução e resultado

Durante a resolução, o sistema **não mostra a resposta correta** — só
depois de finalizar a sessão (seja prática, simulado ou refazer erros) é que
a tela de resultado exibe: total de acertos/erros, percentual, nota Dataprev
(no caso de simulado), tempo total e médio por questão, e uma **revisão
questão a questão** (sua resposta vs. a resposta certa, com selo de
acertou/errou).

### 6. Caderno de Erros

Lista automaticamente todas as questões cuja última tentativa foi errada, e
tem um botão pra refazer todas elas de uma vez numa sessão só.

### 7. Dashboard

Visão geral: total de questões cadastradas, quantas já foram respondidas ao
menos uma vez, percentual de acerto geral, desempenho por disciplina e por
assunto, e histórico dos simulados já feitos (com a última nota Dataprev em
destaque). Tem também um botão de **exportar o banco** (link "Exportar
Banco" no menu, e um botão no topo do próprio dashboard) que baixa um dump
`.sql` completo (schema + dados de todas as tabelas) gerado via JDBC puro —
não depende de ter o binário `sqlite3` instalado, só do driver que o
projeto já usa. Serve como backup: dá pra recriar o `pdf2questao.db` do zero
rodando o `.sql` baixado.

### 8. Parsers por banca (arquitetura heurística)

O parsing de PDF é heurístico — cada banca formata prova e gabarito do seu
próprio jeito, então em vez de um parser genérico único, o projeto usa um
padrão de plugin por banca:

- `ExamParser` e `GabaritoTableParser` são as interfaces que qualquer banca
  nova precisa implementar (uma pra ler a prova, outra pra ler a tabela de
  gabarito).
- Cada implementação é um `@Component` Spring marcado com a banca que atende
  (`banca()`), e o Spring já injeta a lista completa delas nos registries
  (`ExamParserRegistry` / `GabaritoParserRegistry`), que indexam por banca.
  Adicionar uma banca nova é só criar a classe nova — não precisa mexer em
  mais nada.
- Hoje só a **FGV** tem parser de verdade (`FgvPdfParser` /
  `FgvGabaritoParser`, o heurístico original do projeto). As outras bancas
  (Cesgranrio, Cebraspe, FCC, Vunesp) já existem no enum `Banca` e aparecem
  nas telas de importação, mas desabilitadas — são placeholders pro roadmap.
- As telas de importação de prova e de gabarito agora exigem escolher a
  banca antes de subir o PDF; é essa escolha que decide qual parser roda.

Esse desenho é o que deixa viável abrir o projeto como open-source mais pra
frente: cada banca vira uma contribuição isolada, sem precisar entender ou
arriscar quebrar o parser das outras.

## Limitações conhecidas / bugs a corrigir

- Ainda não tem tela de edição pra corrigir disciplina/categoria/assunto
  detectados errado, caso o heurístico erre em provas com formatação fora
  do padrão — hoje só ajustando direto no banco SQLite.
- Importação de gabarito separado: testei de ponta a ponta com o PDF real
  de gabarito da Dataprev (tabela em blocos de 20, números numa linha e
  letras na linha seguinte) e bateu 70 de 70 questões. Formatos de tabela
  diferentes do testado (bancas fora do padrão números-depois-letras) ainda
  são um ponto cego.
- Sem autenticação/multiusuário de propósito — é uma ferramenta pessoal, pra
  rodar local mesmo.
