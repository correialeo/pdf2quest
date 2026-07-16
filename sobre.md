# Concursos — ferramenta local de estudos para concursos públicos

Uma ferramenta web **100% local**, feita para rodar na minha própria máquina,
sem login, sem nuvem e sem depender de nenhum serviço externo. A ideia é
simples: jogar provas em PDF lá dentro e estudar por elas — resolvendo
questões avulsas, montando simulados com nota calculada, e acompanhando a
evolução ao longo do tempo.

Feita hoje com foco no edital da Dataprev, mas serve pra qualquer concurso
cujas provas venham em PDF.

## Stack

- **Java 21 + Spring Boot 3** (MVC + Thymeleaf) no back-end e nas telas.
- **SQLite** como banco — um único arquivo (`concursos.db`), sem servidor de
  banco rodando, sem configuração.
- **Apache PDFBox** para extrair o texto dos PDFs de prova e de gabarito.
- **Bootstrap 5** + um pouco de JS puro nas telas.
- Sobe tudo com `mvn spring-boot:run` e sem nenhuma configuração adicional.

## O que dá pra fazer

### 1. Importar a prova (PDF)

Você sobe o PDF da prova e o sistema lê o texto e tenta identificar sozinho:

- a **banca** organizadora (CEBRASPE, FGV, etc.);
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

1. Sobe o PDF do gabarito;
2. Digita o título exato do perfil que quer buscar dentro do documento (ex.:
   `ATI - DESENVOLVIMENTO DE SOFTWARE – PROVA TIPO 1`);
3. Escolhe qual importação de prova (já feita antes) quer vincular.

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
destaque).

## Limitações conhecidas / bugs a corrigir

- O parsing de PDF é heurístico — provas com formatação muito fora do padrão
  ainda podem gerar falhas de importação (ficam registradas no relatório, não
  travam o processo).
- Bug em que o sistema coloca alguma parte do texto interpretativo como disciplina
  na disciplina de Inglês, por ter textos grandes antes da questão.
- Ainda não tem tela de edição pra corrigir disciplina/categoria/assunto
  detectados errado — hoje só ajustando direto no banco SQLite.
- É a primeira leva funcional do recurso de gabarito separado — ainda pode
  ter arestas a aparar em formatos de tabela diferentes do testado.
- Sem autenticação/multiusuário de propósito — é uma ferramenta pessoal, pra
  rodar local mesmo.
- Precisamos colocar um botão para exportar o banco concursos.db (baixar o arquivo .sql).
