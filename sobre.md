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
- **[Corrigido]** Testando com uma prova real (Dataprev/FGV, ATI -
  Desenvolvimento de Software) apareceram três bugs no parser de questões,
  todos já corrigidos e cobertos por testes de regressão:
  - Textos de apoio grandes de interpretação de texto — sobretudo em Língua
    Inglesa — têm linhas curtas (nome de autor, citação, título de obra)
    que o heurístico de cabeçalho confundia com um novo título de
    disciplina (nessa prova, as questões 13 a 19, todas de Língua Inglesa,
    saíam rotuladas com disciplina "Marketing Manager, Soulcore" e "Louis
    Ramirez" em vez de "Língua Inglesa"). O parser agora rastreia se já
    apareceu texto "de corpo" desde a última fronteira de bloco (cabeçalho
    de disciplina, seção CONHECIMENTOS ou frase de instrução do tipo "Use
    the following TEXT...") e só aceita uma linha como cabeçalho de
    disciplina se estiver logo no começo do intervalo entre questões —
    linhas parecidas com cabeçalho no meio de um texto de apoio são
    ignoradas.
  - O mesmo mecanismo destravou um efeito colateral: como o bloco da
    questão só era fechado quando a próxima questão numerada aparecia,
    textos de apoio longos entre uma questão e a próxima acabavam grudados
    na última alternativa da questão anterior. Agora o bloco é fechado
    assim que uma fronteira reconhecida aparece (cabeçalho de seção ou
    frase de instrução de texto de apoio), não só quando a próxima questão
    é encontrada.
  - Quando o bloco de Conhecimentos Específicos não tem cabeçalhos de
    disciplina próprios dentro dele (banca não subdivide por matéria — caso
    comum), as questões específicas herdavam a última disciplina vista
    ainda em Conhecimentos Gerais (nessa prova, as 30 questões específicas,
    41 a 70, saíam todas com a disciplina "Legislação Acerca de Segurança
    da Informação e Proteção de Dados", puxada indevidamente do bloco de
    Conhecimentos Gerais). Agora a disciplina é resetada para `null` toda
    vez que uma fronteira CONHECIMENTOS GERAIS/ESPECÍFICOS é detectada.
  - Bug de formatação: o título das disciplinas detectadas capitalizava
    preposições e conectivos ("de", "da", "e"...), ex.: "Legislação Acerca
    De Segurança Da Informação E Proteção De Dados" em vez de "...de
    Segurança da Informação e Proteção de Dados". Corrigido.
- **[Corrigido]** Quando a última alternativa de uma questão caía bem no
  fim de uma página do PDF, o rodapé/marca d'água do site que hospeda a
  prova (ex.: `pcimarkpci ...`, `www.pciconcursos.com.br`, nome da
  instituição repetido) entrava no meio do texto da alternativa. O parser
  agora reconhece e descarta esse tipo de ruído: linhas de marca d'água e
  URL isoladas por padrão fixo, linha de rodapé terminando em "PÁGINA N",
  e — de forma genérica, sem depender do nome de nenhuma banca — qualquer
  linha em CAIXA ALTA que se repete identica 3+ vezes no documento (sinal
  de cabeçalho/rodapé reimpresso em toda página).
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
- Precisamos colocar um botão para exportar o banco concursos.db (baixar o arquivo .sql).
