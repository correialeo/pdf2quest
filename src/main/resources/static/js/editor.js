// Editor das paginas de Estudos: formatacao por botoes (sem precisar saber Markdown), atalhos no inicio da linha
// ("# ", "- ", "1. ", "[] "), checklists e salvamento automatico. Cada alteracao vai antes para um rascunho no
// navegador (localStorage), assim nada se perde se a gravacao falhar ou a aba fechar; o rascunho some quando o
// servidor confirma. O servidor recusa (409) gravacoes de uma versao antiga da pagina (outra aba gravou antes).
(function () {
    var root = document.getElementById('pageEditor');
    if (!root) {
        return;
    }
    var pageId = root.getAttribute('data-page-id');
    var version = parseInt(root.getAttribute('data-version'), 10);
    var editor = document.getElementById('editor');
    var title = document.getElementById('pageTitle');
    var status = document.getElementById('saveStatus');
    var draftKey = 'study-page-draft-' + pageId;
    var url = '/estudos/paginas/' + pageId + '/conteudo';

    var SAVE_DELAY = 800;
    var RETRY_DELAY = 4000;
    var timer = null;
    var saving = false;
    var dirty = false;
    var conflict = false;
    // Titulo e conteudo que esta aba sabe que estao no servidor (os do carregamento ou da ultima gravacao).
    var synced = null;

    // HTML no formato que o navegador usa no editor, para comparar com o que veio do servidor.
    function normalize(html) {
        var div = document.createElement('div');
        div.innerHTML = html || '';
        return div.innerHTML;
    }

    function setStatus(text, kind) {
        status.textContent = text;
        status.className = 'save-status ms-auto text-nowrap' + (kind ? ' is-' + kind : '');
    }

    function snapshot() {
        return {title: title.value, content: editor.innerHTML, version: version};
    }

    function storeDraft() {
        try {
            var draft = snapshot();
            draft.at = Date.now();
            localStorage.setItem(draftKey, JSON.stringify(draft));
        } catch (e) {
            // Navegador sem espaco/privado: segue so com o salvamento no servidor.
        }
    }

    function changed() {
        if (conflict) {
            storeDraft();
            return;
        }
        dirty = true;
        storeDraft();
        setStatus('Alterações não salvas…', 'pending');
        clearTimeout(timer);
        timer = setTimeout(save, SAVE_DELAY);
    }

    function save() {
        if (saving || !dirty || conflict) {
            return;
        }
        saving = true;
        dirty = false;
        setStatus('Salvando…', 'pending');
        var body = snapshot();
        fetch(url, {method: 'PUT', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)})
            .then(function (response) {
                return response.json().catch(function () { return {}; }).then(function (data) {
                    return {status: response.status, data: data};
                });
            })
            .then(function (result) {
                saving = false;
                if (result.status === 200) {
                    version = result.data.version;
                    synced = {title: body.title, content: body.content};
                    if (dirty) {
                        // Digitou durante a gravacao: o rascunho continua e grava de novo.
                        storeDraft();
                        save();
                        return;
                    }
                    localStorage.removeItem(draftKey);
                    var at = new Date(result.data.savedAt);
                    setStatus('Salvo às ' + String(at.getHours()).padStart(2, '0') + ':'
                        + String(at.getMinutes()).padStart(2, '0'), 'ok');
                } else if (result.status === 409 && sameAsServer(result.data)) {
                    // A versao mudou, mas o conteudo no servidor e o que esta aba ja tinha ou quer gravar (ex.: a
                    // gravacao feita ao sair da pagina): nao e conflito de verdade, grava de novo na versao atual.
                    version = result.data.version;
                    synced = {title: result.data.title, content: normalize(result.data.content)};
                    dirty = true;
                    save();
                } else if (result.status === 409) {
                    conflict = true;
                    storeDraft();
                    document.getElementById('conflictBanner').hidden = false;
                    setStatus('Não salvo: página alterada em outro lugar', 'bad');
                } else if (result.status === 404) {
                    conflict = true;
                    setStatus('Esta página foi apagada', 'bad');
                } else {
                    retry();
                }
            })
            .catch(function () {
                saving = false;
                retry();
            });
    }

    function sameAsServer(server) {
        if (server.content === undefined) {
            return false;
        }
        var content = normalize(server.content);
        var current = snapshot();
        return (synced && server.title === synced.title && content === synced.content)
            || (server.title === current.title && content === normalize(current.content));
    }

    function retry() {
        dirty = true;
        setStatus('Não foi possível salvar; tentando de novo… (as alterações estão guardadas neste navegador)', 'bad');
        clearTimeout(timer);
        timer = setTimeout(save, RETRY_DELAY);
    }

    // Rascunho que nao chegou ao servidor (aba fechada, servidor desligado): oferece recuperar.
    function checkDraft() {
        var raw = localStorage.getItem(draftKey);
        if (!raw) {
            return;
        }
        var draft;
        try {
            draft = JSON.parse(raw);
        } catch (e) {
            localStorage.removeItem(draftKey);
            return;
        }
        // O servidor gravou depois do rascunho (ex.: a gravacao feita ao sair da pagina): nada ficou para tras.
        var updatedAt = parseInt(root.getAttribute('data-updated'), 10);
        if ((normalize(draft.content) === editor.innerHTML && draft.title === title.value)
                || (draft.version < version && draft.at <= updatedAt)) {
            localStorage.removeItem(draftKey);
            return;
        }
        var banner = document.getElementById('draftBanner');
        banner.hidden = false;
        document.getElementById('restoreDraft').addEventListener('click', function () {
            editor.innerHTML = draft.content;
            title.value = draft.title;
            banner.hidden = true;
            changed();
        });
        document.getElementById('discardDraft').addEventListener('click', function () {
            localStorage.removeItem(draftKey);
            banner.hidden = true;
        });
    }

    // ---- formatacao ----

    function exec(command, value) {
        editor.focus();
        document.execCommand(command, false, value);
        changed();
    }

    function currentBlock() {
        var selection = window.getSelection();
        if (!selection.rangeCount) {
            return null;
        }
        var node = selection.anchorNode;
        while (node && node !== editor) {
            if (node.nodeType === 1 && /^(P|H1|H2|H3|LI|PRE|BLOCKQUOTE|DIV|TD|TH)$/.test(node.tagName)) {
                return node;
            }
            node = node.parentNode;
        }
        return null;
    }

    function closest(node, selector) {
        while (node && node !== editor) {
            if (node.nodeType === 1 && node.matches(selector)) {
                return node;
            }
            node = node.parentNode;
        }
        return null;
    }

    function insertChecklist() {
        editor.focus();
        var selection = window.getSelection();
        var list = selection.rangeCount ? closest(selection.anchorNode, 'ul') : null;
        if (list) {
            // Lista existente vira checklist (ou volta a ser lista comum).
            if (list.hasAttribute('data-checklist')) {
                list.removeAttribute('data-checklist');
                list.querySelectorAll('li').forEach(function (li) { li.removeAttribute('data-checked'); });
            } else {
                list.setAttribute('data-checklist', '');
                list.querySelectorAll('li').forEach(function (li) { li.setAttribute('data-checked', 'false'); });
            }
            changed();
            return;
        }
        document.execCommand('insertUnorderedList');
        list = selection.rangeCount ? closest(selection.anchorNode, 'ul') : null;
        if (list) {
            list.setAttribute('data-checklist', '');
            list.querySelectorAll('li').forEach(function (li) { li.setAttribute('data-checked', 'false'); });
        }
        changed();
    }

    function insertTable() {
        var rows = '';
        for (var r = 0; r < 3; r++) {
            rows += '<tr>' + (r === 0 ? '<th>Coluna 1</th><th>Coluna 2</th><th>Coluna 3</th>' : '<td><br></td><td><br></td><td><br></td>') + '</tr>';
        }
        exec('insertHTML', '<table><tbody>' + rows + '</tbody></table><p><br></p>');
    }

    function insertLink() {
        var selection = window.getSelection();
        var text = selection.toString();
        var href = window.prompt('Endereço do link (ex.: https://www.planalto.gov.br/...)', 'https://');
        if (!href || href === 'https://') {
            return;
        }
        if (!/^(https?:|mailto:|\/|#)/i.test(href)) {
            href = 'https://' + href;
        }
        if (text) {
            exec('createLink', href);
        } else {
            exec('insertHTML', '<a href="' + href.replace(/"/g, '&quot;') + '">' + escapeHtml(href) + '</a>&nbsp;');
        }
    }

    function escapeHtml(text) {
        return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    var commands = {
        h1: function () { exec('formatBlock', '<h1>'); },
        h2: function () { exec('formatBlock', '<h2>'); },
        h3: function () { exec('formatBlock', '<h3>'); },
        p: function () { exec('formatBlock', '<p>'); },
        quote: function () { exec('formatBlock', '<blockquote>'); },
        code: function () { exec('formatBlock', '<pre>'); },
        bold: function () { exec('bold'); },
        italic: function () { exec('italic'); },
        underline: function () { exec('underline'); },
        strikeThrough: function () { exec('strikeThrough'); },
        ul: function () { exec('insertUnorderedList'); },
        ol: function () { exec('insertOrderedList'); },
        checklist: insertChecklist,
        link: insertLink,
        table: insertTable,
        hr: function () { exec('insertHorizontalRule'); },
        undo: function () { exec('undo'); },
        redo: function () { exec('redo'); }
    };

    document.querySelectorAll('.editor-toolbar [data-cmd]').forEach(function (button) {
        // mousedown + preventDefault: o clique no botao nao tira a selecao do texto.
        button.addEventListener('mousedown', function (e) { e.preventDefault(); });
        button.addEventListener('click', function () {
            commands[button.getAttribute('data-cmd')]();
        });
    });

    // Atalhos no inicio de um paragrafo, como no Notion: "# " titulo, "- " lista, "1. " numerada, "[] " checklist.
    var SHORTCUTS = [
        {pattern: /^#$/, run: commands.h1},
        {pattern: /^##$/, run: commands.h2},
        {pattern: /^###$/, run: commands.h3},
        {pattern: /^[-*]$/, run: commands.ul},
        {pattern: /^1[.)]$/, run: commands.ol},
        {pattern: /^\[\s?\]$/, run: insertChecklist},
        {pattern: /^>$/, run: commands.quote},
        {pattern: /^```$/, run: commands.code}
    ];

    editor.addEventListener('keydown', function (e) {
        if (e.key === ' ') {
            var block = currentBlock();
            var text = block ? block.textContent : editor.textContent;
            var selection = window.getSelection();
            if (block && !/^(LI|PRE|TD|TH)$/.test(block.tagName) && selection.isCollapsed
                    && selection.anchorOffset === text.length) {
                for (var i = 0; i < SHORTCUTS.length; i++) {
                    if (SHORTCUTS[i].pattern.test(text)) {
                        e.preventDefault();
                        block.textContent = '';
                        block.appendChild(document.createElement('br'));
                        var range = document.createRange();
                        range.setStart(block, 0);
                        range.collapse(true);
                        selection.removeAllRanges();
                        selection.addRange(range);
                        SHORTCUTS[i].run();
                        return;
                    }
                }
            }
        }
        if (e.key === 'Enter') {
            // Item novo de checklist nasce desmarcado (o navegador copia os atributos do item anterior).
            setTimeout(function () {
                var li = closest(window.getSelection().anchorNode, 'ul[data-checklist] > li');
                if (li && li.textContent.trim() === '') {
                    li.setAttribute('data-checked', 'false');
                }
            }, 0);
        }
        if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
            // Ctrl+S grava na hora em vez de abrir o "salvar pagina" do navegador.
            e.preventDefault();
            clearTimeout(timer);
            dirty = true;
            save();
        }
    });

    // Marcar/desmarcar item do checklist: clique na caixinha (a margem esquerda do item).
    editor.addEventListener('click', function (e) {
        var li = e.target.closest && e.target.closest('ul[data-checklist] > li');
        if (!li) {
            return;
        }
        var box = li.getBoundingClientRect();
        if (e.clientX - box.left < 26) {
            li.setAttribute('data-checked', li.getAttribute('data-checked') === 'true' ? 'false' : 'true');
            changed();
        }
    });

    // Links abrem com Ctrl/Cmd+clique (clique simples posiciona o cursor para editar).
    editor.addEventListener('click', function (e) {
        var link = e.target.closest && e.target.closest('a[href]');
        if (link && (e.ctrlKey || e.metaKey)) {
            window.open(link.href, '_blank', 'noopener');
        }
    });

    // Colar: mantem so a formatacao que o editor conhece (o servidor limpa de novo ao gravar).
    var ALLOWED = /^(P|BR|H1|H2|H3|STRONG|B|EM|I|U|S|UL|OL|LI|A|TABLE|THEAD|TBODY|TR|TH|TD|PRE|CODE|HR|BLOCKQUOTE)$/;
    var DROP = /^(SCRIPT|STYLE|IFRAME|OBJECT|EMBED|TEMPLATE|NOSCRIPT|META|LINK|TITLE|HEAD|SVG)$/;

    function cleanNode(node, out) {
        node.childNodes.forEach(function (child) {
            if (child.nodeType === 3) {
                out.appendChild(document.createTextNode(child.textContent));
            } else if (child.nodeType === 1 && !DROP.test(child.tagName)) {
                if (ALLOWED.test(child.tagName)) {
                    var copy = document.createElement(child.tagName);
                    if (child.tagName === 'A' && /^(https?:|mailto:)/i.test(child.getAttribute('href') || '')) {
                        copy.setAttribute('href', child.getAttribute('href'));
                    }
                    cleanNode(child, copy);
                    out.appendChild(copy);
                } else {
                    cleanNode(child, out);
                    if (/^(DIV|SECTION|ARTICLE)$/.test(child.tagName)) {
                        out.appendChild(document.createElement('br'));
                    }
                }
            }
        });
        return out;
    }

    editor.addEventListener('paste', function (e) {
        var data = e.clipboardData;
        if (!data) {
            return;
        }
        e.preventDefault();
        var html = data.getData('text/html');
        if (html) {
            var parsed = new DOMParser().parseFromString(html, 'text/html');
            var container = cleanNode(parsed.body, document.createElement('div'));
            document.execCommand('insertHTML', false, container.innerHTML);
        } else {
            var text = data.getData('text/plain');
            document.execCommand('insertHTML', false, escapeHtml(text).replace(/\r?\n/g, '<br>'));
        }
        changed();
    });

    editor.addEventListener('input', changed);
    title.addEventListener('input', changed);
    title.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
            e.preventDefault();
            editor.focus();
        }
    });

    // Saindo da pagina com alteracoes pendentes: grava pelo sendBeacon (sobrevive ao fechamento da aba), sem o
    // dialogo "Sair do site?". Se ainda ha uma gravacao em andamento, ela vai virar a versao atual + 1: o beacon ja
    // vai com essa versao. O rascunho local fica ate a proxima abertura confirmar que o servidor recebeu.
    window.addEventListener('pagehide', function () {
        if ((dirty || saving) && !conflict && navigator.sendBeacon) {
            storeDraft();
            var body = snapshot();
            body.version = version + (saving ? 1 : 0);
            navigator.sendBeacon(url, new Blob([JSON.stringify(body)], {type: 'application/json'}));
        }
    });

    // Pagina restaurada da memoria do navegador (botao Voltar): recarrega para pegar a versao atual.
    window.addEventListener('pageshow', function (e) {
        if (e.persisted) {
            window.location.reload();
        }
    });

    document.execCommand('defaultParagraphSeparator', false, 'p');
    if (editor.innerHTML.trim() === '') {
        editor.innerHTML = '<p><br></p>';
        if (!title.value) {
            title.focus();
        }
    }
    synced = {title: title.value, content: editor.innerHTML};
    checkDraft();
})();
