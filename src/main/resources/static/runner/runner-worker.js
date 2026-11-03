/*
 * Web Worker que roda o codigo do aluno, isolado da pagina. Carrega so os motores que a aula usa (Pyodide e/ou
 * sql.js, da pasta local /vendor) e, depois disso, desliga a rede: fetch, XMLHttpRequest, WebSocket e importScripts
 * deixam de existir para o codigo do aluno (alem da CSP do worker, que so permite o proprio servidor). O tempo-limite
 * fica na pagina: se estourar, ela encerra este worker e cria outro.
 */
'use strict';
importScripts('/runner/runner-core.js');

let runner = null;
let ready = null;

function lockNetwork() {
    const blocked = function () { throw new Error('Sem acesso à rede no código da aula.'); };
    self.fetch = function () { return Promise.reject(new Error('Sem acesso à rede no código da aula.')); };
    self.XMLHttpRequest = blocked;
    self.WebSocket = blocked;
    self.EventSource = blocked;
    self.importScripts = blocked;
}

function init(langs) {
    if (ready) return ready;
    ready = (async function () {
        const needPython = langs.some(l => l !== 'sql');
        const needSql = langs.includes('sql');
        if (needPython) importScripts('/vendor/pyodide/pyodide.js');
        if (needSql) importScripts('/vendor/sqljs/sql-wasm.js');
        const text = url => fetch(url).then(r => { if (!r.ok) throw new Error(url + ': ' + r.status); return r.text(); });
        const files = needPython ? {harness: await text('/runner/harness.py'), pyspark: await text('/runner/pyspark_sim.py')} : {};
        runner = P2QRunner.createRunner({
            loadPyodide: self.loadPyodide, initSqlJs: self.initSqlJs, files: files,
            pyodideOptions: {indexURL: '/vendor/pyodide/'},
            sqlOptions: {locateFile: f => '/vendor/sqljs/' + f}
        });
        if (needSql) await runner.prepare('sql');
        if (needPython) await runner.prepare('python');
        lockNetwork();
    })();
    return ready;
}

self.onmessage = async function (e) {
    const msg = e.data || {};
    try {
        if (msg.type === 'init') {
            await init(msg.langs || []);
            self.postMessage({type: 'ready'});
            return;
        }
        if (msg.type === 'run') {
            await init(msg.langs || [msg.req.lang]);
            self.postMessage({type: 'running', id: msg.id});
            const result = await runner.run(msg.req);
            self.postMessage({type: 'result', id: msg.id, result: result});
        }
    } catch (err) {
        self.postMessage({type: msg.type === 'init' ? 'init-error' : 'result', id: msg.id, result: {
            ok: false, output: '', error: 'Não foi possível preparar o ambiente: ' + (err && err.message ? err.message : err)
                + ' (rode ./instalar-runtimes.sh na pasta do pdf2questao)'
        }});
    }
};
