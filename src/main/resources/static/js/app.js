(function () {
    var STORAGE_KEY = 'theme';

    function applyTheme(theme) {
        document.documentElement.setAttribute('data-bs-theme', theme);
    }

    function currentTheme() {
        return document.documentElement.getAttribute('data-bs-theme') === 'dark' ? 'dark' : 'light';
    }

    function setupThemeToggle() {
        var toggle = document.querySelector('.theme-toggle');
        if (!toggle) {
            return;
        }
        toggle.addEventListener('click', function () {
            var next = currentTheme() === 'dark' ? 'light' : 'dark';
            applyTheme(next);
            localStorage.setItem(STORAGE_KEY, next);
        });
    }

    // Marca o item do menu lateral da pagina atual (o de caminho mais longo: /import/gabarito nao acende /import).
    function highlightActiveNavLink() {
        var path = window.location.pathname;
        var best = null;
        document.querySelectorAll('.sidebar .sidebar-link[href]').forEach(function (link) {
            var href = link.getAttribute('href');
            if (href !== '/' && path.indexOf(href) === 0
                    && (!best || href.length > best.getAttribute('href').length)) {
                best = link;
            }
        });
        if (best) {
            best.classList.add('active');
        }
    }

    // Menu lateral recolhido (so icones) no desktop; a escolha fica salva e o head.html aplica antes de pintar.
    function setupSidebarCollapse() {
        var button = document.querySelector('.sidebar-collapse');
        if (!button) {
            return;
        }
        function toggle() {
            var collapsed = document.documentElement.classList.toggle('sidebar-collapsed');
            localStorage.setItem('sidebarCollapsed', collapsed ? '1' : '0');
        }
        button.addEventListener('click', toggle);
        document.addEventListener('keydown', function (e) {
            var t = e.target;
            if (e.key === '\\' && !e.ctrlKey && !e.metaKey && !e.altKey && !t.isContentEditable
                    && ['INPUT', 'TEXTAREA', 'SELECT'].indexOf(t.tagName) < 0) {
                toggle();
            }
        });
    }

    function postJson(url, body) {
        return fetch(url, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(body)
        }).then(function (response) {
            if (!response.ok) {
                throw new Error(response.status);
            }
        });
    }

    function setupQuestionTools() {
        document.querySelectorAll('.question-tools').forEach(function (tools) {
            var questionId = tools.getAttribute('data-question-id');
            var note = tools.querySelector('.question-note');
            var status = tools.querySelector('.question-note-status');

            note.addEventListener('change', function () {
                postJson('/questoes/' + questionId + '/observacao', {note: note.value})
                    .then(function () { status.textContent = 'Observação salva.'; })
                    .catch(function () { status.textContent = 'Falha ao salvar a observação.'; });
            });
        });
    }

    // Anotacao rapida (Estudos): Ctrl/Cmd+Enter guarda sem tirar a mao do teclado.
    function setupQuickNote() {
        document.querySelectorAll('.quick-note textarea').forEach(function (area) {
            area.addEventListener('keydown', function (e) {
                if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && area.value.trim() !== '') {
                    e.preventDefault();
                    area.form.submit();
                }
            });
        });
    }

    // Cronograma: ao concluir (ou ao clicar no tempo) pergunta os minutos estudados, ja com o estimado.
    function setupTaskTime() {
        var forms = document.querySelectorAll('.task-done-form, .task-time-form');
        if (!forms.length) return;
        var dialog = document.createElement('dialog');
        dialog.className = 'task-time-dialog';
        dialog.innerHTML = '<form method="dialog">'
            + '<div class="fw-semibold mb-1" data-role="heading"></div>'
            + '<div class="small text-muted mb-3 text-truncate" data-role="title"></div>'
            + '<label class="form-label small" for="taskTimeInput">Minutos estudados</label>'
            + '<input type="number" id="taskTimeInput" class="form-control mb-3" min="0" step="5" inputmode="numeric"/>'
            + '<div class="d-flex flex-wrap gap-2 justify-content-end">'
            + '<button type="button" class="btn btn-link btn-sm me-auto px-0" data-role="skip"></button>'
            + '<button type="button" class="btn btn-outline-secondary btn-sm" data-role="cancel">Cancelar</button>'
            + '<button type="submit" class="btn btn-primary btn-sm" data-role="ok"></button>'
            + '</div></form>';
        document.body.appendChild(dialog);
        var input = dialog.querySelector('input');
        var target = null;

        function finish(minutes) {
            target.querySelector('[name=spentMinutes]').value = minutes;
            dialog.close();
            target.submit();
        }

        function open(form, completing) {
            target = form;
            var spent = form.dataset.spent, estimate = form.dataset.estimate;
            dialog.querySelector('[data-role=heading]').textContent = completing ? 'Concluir tarefa' : 'Tempo estudado';
            dialog.querySelector('[data-role=title]').textContent = form.dataset.title || '';
            dialog.querySelector('[data-role=ok]').textContent = completing ? 'Concluir' : 'Salvar';
            dialog.querySelector('[data-role=skip]').textContent = completing ? 'Concluir sem registrar tempo' : 'Apagar o tempo';
            dialog.querySelector('[data-role=skip]').hidden = !completing && !spent;
            input.value = spent || estimate || '';
            dialog.showModal();
            input.select();
        }

        dialog.querySelector('form').addEventListener('submit', function (e) {
            e.preventDefault();
            var value = input.value.trim();
            if (value !== '' && !/^\d+$/.test(value)) { input.focus(); return; }
            finish(value);
        });
        dialog.querySelector('[data-role=skip]').addEventListener('click', function () { finish(''); });
        dialog.querySelector('[data-role=cancel]').addEventListener('click', function () { dialog.close(); });

        forms.forEach(function (form) {
            form.addEventListener('submit', function (e) {
                var completing = form.classList.contains('task-done-form');
                if (completing && form.dataset.done === 'true') return; // reabrir nao pergunta
                e.preventDefault();
                open(form, completing);
            });
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        setupTaskTime();
        setupQuickNote();
        setupThemeToggle();
        highlightActiveNavLink();
        setupSidebarCollapse();
        setupQuestionTools();
    });
})();
