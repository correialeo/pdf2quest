// Cronometro por tarefa: conta o tempo com a aula/quiz da tarefa aberto e a aba visivel. Fica no navegador
// (localStorage "p2q-timer-<tarefa>": {ms, paused, at}) e preenche os minutos ao concluir a tarefa.
(function () {
    var PREFIX = 'p2q-timer-';

    function load(task) {
        try { return JSON.parse(localStorage.getItem(PREFIX + task)) || {ms: 0, paused: false}; }
        catch (e) { return {ms: 0, paused: false}; }
    }

    function save(task, state) { localStorage.setItem(PREFIX + task, JSON.stringify(state)); }

    function minutes(task) { return Math.round(load(task).ms / 60000); }

    function format(ms) {
        var m = Math.floor(ms / 60000);
        return m < 60 ? m + ' min' : Math.floor(m / 60) + 'h' + String(m % 60).padStart(2, '0');
    }

    window.p2qTimer = {
        minutes: minutes,
        clear: function (task) { localStorage.removeItem(PREFIX + task); }
    };

    // Hoje: mostra o tempo ja contado nas tarefas em andamento.
    document.querySelectorAll('[data-timer-task]').forEach(function (el) {
        var ms = load(el.dataset.timerTask).ms;
        if (ms >= 60000) {
            el.hidden = false;
            el.innerHTML = '<i class="bi bi-stopwatch me-1"></i>' + format(ms) + ' contados';
        }
    });

    var bar = document.querySelector('.session-bar[data-task]');
    if (!bar) return;
    document.body.classList.add('has-session-bar');
    var task = bar.dataset.task;
    var toggle = bar.querySelector('.session-timer-toggle');
    if (!toggle) return;
    var timeEl = toggle.querySelector('[data-role=time]');
    var iconEl = toggle.querySelector('[data-role=icon]');
    var state = load(task);
    var last = Date.now();

    function render() {
        timeEl.textContent = format(state.ms);
        iconEl.className = 'bi ' + (state.paused ? 'bi-play-fill' : 'bi-pause-fill');
        toggle.classList.toggle('is-paused', state.paused);
    }

    function tick() {
        var now = Date.now();
        // Parte sempre do que esta salvo (outras abas da mesma tarefa tambem contam). "at" e o ultimo
        // instante ja contado por qualquer aba: cada aba so soma o trecho depois dele, entao duas abas
        // abertas juntas nao contam o mesmo segundo duas vezes.
        var stored = load(task);
        if (!stored.paused && document.visibilityState === 'visible') {
            var from = Math.max(last, stored.at || 0);
            stored.ms += Math.min(Math.max(now - from, 0), 5000);   // aba dormindo/suspensa nao conta
            stored.at = now;
        }
        last = now;
        state = stored;
        save(task, state);
        render();
    }

    toggle.addEventListener('click', function () {
        tick();
        state.paused = !state.paused;
        save(task, state);
        render();
    });
    document.addEventListener('visibilitychange', function () { tick(); });
    window.addEventListener('pagehide', tick);
    setInterval(tick, 1000);
    render();
})();
