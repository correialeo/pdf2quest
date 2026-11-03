/*
 * Expressoes dos exercicios guiados (```calculo). MESMA gramatica de CalcExpr.java (o servidor valida o gerador na
 * criacao da aula; aqui o navegador sorteia os numeros e confere cada etapa). Sem eval: so aritmetica.
 */
(function (root) {
    'use strict';

    function evaluate(src, vars) {
        if (src == null || String(src).trim() === '') throw new Error('expressão vazia');
        src = String(src);
        let pos = 0;
        const skip = () => { while (pos < src.length && /\s/.test(src[pos])) pos++; };
        const eat = t => { skip(); if (src.startsWith(t, pos)) { pos += t.length; return true; } return false; };
        const bool = b => (b ? 1 : 0);

        function or() { let a = and(); while (eat('||')) { const b = and(); a = bool(a !== 0 || b !== 0); } return a; }
        function and() { let a = cmp(); while (eat('&&')) { const b = cmp(); a = bool(a !== 0 && b !== 0); } return a; }
        function cmp() {
            const a = add();
            for (const op of ['==', '!=', '<=', '>=', '<', '>']) {
                if (eat(op)) {
                    const b = add();
                    switch (op) {
                        case '==': return bool(Math.abs(a - b) < 1e-9);
                        case '!=': return bool(Math.abs(a - b) >= 1e-9);
                        case '<=': return bool(a <= b);
                        case '>=': return bool(a >= b);
                        case '<': return bool(a < b);
                        default: return bool(a > b);
                    }
                }
            }
            return a;
        }
        function add() {
            let a = mul();
            for (;;) {
                if (eat('+')) a += mul();
                else { skip(); if (src[pos] === '-') { pos++; a -= mul(); } else return a; }
            }
        }
        function mul() {
            let a = unary();
            for (;;) {
                if (eat('*')) a *= unary();
                else if (eat('/')) a /= unary();
                else if (eat('%')) a %= unary();
                else return a;
            }
        }
        function unary() {
            skip();
            if (src[pos] === '-') { pos++; return -unary(); }
            if (src[pos] === '!' && !src.startsWith('!=', pos)) { pos++; return bool(unary() === 0); }
            return pow();
        }
        function pow() { const b = primary(); return eat('^') ? Math.pow(b, unary()) : b; }
        function primary() {
            skip();
            if (pos >= src.length) throw new Error('"' + src + '": termina no meio');
            const c = src[pos];
            if (eat('(')) { const x = or(); if (!eat(')')) throw new Error('"' + src + '": falta ")"'); return x; }
            if (/[0-9.]/.test(c)) {
                const m = /^[0-9.]+(?:[eE][-+]?[0-9]+)?/.exec(src.slice(pos));
                pos += m[0].length;
                return parseFloat(m[0]);
            }
            if (/[A-Za-z_À-ſ]/.test(c)) {
                const m = /^[A-Za-z_À-ſ][A-Za-z0-9_À-ſ]*/.exec(src.slice(pos));
                pos += m[0].length;
                const name = m[0];
                if (eat('(')) {
                    const args = [];
                    if (!eat(')')) {
                        do { args.push(or()); } while (eat(','));
                        if (!eat(')')) throw new Error('"' + src + '": falta ")" em ' + name + '(');
                    }
                    return call(name, args);
                }
                if (!Object.prototype.hasOwnProperty.call(vars, name)) {
                    throw new Error('variável desconhecida "' + name + '" em "' + src + '"');
                }
                return vars[name];
            }
            throw new Error('"' + src + '": caractere inesperado "' + c + '"');
        }
        function call(name, a) {
            const one = ['ln', 'log10', 'exp', 'sqrt', 'abs', 'floor', 'ceil'];
            if (one.includes(name) && a.length === 1) {
                const x = a[0];
                return {ln: Math.log, log10: Math.log10, exp: Math.exp, sqrt: Math.sqrt, abs: Math.abs,
                    floor: Math.floor, ceil: Math.ceil}[name](x);
            }
            if (name === 'round' && (a.length === 1 || a.length === 2)) return round(a[0], a.length === 2 ? Math.round(a[1]) : 0);
            if (name === 'min' && a.length >= 1) return Math.min.apply(null, a);
            if (name === 'max' && a.length >= 1) return Math.max.apply(null, a);
            if (name === 'pow' && a.length === 2) return Math.pow(a[0], a[1]);
            if (!one.concat(['round', 'min', 'max', 'pow']).includes(name)) {
                throw new Error('função desconhecida "' + name + '"');
            }
            throw new Error('número de argumentos errado em ' + name + '()');
        }

        const v = or();
        skip();
        if (pos < src.length) throw new Error('"' + src + '": sobra "' + src.slice(pos) + '"');
        return v;
    }

    function round(x, places) {
        const f = Math.pow(10, places);
        return Math.floor(x * f + 0.5) / f;
    }

    const api = {evaluate: evaluate, round: round};
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    else root.P2QCalcExpr = api;
})(typeof self !== 'undefined' ? self : this);
