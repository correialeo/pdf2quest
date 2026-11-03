/*
 * Aula interativa: formulas (KaTeX), exercicio guiado parametrizado (```calculo) e codigo executavel (```... run e
 * ```tarefa). O codigo roda num Web Worker (/runner/runner-worker.js), com tempo-limite e sem rede; o que o aluno
 * edita fica so no localStorage deste navegador.
 */
(function () {
    'use strict';
    const deck = document.querySelector('.lesson-deck');
    if (!deck) return;
    const lessonId = deck.dataset.lesson;
    const datasets = JSON.parse(deck.dataset.datasets || '{}');
    const lastTry = JSON.parse(deck.dataset.exercises || '{}');
    const Expr = window.P2QCalcExpr;

    // ------------------------------------------------------------------ util

    const el = function (tag, cls, text) {
        const e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text != null) e.textContent = text;
        return e;
    };
    const button = function (label, cls, icon) {
        const b = el('button', 'btn btn-sm ' + (cls || 'btn-outline-secondary'));
        b.type = 'button';
        b.innerHTML = (icon ? '<i class="bi ' + icon + ' me-1"></i>' : '');
        b.appendChild(document.createTextNode(label));
        return b;
    };
    const fmt = function (v, digits) {
        if (!isFinite(v)) return String(v);
        const max = digits != null ? digits : (Math.abs(v) >= 1 ? 2 : 6);
        return v.toLocaleString('pt-BR', {maximumFractionDigits: max});
    };
    /** "1.234,56", "1234,56", "1234.56", "R$ 1.000", "12%" -> numero. */
    const parseNumber = function (raw) {
        let s = String(raw || '').replace(/R\$|%|\s/g, '');
        if (s === '') return NaN;
        if (s.includes(',')) s = s.replace(/\./g, '').replace(',', '.');
        else if (/^-?[1-9]\d{0,2}(\.\d{3})+$/.test(s)) s = s.replace(/\./g, '');   // 12.000 = doze mil
        return Number(s);
    };
    const post = function (block, correct, hints) {
        return fetch('/aulas/' + lessonId + '/exercicio', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({block: Number(block), correct: correct, hints: hints})
        }).then(r => { if (!r.ok) throw new Error(r.status); return r.json(); })
            .catch(() => window.p2qToast && window.p2qToast('Não foi possível salvar o resultado. Tente de novo.', 'danger'));
    };
    const lastTryNote = function (container, block) {
        const t = lastTry[block];
        if (!t) return;
        const note = el('div', 'small text-muted mt-1');
        note.textContent = 'Última tentativa: ' + (t.acertou ? 'concluída sem ver a resolução' : 'não concluída sozinho')
            + (t.dicas ? ' · ' + t.dicas + (t.dicas === 1 ? ' dica' : ' dicas') : '');
        container.appendChild(note);
    };

    // ------------------------------------------------------------------ formulas

    function renderTex() {
        if (!window.katex) return;   // sem o KaTeX baixado, fica o TeX em texto
        document.querySelectorAll('.katex-display-src, .katex-inline-src').forEach(function (node) {
            try {
                window.katex.render(node.dataset.tex, node, {
                    displayMode: node.classList.contains('katex-display-src'), throwOnError: false
                });
            } catch (e) { /* mantem o texto */ }
        });
    }

    // ------------------------------------------------------------------ exercicio guiado

    function tolerance(step, value) {
        return step.tolerancia != null ? step.tolerancia : Math.max(0.01, Math.abs(value) * 0.002);
    }

    /** Mesmo sorteio de LessonBlocks.sample: min + k*passo, sem repetir o exemplo, satisfazendo as restricoes. */
    function sample(spec) {
        for (let t = 0; t < 500; t++) {
            const vars = {};
            let ok = true;
            Object.keys(spec.variaveis).forEach(function (name) {
                const r = spec.variaveis[name];
                const steps = Math.floor((r.max - r.min) / r.passo + 1e-9);
                const v = Expr.round(r.min + Math.floor(Math.random() * (steps + 1)) * r.passo, 10);
                const ex = spec.exemplo ? spec.exemplo[name] : undefined;
                if (ex != null && Math.abs(ex - v) <= 1e-9) ok = false;
                vars[name] = v;
            });
            if (!ok) continue;
            if ((spec.restricoes || []).every(rule => Expr.evaluate(rule, vars) !== 0)) return vars;
        }
        return null;
    }

    function fill(template, vars) {
        return String(template || '').replace(/\{([A-Za-z_][A-Za-z0-9_]*)}/g, function (m, name) {
            return Object.prototype.hasOwnProperty.call(vars, name) ? fmt(vars[name], name === 'resposta' ? 2 : 4) : m;
        });
    }

    function setupCalc(box) {
        let spec;
        try {
            spec = JSON.parse(box.dataset.spec);
        } catch (e) {
            box.textContent = 'Exercício guiado com JSON inválido.';
            return;
        }
        const block = box.dataset.block;

        function start() {
            box.innerHTML = '';
            const vars = sample(spec);
            if (!vars) { box.textContent = 'Não foi possível sortear valores para este exercício.'; return; }
            const env = Object.assign({}, vars);
            const answers = [];
            spec.etapas.forEach(function (s, i) {
                answers.push(Expr.evaluate(s.resposta, env));
                env['e' + (i + 1)] = answers[i];
            });
            let current = 0, hints = 0, revealed = false, done = false;

            const head = el('div', 'calc-head');
            head.appendChild(el('div', 'lesson-box-title', 'Exercício guiado' + (spec.titulo ? ': ' + spec.titulo : '')));
            head.appendChild(el('div', 'small text-muted', 'Números novos a cada tentativa (diferentes do exemplo do slide).'));
            box.appendChild(head);
            box.appendChild(el('p', 'calc-statement', fill(spec.enunciado, vars)));

            const rows = spec.etapas.map(function (s, i) {
                const row = el('div', 'calc-step');
                row.appendChild(el('div', 'calc-step-title', (i + 1) + ') ' + (s.titulo || 'Etapa ' + (i + 1))));
                row.appendChild(el('div', 'calc-step-question', fill(s.pergunta, vars)));
                const line = el('div', 'd-flex flex-wrap gap-2 align-items-center');
                const input = el('input', 'form-control form-control-sm calc-input');
                input.inputMode = 'decimal';
                input.autocomplete = 'off';
                input.setAttribute('aria-label', 'Resposta da etapa ' + (i + 1));
                const check = button('Conferir', 'btn-primary');
                line.appendChild(input);
                if (s.unidade) line.appendChild(el('span', 'small text-muted', s.unidade));
                line.appendChild(check);
                row.appendChild(line);
                const feedback = el('div', 'calc-feedback small');
                row.appendChild(feedback);
                box.appendChild(row);
                const r = {row: row, input: input, check: check, feedback: feedback};
                check.addEventListener('click', () => verify(i));
                input.addEventListener('keydown', e => { if (e.key === 'Enter') verify(i); });
                return r;
            });

            const controls = el('div', 'calc-controls d-flex flex-wrap gap-2 mt-2');
            const hintBtn = button('Dica', 'btn-outline-secondary', 'bi-lightbulb');
            const nextBtn = button('Mostrar passo seguinte', 'btn-outline-secondary', 'bi-skip-forward');
            const allBtn = button('Mostrar resolução completa', 'btn-outline-secondary', 'bi-eye');
            const newBtn = button('Novo exercício', 'btn-outline-primary', 'bi-shuffle');
            [hintBtn, nextBtn, allBtn, newBtn].forEach(b => controls.appendChild(b));
            box.appendChild(controls);
            const end = el('div', 'calc-end mt-2');
            box.appendChild(end);
            lastTryNote(box, block);

            function refresh() {
                rows.forEach(function (r, i) {
                    const active = i === current && !done;
                    r.input.disabled = !active;
                    r.check.disabled = !active;
                    r.row.classList.toggle('is-current', active);
                });
                hintBtn.disabled = nextBtn.disabled = allBtn.disabled = done;
                if (!done) rows[current].input.focus({preventScroll: true});
            }

            function accept(i, shown) {
                const r = rows[i];
                r.input.value = fmt(answers[i]);
                r.row.classList.add(shown ? 'is-shown' : 'is-ok');
                if (shown) r.feedback.textContent = 'Resposta: ' + fmt(answers[i]) + (spec.etapas[i].unidade ? ' ' + spec.etapas[i].unidade : '');
                else r.feedback.textContent = 'Certo.';
                current = i + 1;
                if (current >= rows.length) finish(); else refresh();
            }

            function verify(i) {
                const s = spec.etapas[i];
                const value = parseNumber(rows[i].input.value);
                const r = rows[i];
                r.row.classList.remove('is-wrong');
                if (isNaN(value)) { r.feedback.textContent = 'Digite um número (ex.: 1.234,56).'; return; }
                if (Math.abs(value - answers[i]) <= tolerance(s, answers[i])) { accept(i, false); return; }
                r.row.classList.add('is-wrong');
                const envHere = Object.assign({}, vars);
                answers.slice(0, i).forEach((a, k) => { envHere['e' + (k + 1)] = a; });
                const typical = (s.errosTipicos || []).find(function (te) {
                    try {
                        const w = Expr.evaluate(te.resposta, envHere);
                        return Math.abs(value - w) <= tolerance(s, w);
                    } catch (e) { return false; }
                });
                r.feedback.textContent = typical ? 'Erro típico: ' + typical.mensagem
                    : 'Não confere. Revise a conta ou peça a dica.';
            }

            function finish() {
                done = true;
                refresh();
                end.innerHTML = '';
                const answer = Expr.evaluate(spec.resposta, env);
                end.appendChild(el('div', revealed ? 'text-guess' : 'text-ok',
                    revealed ? 'Resolução mostrada: esta tentativa conta como não concluída. Tente um novo exercício.'
                        : 'Concluído sozinho' + (hints ? ' (com ' + hints + (hints === 1 ? ' dica)' : ' dicas)') : '!')));
                if (spec.interpretacao) {
                    end.appendChild(el('div', 'calc-interpretation mt-1',
                        fill(spec.interpretacao, Object.assign({resposta: answer}, env))));
                }
                post(block, !revealed, hints);
            }

            hintBtn.addEventListener('click', function () {
                hints++;
                rows[current].feedback.textContent = 'Dica: ' + fill(spec.etapas[current].dica, vars);
            });
            nextBtn.addEventListener('click', function () { hints++; revealed = true; accept(current, true); });
            allBtn.addEventListener('click', function () {
                revealed = true;
                while (!done) accept(current, true);
            });
            newBtn.addEventListener('click', start);
            refresh();
        }
        start();
    }

    // ------------------------------------------------------------------ codigo

    const TIMEOUT = {sql: 10000, python: 30000, pyspark: 30000};
    const langs = Array.from(new Set(Array.from(deck.querySelectorAll('.code-run, .code-task')).map(b => b.dataset.lang)));
    let worker = null, seq = 0;
    const pending = {};

    function getWorker() {
        if (worker) return worker;
        worker = new Worker('/runner/runner-worker.js');
        worker.onmessage = function (e) {
            const m = e.data;
            const p = pending[m.id];
            if (!p) return;
            if (m.type === 'running') {
                p.onRunning();
                p.timer = setTimeout(function () {
                    delete pending[m.id];   // antes de reiniciar, para esta resposta ser a do tempo-limite
                    resetWorker();
                    p.resolve({ok: false, output: '', error: 'Passou de ' + (TIMEOUT[p.lang] / 1000)
                            + ' s rodando (laço infinito?). O ambiente foi reiniciado.'});
                }, TIMEOUT[p.lang] || 30000);
            } else if (m.type === 'result') {
                clearTimeout(p.timer);
                delete pending[m.id];
                p.resolve(m.result);
            }
        };
        worker.postMessage({type: 'init', langs: langs});
        return worker;
    }

    function resetWorker() {
        if (worker) worker.terminate();
        worker = null;
        Object.keys(pending).forEach(function (id) {
            clearTimeout(pending[id].timer);
            pending[id].resolve({ok: false, output: '', error: 'Ambiente reiniciado.'});
            delete pending[id];
        });
    }

    function run(req, onRunning) {
        return new Promise(function (resolve) {
            const id = ++seq;
            pending[id] = {resolve: resolve, lang: req.lang, onRunning: onRunning || function () {}};
            getWorker().postMessage({type: 'run', id: id, req: req, langs: langs});
        });
    }

    function showResult(out, res, lang) {
        out.hidden = false;
        out.innerHTML = '';
        if (res.table) {
            const t = el('table', 'lesson-table');
            const thead = el('thead'), tr = el('tr');
            res.table.columns.forEach(c => tr.appendChild(el('th', null, c)));
            thead.appendChild(tr);
            t.appendChild(thead);
            const tbody = el('tbody');
            res.table.values.forEach(function (row) {
                const r = el('tr');
                row.forEach(v => r.appendChild(el('td', null, v === null ? 'NULL' : String(v))));
                tbody.appendChild(r);
            });
            t.appendChild(tbody);
            out.appendChild(t);
        } else if (res.output) {
            out.appendChild(el('pre', 'code-stdout', res.output));
        } else if (res.ok && !res.error) {
            out.appendChild(el('div', 'small text-muted', lang === 'sql' ? 'Comando executado (sem linhas de resultado).'
                : 'Rodou sem saída. A última linha, se for uma expressão, aparece aqui.'));
        }
        if (res.error) out.appendChild(el('pre', 'code-error', res.error));
        if (res.spark) {
            const s = el('div', 'code-spark small');
            s.appendChild(el('strong', null, 'Simulação (não é Spark real): '));
            s.appendChild(document.createTextNode(res.spark.jobs + (res.spark.jobs === 1 ? ' job, ' : ' jobs, ')
                + res.spark.stages + ' stages no total. '));
            (res.spark.acoes || []).forEach(function (a) {
                s.appendChild(el('span', 'badge text-bg-light me-1', a.acao + '() → job ' + a.job + ': ' + a.stages
                    + (a.stages === 1 ? ' stage' : ' stages') + (a.cache ? ' (do cache)' : '')));
            });
            if (!res.spark.jobs) s.appendChild(document.createTextNode('Só transformações: nada foi executado (lazy).'));
            out.appendChild(s);
        }
    }

    function autosize(ta) {
        ta.style.height = 'auto';
        ta.style.height = Math.max(ta.scrollHeight + 2, 60) + 'px';
    }

    function setupCode(box, isTask) {
        const ta = box.querySelector('.code-src');
        const original = ta.dataset.original;
        const key = 'p2q-code:' + lessonId + ':' + box.dataset.block;
        const saved = localStorage.getItem(key);
        if (saved != null) ta.value = saved;
        autosize(ta);
        ta.addEventListener('input', function () { localStorage.setItem(key, ta.value); autosize(ta); });
        ta.addEventListener('keydown', function (e) {
            if (e.key === 'Tab' && !e.shiftKey) {
                e.preventDefault();
                const s = ta.selectionStart;
                ta.setRangeText('    ', s, ta.selectionEnd, 'end');
                ta.dispatchEvent(new Event('input'));
            } else if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                e.preventDefault();
                runBtn.click();
            }
        });
        const out = box.querySelector('.code-output');
        const bar = box.querySelector('.code-buttons');
        const runBtn = button(isTask ? 'Rodar e conferir' : 'Rodar', 'btn-primary', 'bi-play-fill');
        const resetBtn = button('Reiniciar', 'btn-outline-secondary', 'bi-arrow-clockwise');
        const backBtn = button('Voltar ao original', 'btn-outline-secondary', 'bi-arrow-counterclockwise');
        runBtn.title = 'Ctrl+Enter';
        resetBtn.title = 'Reinicia o ambiente de execução (útil se travou) e limpa a saída';
        [runBtn, resetBtn, backBtn].forEach(b => bar.appendChild(b));
        let hints = 0, solutionSeen = false, recorded = false;
        if (isTask) {
            const hintBtn = button('Dica', 'btn-outline-secondary', 'bi-lightbulb');
            const solBtn = button('Ver solução', 'btn-outline-secondary', 'bi-eye');
            bar.appendChild(hintBtn);
            bar.appendChild(solBtn);
            const note = el('div', 'code-hint small mt-1');
            note.hidden = true;
            bar.after(note);
            hintBtn.addEventListener('click', function () {
                hints++;
                note.hidden = false;
                note.textContent = 'Dica: ' + box.dataset.hint;
            });
            solBtn.addEventListener('click', function () {
                solutionSeen = true;
                note.hidden = false;
                note.innerHTML = '';
                note.appendChild(el('div', null, 'Solução (esta tentativa conta como não concluída):'));
                note.appendChild(el('pre', 'code-stdout', box.dataset.solution));
                if (!recorded) { recorded = true; post(box.dataset.block, false, hints); }
            });
            lastTryNote(box, box.dataset.block);
        }
        const expected = box.querySelector('.code-expected');
        runBtn.addEventListener('click', function () {
            runBtn.disabled = true;
            if (expected) expected.hidden = true;   // a saida ao vivo substitui a conferida na criacao
            out.hidden = false;
            out.innerHTML = '';
            out.appendChild(el('div', 'small text-muted', 'Preparando o ambiente (na primeira vez pode levar alguns segundos)…'));
            const lang = box.dataset.lang;
            run({lang: lang, code: ta.value, datasets: datasets, test: isTask ? box.dataset.test || null : null,
                expected: isTask ? box.dataset.expected || null : null}, function () {
                out.innerHTML = '';
                out.appendChild(el('div', 'small text-muted', 'Rodando…'));
            }).then(function (res) {
                showResult(out, res, lang);
                if (isTask) {
                    const passed = res.ok && res.testPassed !== false && res.matches !== false
                        && (res.testPassed === true || res.matches === true);
                    const verdict = el('div', passed ? 'code-verdict text-ok' : 'code-verdict text-guess',
                        passed ? 'Passou no teste' + (solutionSeen ? ' (depois de ver a solução).' : '!')
                            : res.ok && res.matches === false ? 'Ainda não: o resultado não é o esperado.'
                                : res.ok ? 'Ainda não passou no teste.' : 'O código deu erro.');
                    out.prepend(verdict);
                    if (passed && !recorded) { recorded = true; post(box.dataset.block, !solutionSeen, hints); }
                }
            }).finally(() => { runBtn.disabled = false; });
        });
        resetBtn.addEventListener('click', function () { resetWorker(); out.hidden = true; out.innerHTML = ''; });
        backBtn.addEventListener('click', function () {
            ta.value = original;
            localStorage.removeItem(key);
            if (expected) expected.hidden = false;
            out.hidden = true;
            autosize(ta);
        });
    }

    renderTex();
    deck.querySelectorAll('.calc-block').forEach(setupCalc);
    deck.querySelectorAll('.code-run').forEach(b => setupCode(b, false));
    deck.querySelectorAll('.code-task').forEach(b => setupCode(b, true));
    // Ao abrir um slide escondido, a altura do editor so pode ser medida quando ele aparece.
    deck.addEventListener('click', function () {
        setTimeout(() => deck.querySelectorAll('.code-src').forEach(autosize), 0);
    });
    document.addEventListener('keydown', function () {
        setTimeout(() => deck.querySelectorAll('.lesson-slide:not([hidden]) .code-src').forEach(autosize), 0);
    });
})();
