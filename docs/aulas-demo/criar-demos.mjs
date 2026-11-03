// Cria as duas aulas de demonstracao (SQL/pandas/PySpark e Matematica Financeira) num pdf2questao rodando, pelo
// servidor MCP, do mesmo jeito que o agente faz: grava os trechos literais (registrar_trecho) e chama criar_aula.
// Uso: node docs/aulas-demo/criar-demos.mjs [http://localhost:8080]
import fs from 'node:fs';
import path from 'node:path';

const base = (process.argv[2] || 'http://localhost:8080').replace(/\/$/, '');
const dir = path.dirname(new URL(import.meta.url).pathname);
let session = null, seq = 0;

async function rpc(method, params, notify = false) {
    const res = await fetch(base + '/mcp', {
        method: 'POST',
        headers: Object.assign({'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream'},
            session ? {'Mcp-Session-Id': session} : {}),
        body: JSON.stringify(Object.assign({jsonrpc: '2.0', method, params}, notify ? {} : {id: ++seq}))
    });
    session = res.headers.get('mcp-session-id') || session;
    if (notify) return null;
    const text = await res.text();
    const json = text.trim().startsWith('{') ? JSON.parse(text)
        : JSON.parse(text.split('\n').filter(l => l.startsWith('data:')).map(l => l.slice(5)).pop());
    if (json.error) throw new Error(method + ': ' + JSON.stringify(json.error));
    return json.result;
}

async function call(name, args) {
    const r = await rpc('tools/call', {name, arguments: args});
    const text = (r.content || []).map(c => c.text).join('\n');
    if (r.isError) throw new Error(name + ': ' + text);
    return r.structuredContent || JSON.parse(text);
}

await rpc('initialize', {protocolVersion: '2025-03-26', capabilities: {}, clientInfo: {name: 'criar-demos', version: '1'}});
await rpc('notifications/initialized', {}, true);
const trechos = JSON.parse(fs.readFileSync(path.join(dir, 'trechos.json'), 'utf8'));
for (const name of ['sql-pandas', 'matematica-financeira']) {
    const p = JSON.parse(fs.readFileSync(path.join(dir, name + '.json'), 'utf8'));
    const ids = [];
    for (const t of trechos[name]) ids.push((await call('registrar_trecho', t)).trechoId);
    const aula = await call('criar_aula', {
        titulo: p.titulo, disciplina: p.disciplina, topicoEdital: p.topicoEdital, concurso: p.concurso,
        conteudo: fs.readFileSync(path.join(dir, p.conteudoArquivo), 'utf8'), fontes: p.fontes,
        perguntasDaAula: p.perguntasDaAula, estiloDaBanca: p.estiloDaBanca, referencias: p.referencias, trechos: ids
    });
    console.log(name + ': aula ' + aula.aulaId + ' (' + aula.slides + ' slides, ' + aula.questoesDeFixacao
        + ' itens de fixação) ' + aula.link);
}
