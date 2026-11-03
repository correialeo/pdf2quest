// Runner do servidor: valida, na criacao da aula, que os exemplos rodam e dao a saida declarada.
// Uso: node runner-node.js <pasta-do-runtime> < pedidos.json  (lista de pedidos; responde a lista de resultados).
// Usa o mesmo runner-core.js, Pyodide e sql.js do navegador.
'use strict';
const fs = require('fs');
const path = require('path');
const runtime = path.resolve(process.argv[2]);
const here = __dirname;
const req = name => require(path.join(runtime, 'node_modules', name));
const core = require(path.join(here, 'runner-core.js'));

(async function () {
    const input = JSON.parse(fs.readFileSync(0, 'utf8'));
    const runner = core.createRunner({
        loadPyodide: req('pyodide').loadPyodide,
        initSqlJs: req('sql.js'),
        files: {
            harness: fs.readFileSync(path.join(here, 'harness.py'), 'utf8'),
            pyspark: fs.readFileSync(path.join(here, 'pyspark_sim.py'), 'utf8')
        }
    });
    const out = [];
    for (const r of input) {
        try {
            out.push(await runner.run(r));
        } catch (e) {
            out.push({ok: false, output: '', error: 'Falha do runner: ' + (e && e.message ? e.message : e)});
        }
    }
    process.stdout.write(JSON.stringify(out));
})().catch(e => { process.stderr.write(String(e && e.stack || e)); process.exit(2); });
