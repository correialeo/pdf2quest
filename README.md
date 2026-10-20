# PDF2Questao

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3-6DB33F)
![License](https://img.shields.io/badge/license-PolyForm%20Noncommercial%201.0.0-blue)

Transforma o PDF de uma prova de concurso em questões para resolver no computador: você importa a prova,
resolve as questões, vê a nota calculada pelo edital e acompanha onde está errando.

Tudo roda **no seu computador**: não precisa de conta, de internet (depois de instalado) nem de servidor.
As suas provas e respostas ficam num arquivo só, dentro da pasta do projeto.

## Como instalar e usar (passo a passo)

Funciona no **macOS** e no **Linux** (Ubuntu, Debian, Fedora). No Windows, veja a seção
[Windows](#windows) abaixo.

### 1. Baixe o projeto

Escolha uma das opções:

- **Sem saber Git:** na página do projeto no GitHub, clique no botão verde **Code** → **Download ZIP**.
  Descompacte o arquivo onde quiser (por exemplo, na pasta Documentos).
- **Com Git:** `git clone https://github.com/correialeo/pdf2quest.git`

### 2. Abra o Terminal na pasta do projeto

- **Mac:** abra o app **Terminal**, digite `cd ` (com um espaço no fim), arraste a pasta do projeto para
  dentro da janela e aperte Enter.
- **Linux:** clique com o botão direito dentro da pasta e escolha **Abrir no terminal**.

### 3. Instale o que é necessário (só na primeira vez)

```bash
./instalar.sh
```

O script instala o **Java 21**, o **Maven** (que compila e roda o projeto) e o **Tesseract** (que lê provas
escaneadas, em imagem). Ele vai pedir a senha do computador. No Mac, se ainda não tiver o
[Homebrew](https://brew.sh), ele instala também. Pode levar alguns minutos.

### 4. Abra o sistema

```bash
./iniciar.sh
```

Na primeira vez demora alguns minutos (o Maven baixa o que precisa); nas próximas, uns segundos. Quando
estiver pronto, o navegador abre sozinho em **http://localhost:8080**.

Para **fechar**, volte ao Terminal e aperte `Ctrl + C`. Para usar de novo outro dia, repita só o passo 4.

> **Deu "permissão negada"?** Rode uma vez `chmod +x instalar.sh iniciar.sh` e tente de novo.
>
> **A porta 8080 já está em uso?** Rode `PORTA=8090 ./iniciar.sh` e acesse http://localhost:8090.

### Windows

Os scripts não rodam direto no Windows. Duas opções:

- **Recomendado: WSL.** Instale o [WSL com Ubuntu](https://learn.microsoft.com/pt-br/windows/wsl/install)
  (um Linux dentro do Windows), abra o Ubuntu e siga os passos acima normalmente.
- **Manual:** instale o [Java 21 (Temurin)](https://adoptium.net/), o [Maven](https://maven.apache.org/download.cgi)
  e, se for importar provas escaneadas, o [Tesseract](https://github.com/UB-Mannheim/tesseract/wiki) com o
  idioma português. Depois, no Prompt de Comando, dentro da pasta do projeto: `mvn spring-boot:run`.

## Primeiros passos no sistema

1. **Importe uma prova:** menu **Provas → Importar PDF**. Escolha o arquivo e a banca. Provas escaneadas
   (só imagem, comum na FCC) passam por leitura automática (OCR) e levam alguns minutos; a tela mostra o
   progresso página a página e você pode continuar usando o sistema enquanto isso.
2. **Importe o gabarito**, se ele vier num PDF separado: **Provas → Importar gabarito**.
   Sem PDF? Em **Provas → Importar planilha/JSON** você traz questões de uma planilha (Excel, LibreOffice,
   Google Planilhas salvos como CSV) ou de um arquivo JSON. Antes de gravar, o sistema mostra uma prévia com
   as questões com erro e as que parecem repetidas. O gabarito também pode vir de uma planilha ou ser colado
   como texto (**Importar gabarito → Gabarito em planilha/texto**).
3. **Estude:** em **Estudar → Blocos de estudo**, crie um bloco com a prova inteira ou só as faixas de
   questões que quiser (ex.: `1-30`), ou use **Sessão rápida** para sortear questões.
4. **Resolva:** clique na alternativa ou use o teclado (`A`–`E` responde, `X` marca chute, `←` `→` navega,
   `M` abre o mapa de questões). Pode sair no meio: a sessão fica salva e aparece em **Início → Continuar**.
5. **Revise:** ao finalizar, veja a nota, o desempenho por disciplina e as questões erradas. Depois, o
   **Caderno de erros** e a tela de **Chutes** juntam o que você precisa estudar.

## O que o sistema faz

- **Importar provas em PDF** das bancas **FGV, FCC, Cebraspe e Cesgranrio**, detectando ano, disciplina,
  textos base e imagens. Provas escaneadas são lidas por OCR.
- **Importar gabarito** de um PDF separado e ligar as respostas às questões.
- **Importar questões sem PDF**, por planilha CSV (qualquer layout: você escolhe as colunas) ou JSON
  ([formato documentado](docs/importacao/formato-json.md)), com prévia, validação e detecção de duplicatas.
- **Estudos:** páginas e subpáginas de resumo com editor visual (títulos, listas, checklists, tabelas, links,
  código) e salvamento automático, cronograma de tarefas por dia com tempo estimado e dedicado, e uma caixa
  de entrada para anotações rápidas.
- **Blocos de estudo:** recortes reutilizáveis de uma ou várias provas, com tempo limite opcional.
- **Perfis de pontuação:** peso por disciplina e nota mínima por grupo, como no edital.
- **Resolver no modo foco**, com riscar alternativas, grifar o texto base e marcar "é chute?".
- **Resultado** com nota, desempenho por disciplina e revisão filtrável (erradas, chutes, em branco).
- **Caderno de erros** e **diagnóstico de chutes** (o que você acertou sem saber).
- **Exportar o banco** (`.sql`) para backup, no menu lateral.

Descrição detalhada em [`sobre.md`](sobre.md).

## Estudar junto com o Claude (MCP)

Com o sistema aberto, o [Claude Code](https://claude.com/claude-code) consegue usar os seus dados direto da conversa,
pelo servidor MCP em `http://localhost:8080/mcp`. Na pasta do projeto, o arquivo `.mcp.json` já configura isso: ao
abrir o Claude Code aqui, aprove o servidor `pdf2questao`. Para usar de qualquer pasta:

```bash
claude mcp add --transport http --scope user pdf2questao http://localhost:8080/mcp
```

(Se rodar em outra porta, troque o `8080` no endereço.)

| Ferramenta                  | O que faz                                                                  |
| --------------------------- | -------------------------------------------------------------------------- |
| `desempenho_por_disciplina` | Acertos por disciplina, piores primeiro                                    |
| `ultimo_simulado`           | Nota projetada do último simulado com perfil do edital e os mínimos        |
| `listar_erros`              | Questões do caderno de erros (até 20), com a sua resposta e a certa        |
| `registrar_treino`          | Grava as questões resolvidas no chat, com as suas respostas, como sessão   |
| `tarefas_de_hoje`           | Tarefas do cronograma de hoje e atrasadas                                  |
| `concluir_tarefa`           | Conclui uma tarefa, com os minutos dedicados                               |
| `revisoes_de_hoje`          | Assuntos da revisão espaçada que vencem hoje                               |
| `marcar_revisao`            | Lembrei / esqueci de um assunto da revisão                                 |
| `anexar_ao_resumo_do_dia`   | Acrescenta um texto (Markdown simples) ao resumo do dia                    |

Exemplos: *"me treina nos meus erros de Economia e registra o treino no fim"*, *"faz a revisão de hoje comigo"*,
*"resume o que treinamos no resumo do dia"*. Nenhuma ferramenta apaga dados.

## Onde ficam os meus dados?

Tudo fica dentro da pasta do projeto:

| O quê                         | Onde                |
| ----------------------------- | ------------------- |
| Questões, sessões e respostas | `pdf2questao.db`    |
| PDFs das provas importadas    | `data/pdfs/`        |
| Cache da leitura por OCR      | `data/ocr-cache/`   |

**Para fazer backup**, copie o `pdf2questao.db` e a pasta `data/` (ou use **Exportar banco** no menu).
Se for baixar uma versão nova do projeto, leve esses arquivos para a pasta nova.

## Problemas comuns

- **"Falta o Java 21 ou o Maven"** ao rodar `./iniciar.sh`: rode `./instalar.sh` antes.
- **A importação de uma prova escaneada falha falando do Tesseract:** rode `./instalar.sh` de novo; ele
  confere se o Tesseract e o idioma português estão instalados.
- **Algumas questões vieram erradas ou faltando:** a leitura de PDF é automática e pode errar em provas
  com formatação incomum. Veja o **relatório** na tela da prova importada. Depois de uma atualização do
  projeto, o botão **Reprocessar** lê o PDF de novo sem perder as suas respostas.

## Para desenvolvedores

| Camada       | Tecnologia                              |
| ------------ | --------------------------------------- |
| Back-end     | Java 21 + Spring Boot 3 (MVC)           |
| Views        | Thymeleaf + Bootstrap 5                 |
| Banco        | SQLite (arquivo único) + Flyway         |
| Extração PDF | Apache PDFBox + Tesseract (OCR)         |
| Build        | Maven                                   |

```bash
mvn spring-boot:run   # sobe em http://localhost:8080
mvn test              # roda os testes
```

O schema do banco é versionado pelo Flyway (`src/main/resources/db/migration`). Instalações existentes são
atualizadas sozinhas ao iniciar: as migrações só acrescentam colunas e tabelas (a V6 adiciona a importação por
arquivo e a área de Estudos) e nunca apagam dados. Rollbacks manuais ficam em `db/rollback`.

A importação segue um padrão de plugin por banca (`ExamParser` / `GabaritoTableParser`, descobertos pelo
Spring e indexados por `Banca`): adicionar uma banca nova não mexe no resto do sistema. Estrutura de pacotes
e passo a passo em [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Licença

Distribuído sob a [PolyForm Noncommercial License 1.0.0](LICENSE) — uso,
modificação e distribuição livres para fins não comerciais (pessoal,
educacional, pesquisa). Uso comercial não é permitido por estes termos.
