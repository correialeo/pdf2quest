#!/usr/bin/env bash
# Baixa, uma vez, o que as aulas interativas usam, para rodar tudo localmente (sem CDN na hora da aula):
#  - KaTeX (formulas), sql.js (SQLite em WebAssembly) e Pyodide (Python + pandas em WebAssembly).
# Fica em data/runtime (fora do git). O navegador carrega daqui (/vendor/...) e o servidor usa o mesmo
# Pyodide/sql.js, via Node, para conferir na criacao da aula que o exemplo roda e da a saida declarada.
# Pode rodar de novo: o que ja estiver baixado e pulado.
set -euo pipefail
cd "$(dirname "$0")"
DIR=data/runtime
mkdir -p "$DIR"
command -v node >/dev/null 2>&1 || { echo "Instale o Node.js 20+ (brew install node) e rode de novo." >&2; exit 1; }

PYODIDE=0.27.7
SQLJS=1.13.0
KATEX=0.16.22

if [ ! -f "$DIR/node_modules/pyodide/package.json" ] || ! grep -q "\"version\": \"$PYODIDE\"" "$DIR/node_modules/pyodide/package.json"; then
    echo "==> Baixando pyodide@$PYODIDE, sql.js@$SQLJS e katex@$KATEX"
    (cd "$DIR" && [ -f package.json ] || echo '{"private":true}' > package.json)
    (cd "$DIR" && npm install --no-audit --no-fund --save-exact "pyodide@$PYODIDE" "sql.js@$SQLJS" "katex@$KATEX")
fi

# Pacotes Python (pandas e dependencias): baixados uma vez para a pasta do Pyodide, de onde o navegador os carrega.
echo "==> Conferindo os pacotes Python do Pyodide (pandas)"
(cd "$DIR" && node -e '
const { loadPyodide } = require("pyodide");
(async () => {
  const py = await loadPyodide();
  await py.loadPackage(["pandas"]);
  console.log("pandas", py.runPython("import pandas; pandas.__version__"));
})().catch(e => { console.error(e); process.exit(1); });
')
echo "ok: runtimes em $DIR"
