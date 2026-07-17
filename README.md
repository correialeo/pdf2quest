# Concursos

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3-6DB33F)
![License](https://img.shields.io/badge/license-PolyForm%20Noncommercial%201.0.0-blue)

Ferramenta web local (single-user, sem login, sem nuvem) para importar
provas de concurso em PDF, resolver questões, montar simulados com nota
calculada pelo edital e acompanhar a evolução nos estudos ao longo do
tempo.

Feita inicialmente com foco no edital da Dataprev, mas serve pra qualquer
concurso cujas provas venham em PDF — a leitura de cada PDF é feita por um
parser específico da banca organizadora, então dá pra estender pra novas
bancas sem tocar no resto do sistema. Veja a arquitetura de parsers e como
adicionar uma banca nova em [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Stack

| Camada       | Tecnologia                          |
| ------------ | ------------------------------------ |
| Back-end     | Java 21 + Spring Boot 3 (MVC)        |
| Views        | Thymeleaf + Bootstrap 5              |
| Banco        | SQLite (arquivo único, sem servidor) |
| Extração PDF | Apache PDFBox                        |
| Build        | Maven                                |

## Rodando localmente

Pré-requisito: Java 21 (`java -version`; ajuste `JAVA_HOME` se necessário).

```bash
mvn spring-boot:run
```

Acesse **http://localhost:8080** — abre direto no dashboard.

## Funcionalidades

1. **Importar prova (PDF)** — sobe o PDF de uma prova; banca, ano,
   disciplina e categoria (Gerais/Específicas) são detectados
   automaticamente a partir do próprio documento.
2. **Importar gabarito separado (PDF)** — vincula um PDF de gabarito
   publicado à parte a uma prova já importada.
3. **Banco de questões / prática livre** — filtra por disciplina, assunto,
   ano e banca para resolver questões avulsas.
4. **Simulado** — monta um simulado por quantidade de questões de
   Gerais/Específicas e calcula a nota conforme o peso do edital.
5. **Caderno de erros** — lista questões erradas na última tentativa e
   permite refazê-las de uma vez.
6. **Dashboard** — totais, desempenho por disciplina/assunto, histórico de
   simulados e exportação do banco (`.sql`) para backup.

Descrição completa de cada funcionalidade e da arquitetura de parsers por
banca em [`sobre.md`](sobre.md).

## Arquitetura

A importação de PDF segue um padrão de plugin por banca organizadora
(`ExamParser` / `GabaritoTableParser`, descobertos automaticamente pelo
Spring e indexados por `Banca`), pensado para escalar sem acoplar bancas
novas ao resto do sistema. Hoje só a **FGV** tem parser implementado; as
demais (Cesgranrio, Cebraspe, FCC, Vunesp) já existem no enum `Banca` e
aparecem nas telas de importação como placeholders para o roadmap.

Detalhes da estrutura de pacotes e o passo a passo para adicionar uma
banca nova estão em [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Limitações conhecidas

- Parsing de PDF é heurístico (regex); provas com formatação incomum podem
  gerar falhas de importação — registradas no relatório final, sem travar
  o processo.
- Não há tela de edição para corrigir disciplina/categoria/assunto
  detectados incorretamente; hoje o ajuste é direto no banco SQLite
  (`concursos.db`).
- Sem autenticação/multiusuário de propósito — é uma ferramenta pessoal,
  para rodar local.

## Licença

Distribuído sob a [PolyForm Noncommercial License 1.0.0](LICENSE) — uso,
modificação e distribuição livres para fins não comerciais (pessoal,
educacional, pesquisa). Uso comercial não é permitido por estes termos.
