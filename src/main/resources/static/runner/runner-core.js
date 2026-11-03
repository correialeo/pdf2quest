/*
 * Nucleo de execucao dos blocos de codigo das aulas (Python/pandas, PySpark simulado e SQL).
 * O MESMO arquivo roda no Web Worker do navegador e no runner Node do servidor (validacao na criacao da aula),
 * com o mesmo Pyodide e o mesmo sql.js: o que passou na criacao da a mesma saida na tela.
 */
(function (root) {
    'use strict';

    /** CSV simples (aspas duplas opcionais) -> {columns, rows}. */
    function parseCsv(text) {
        const lines = String(text).replace(/\r/g, '').split('\n').filter(l => l.trim() !== '');
        const split = function (line) {
            const out = [];
            let cur = '', quoted = false;
            for (let i = 0; i < line.length; i++) {
                const ch = line[i];
                if (quoted) {
                    if (ch === '"' && line[i + 1] === '"') { cur += '"'; i++; }
                    else if (ch === '"') { quoted = false; }
                    else { cur += ch; }
                } else if (ch === '"') { quoted = true; }
                else if (ch === ',') { out.push(cur.trim()); cur = ''; }
                else { cur += ch; }
            }
            out.push(cur.trim());
            return out;
        };
        const columns = lines.length ? split(lines[0]) : [];
        return {columns: columns, rows: lines.slice(1).map(split)};
    }

    const NUMBER = /^-?\d+(\.\d+)?$/;

    /** Tabelas da aula (:::dados) carregadas no SQLite: coluna so com numeros vira INTEGER/REAL. */
    function loadTables(db, datasets) {
        Object.keys(datasets || {}).forEach(function (name) {
            const t = parseCsv(datasets[name]);
            const types = t.columns.map(function (c, i) {
                const vals = t.rows.map(r => r[i]).filter(v => v !== '' && v !== undefined);
                if (vals.length && vals.every(v => /^-?\d+$/.test(v))) return 'INTEGER';
                if (vals.length && vals.every(v => NUMBER.test(v))) return 'REAL';
                return 'TEXT';
            });
            const q = s => '"' + String(s).replace(/"/g, '""') + '"';
            db.run('CREATE TABLE ' + q(name) + ' (' + t.columns.map((c, i) => q(c) + ' ' + types[i]).join(', ') + ')');
            const stmt = db.prepare('INSERT INTO ' + q(name) + ' VALUES (' + t.columns.map(() => '?').join(', ') + ')');
            t.rows.forEach(function (r) {
                stmt.run(t.columns.map(function (c, i) {
                    const v = r[i];
                    if (v === '' || v === undefined) return null;
                    return types[i] === 'TEXT' ? v : Number(v);
                }));
            });
            stmt.free();
        });
    }

    function tableText(res) {
        if (!res) return '';
        return [res.columns.join(' | ')].concat(res.values.map(r => r.map(v => v === null ? 'NULL' : String(v)).join(' | ')))
            .join('\n');
    }

    /** Saida comparavel: sem linhas vazias/separadores, espacos colapsados, "|" sem espacos, 120.0 == 120. */
    function normalize(text) {
        return String(text || '').replace(/\r/g, '').split('\n')
            .map(l => l.trim())
            .filter(l => l !== '' && !/^[|+\-\s:]+$/.test(l))
            .map(function (l) {
                return l.split('|').map(function (cell) {
                    const c = cell.trim().replace(/\s+/g, ' ');
                    return NUMBER.test(c) ? String(Number(Number(c).toFixed(6))) : c;
                }).join('|');
            }).join('\n');
    }

    function sameOutput(actual, expected) {
        return normalize(actual) === normalize(expected);
    }

    /**
     * @param env.loadPyodide  funcao do Pyodide
     * @param env.initSqlJs    funcao do sql.js
     * @param env.files        {harness: texto de harness.py, pyspark: texto de pyspark_sim.py}
     * @param env.pyodideOptions opcoes do loadPyodide (indexURL)
     * @param env.sqlOptions   opcoes do initSqlJs (locateFile)
     */
    function createRunner(env) {
        let pyPromise = null;
        let sqlPromise = null;

        function python() {
            if (!pyPromise) {
                pyPromise = (async function () {
                    const py = await env.loadPyodide(Object.assign({stdout: () => {}, stderr: () => {}}, env.pyodideOptions || {}));
                    await py.loadPackage('pandas', {messageCallback: () => {}, errorCallback: () => {}});
                    py.FS.mkdirTree('/home/pyodide');
                    py.FS.writeFile('/home/pyodide/harness.py', env.files.harness);
                    py.FS.writeFile('/home/pyodide/pyspark_sim.py', env.files.pyspark);
                    py.runPython("import sys\nsys.path.insert(0, '/home/pyodide')\nimport harness, pandas\n"
                        + "pandas.set_option('display.width', 200)\npandas.set_option('display.max_columns', 50)");
                    return py;
                })();
            }
            return pyPromise;
        }

        function sql() {
            if (!sqlPromise) sqlPromise = env.initSqlJs(env.sqlOptions || {});
            return sqlPromise;
        }

        /** Carrega o motor da linguagem (para o worker liberar a rede so ate aqui). */
        async function prepare(lang) {
            if (lang === 'sql') await sql(); else await python();
        }

        /**
         * req: {lang: python|pandas|pyspark|sql, code, datasets: {nome: csv}, test (python: asserts), expected}
         * -> {ok, output, error, table, testPassed, matches, spark}
         */
        async function run(req) {
            let result;
            if (req.lang === 'sql') {
                const SQL = await sql();
                const db = new SQL.Database();
                try {
                    loadTables(db, req.datasets);
                    const sets = db.exec(req.code);
                    const last = sets.length ? sets[sets.length - 1] : null;
                    result = {ok: true, output: tableText(last), table: last, error: null};
                } catch (e) {
                    result = {ok: false, output: '', table: null, error: 'Erro SQL: ' + (e && e.message ? e.message : e)};
                } finally {
                    db.close();
                }
            } else {
                const py = await python();
                const harness = py.pyimport('harness');
                try {
                    result = JSON.parse(harness.run(JSON.stringify({
                        lang: req.lang === 'pyspark' ? 'pyspark' : 'python', code: req.code,
                        datasets: req.datasets || {}, test: req.test || null
                    })));
                } finally {
                    harness.destroy();
                }
            }
            if (req.expected != null && req.expected !== '') {
                result.matches = result.ok && sameOutput(result.output, req.expected);
            }
            return result;
        }

        return {prepare: prepare, run: run};
    }

    const api = {createRunner: createRunner, normalize: normalize, sameOutput: sameOutput, parseCsv: parseCsv};
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    else root.P2QRunner = api;
})(typeof self !== 'undefined' ? self : this);
