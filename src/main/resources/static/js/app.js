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

    document.addEventListener('DOMContentLoaded', function () {
        setupThemeToggle();
        highlightActiveNavLink();
        setupQuestionTools();
    });
})();
