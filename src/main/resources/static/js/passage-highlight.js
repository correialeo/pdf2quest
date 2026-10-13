/*
 * Grifo do texto base: o usuario seleciona um trecho e ele fica marcado (como um marca-texto).
 * Selecionar de novo DENTRO de um trecho grifado tira o grifo daquela parte. Os trechos sao guardados como
 * posicoes de caractere do texto (nao como HTML), entao sobrevivem a recarregar a pagina e a trocar de questao.
 * Se o texto mudar (ex.: prova reprocessada) o grifo antigo e descartado em vez de cair no lugar errado.
 */
(function (global) {
    'use strict';

    var MARK = 'user-highlight';

    /** Une trechos que se tocam ou se sobrepoem; devolve novos [{s,e}] ordenados. */
    function normalize(ranges) {
        var sorted = ranges.filter(function (r) { return r.e > r.s; })
            .map(function (r) { return {s: r.s, e: r.e}; })
            .sort(function (a, b) { return a.s - b.s; });
        var out = [];
        sorted.forEach(function (r) {
            var last = out[out.length - 1];
            if (last && r.s <= last.e) {
                last.e = Math.max(last.e, r.e);
            } else {
                out.push(r);
            }
        });
        return out;
    }

    function subtract(ranges, s, e) {
        var out = [];
        ranges.forEach(function (r) {
            if (r.e <= s || r.s >= e) {
                out.push(r);
                return;
            }
            if (r.s < s) { out.push({s: r.s, e: s}); }
            if (r.e > e) { out.push({s: e, e: r.e}); }
        });
        return normalize(out);
    }

    function covered(ranges, s, e) {
        return ranges.some(function (r) { return r.s <= s && r.e >= e; });
    }

    function textNodes(root) {
        var nodes = [];
        var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
        while (walker.nextNode()) { nodes.push(walker.currentNode); }
        return nodes;
    }

    function fullText(root) {
        var r = document.createRange();
        r.selectNodeContents(root);
        return r.toString();
    }

    function offsetIn(root, node, offset) {
        var r = document.createRange();
        r.selectNodeContents(root);
        r.setEnd(node, offset);
        return r.toString().length;
    }

    function unwrap(root) {
        root.querySelectorAll('mark.' + MARK).forEach(function (m) {
            var parent = m.parentNode;
            while (m.firstChild) { parent.insertBefore(m.firstChild, m); }
            parent.removeChild(m);
        });
        root.normalize();
    }

    function wrap(root, ranges) {
        var pos = 0;
        textNodes(root).forEach(function (node) {
            var start = pos;
            var end = pos + node.nodeValue.length;
            pos = end;
            var segments = [];
            ranges.forEach(function (r) {
                var from = Math.max(r.s, start) - start;
                var to = Math.min(r.e, end) - start;
                if (to > from) { segments.push({from: from, to: to}); }
            });
            // De tras para frente: dividir o fim do no nao muda as posicoes do comeco.
            segments.sort(function (a, b) { return b.from - a.from; }).forEach(function (seg) {
                var range = document.createRange();
                range.setStart(node, seg.from);
                range.setEnd(node, seg.to);
                var mark = document.createElement('mark');
                mark.className = MARK;
                range.surroundContents(mark);
            });
        });
    }

    /**
     * @param root    elemento com o texto base
     * @param key     chave de armazenamento (uma por sessao e texto)
     * @param options {storage: objeto com getItem/setItem/removeItem, padrao localStorage}
     */
    function init(root, key, options) {
        var storage = (options && options.storage) || global.localStorage;
        var length = fullText(root).length;
        var ranges = load();

        function load() {
            try {
                var saved = JSON.parse(storage.getItem(key) || 'null');
                if (saved && saved.length === length && Array.isArray(saved.ranges)) {
                    return normalize(saved.ranges.filter(function (r) { return r.e <= length; }));
                }
            } catch (e) {
                // armazenamento indisponivel ou corrompido: comeca sem grifo
            }
            return [];
        }

        function save() {
            try {
                if (ranges.length === 0) {
                    storage.removeItem(key);
                } else {
                    storage.setItem(key, JSON.stringify({length: length, ranges: ranges}));
                }
            } catch (e) {
                // sem armazenamento o grifo vale so ate recarregar a pagina
            }
        }

        function render() {
            unwrap(root);
            wrap(root, ranges);
        }

        /** Posicoes da selecao atual dentro do texto, sem espacos nas pontas; null se nao houver. */
        function selectionRange() {
            var sel = global.getSelection();
            if (!sel || sel.rangeCount === 0 || sel.isCollapsed) { return null; }
            var r = sel.getRangeAt(0);
            if (!root.contains(r.commonAncestorContainer)) { return null; }
            var s = offsetIn(root, r.startContainer, r.startOffset);
            var e = offsetIn(root, r.endContainer, r.endOffset);
            var text = fullText(root);
            while (s < e && /\s/.test(text.charAt(s))) { s++; }
            while (e > s && /\s/.test(text.charAt(e - 1))) { e--; }
            return s < e ? {s: s, e: e} : null;
        }

        /** Grifa a selecao (ou tira o grifo se ela esta toda dentro de um trecho grifado). */
        function apply() {
            var sel = selectionRange();
            if (!sel) { return false; }
            ranges = covered(ranges, sel.s, sel.e)
                ? subtract(ranges, sel.s, sel.e)
                : normalize(ranges.concat([sel]));
            save();
            render();
            global.getSelection().removeAllRanges();
            return true;
        }

        function clear() {
            ranges = [];
            save();
            render();
        }

        // Mouse: grifa ao soltar o botao. Toque: o usuario usa o botao "Grifar selecao" (a selecao nativa
        // do celular continua sendo ajustada depois do toque).
        root.addEventListener('pointerup', function (event) {
            if (event.pointerType === 'mouse' && event.button === 0) {
                setTimeout(apply, 0);
            }
        });

        render();
        return {
            apply: apply,
            clear: clear,
            ranges: function () { return ranges.map(function (r) { return {s: r.s, e: r.e}; }); },
            render: render
        };
    }

    global.PassageHighlight = {init: init, _normalize: normalize, _subtract: subtract};
})(window);
