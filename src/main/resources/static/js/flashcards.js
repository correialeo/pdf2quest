/*
 * Flashcards da revisao, antes das questoes do quiz (mesma tela). Um cartao por vez: lembrar, virar, avaliar
 * (errei, dificil, facil). No fim, as questoes dos conceitos marcados errei/dificil sobem para o comeco.
 * Usa "cards" e "markCurrent" do script do quiz (quiz.html), carregado antes deste.
 */
(function () {
    'use strict';
    const deck = document.querySelector('.flash-deck');
    if (!deck) return;
    const quizId = deck.dataset.quiz;
    const flash = Array.from(deck.querySelectorAll('.flash-card'));
    const summary = deck.querySelector('.flash-summary');
    const progress = deck.querySelector('.flash-progress');
    const questions = Array.from(document.querySelectorAll('.quiz-question'));
    const LABEL = {errei: 'errei', dificil: 'difícil', facil: 'fácil'};
    let index = 0;

    function setPhase(cardsPhase) {
        document.body.classList.toggle('flash-phase', cardsPhase);
        questions.forEach(q => { q.hidden = cardsPhase; });
        const done = document.getElementById('quizDone');
        if (cardsPhase && done) done.hidden = true;
    }

    function show(i) {
        index = i;
        flash.forEach((c, k) => { c.hidden = k !== i; });
        summary.hidden = i < flash.length;
        if (i < flash.length) {
            const c = flash[i];
            c.querySelector('.flash-back').hidden = true;
            c.querySelector('.flash-rate').hidden = true;
            c.querySelector('.flash-show').hidden = false;
            progress.textContent = 'Cartão ' + (i + 1) + ' de ' + flash.length;
        } else {
            finish();
        }
    }

    function flip(c) {
        c.querySelector('.flash-back').hidden = false;
        c.querySelector('.flash-show').hidden = true;
        c.querySelector('.flash-rate').hidden = false;
    }

    function rate(c, rating) {
        c.dataset.rating = rating;
        fetch('/quizzes/' + quizId + '/flashcard', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({card: Number(c.dataset.id), rating: rating})
        }).then(r => { if (!r.ok) throw new Error(r.status); })
            .catch(() => window.p2qToast && window.p2qToast('Não foi possível salvar a avaliação do cartão.', 'danger'));
        show(index + 1);
    }

    /** Questoes que cobram um conceito marcado errei/dificil vao para o comeco (na ordem original entre si). */
    function prioritize() {
        const weak = new Set(flash.filter(c => c.dataset.rating === 'errei' || c.dataset.rating === 'dificil')
            .map(c => c.dataset.concept));
        const isWeak = q => (q.dataset.concepts || '').split(',').map(x => x.trim()).some(x => weak.has(x));
        const ordered = questions.filter(isWeak).concat(questions.filter(q => !isWeak(q)));
        const anchor = questions[0] ? questions[0].parentNode : null;
        const first = questions.length ? questions.reduce((a, b) =>
            (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_PRECEDING) ? b : a) : null;
        if (anchor && first) {
            const marker = document.createComment('questoes');
            anchor.insertBefore(marker, first);
            ordered.forEach(q => anchor.insertBefore(q, marker));
            marker.remove();
        }
        questions.forEach(function (q) {
            const old = q.querySelector('.flash-hint');
            if (old) old.remove();
            if (isWeak(q)) {
                const tag = document.createElement('div');
                tag.className = 'flash-hint small mb-1';
                tag.innerHTML = '<i class="bi bi-exclamation-diamond me-1"></i>Você marcou este conceito como difícil no cartão.';
                q.prepend(tag);
            }
        });
        if (typeof cards !== 'undefined') {
            cards.sort((a, b) => ordered.indexOf(a) - ordered.indexOf(b));
            if (typeof markCurrent === 'function') markCurrent();
        }
    }

    function finish() {
        const count = {errei: 0, dificil: 0, facil: 0};
        flash.forEach(c => { if (count[c.dataset.rating] !== undefined) count[c.dataset.rating]++; });
        progress.textContent = flash.length + ' cartões';
        summary.querySelector('.flash-summary-text').textContent = 'Cartões: ' + count.errei + ' errei · '
            + count.dificil + ' difícil · ' + count.facil + ' fácil.'
            + (count.errei + count.dificil ? ' As questões desses conceitos vêm primeiro.' : '');
        prioritize();
    }

    function goQuestions() {
        summary.hidden = false;
        setPhase(false);
        const first = questions.find(q => !q.dataset.selected) || questions[0];
        if (first) first.scrollIntoView({behavior: 'smooth', block: 'center'});
    }

    flash.forEach(function (c) {
        c.querySelector('.flash-show').addEventListener('click', () => flip(c));
        c.querySelectorAll('.flash-rate button').forEach(b => b.addEventListener('click', () => rate(c, b.dataset.rating)));
    });
    summary.querySelector('.flash-go').addEventListener('click', goQuestions);
    summary.querySelector('.flash-again').addEventListener('click', function () { setPhase(true); show(0); });

    document.addEventListener('keydown', function (e) {
        if (!document.body.classList.contains('flash-phase')) return;
        const t = e.target;
        if (e.ctrlKey || e.metaKey || e.altKey || ['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName)) return;
        if (index >= flash.length) {
            if (e.key === 'Enter') { e.preventDefault(); goQuestions(); }
            return;
        }
        const c = flash[index];
        const flipped = !c.querySelector('.flash-back').hidden;
        if (!flipped && (e.key === ' ' || e.key === 'Enter')) { e.preventDefault(); flip(c); }
        else if (flipped && ['1', '2', '3'].includes(e.key)) {
            e.preventDefault();
            rate(c, ['errei', 'dificil', 'facil'][Number(e.key) - 1]);
        }
    });

    // Volta onde parou: cartoes ja avaliados neste quiz pulam; tudo avaliado = direto para as questoes.
    const firstOpen = flash.findIndex(c => !c.dataset.rating);
    const answered = questions.some(q => q.dataset.selected);
    if (firstOpen < 0 || answered) {
        index = flash.length;
        flash.forEach(c => { c.hidden = true; });
        finish();
        summary.hidden = false;
        setPhase(false);
    } else {
        setPhase(true);
        show(firstOpen);
    }
})();
