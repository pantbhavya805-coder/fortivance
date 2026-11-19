/*
 * Fortivance AI working overlay.
 *
 * Usage:
 *   const result = await AiLoader.run(() => api.post(...), AiLoader.PRESETS.incidentUpload);
 *   // or manual: AiLoader.show({ title, steps }); ... AiLoader.hide();
 *
 * While visible, the overlay covers the page, blocks clicks, and makes the rest of the page
 * inert so keyboard focus cannot reach buttons behind it.
 */
(function () {
    const PRESETS = {
        incidentUpload: {
            title: 'Analysing vendor notice',
            steps: [
                'Reading the notice locally',
                'Extracting incident facts with Claude Haiku',
                'Matching the vendor and checking the sender domain',
                'Tracing impact across vendors, applications and services',
                'Drafting the response plan with Claude Sonnet'
            ],
            stepMs: 3500
        },
        responsePlan: {
            title: 'Drafting response plan',
            steps: [
                'Collecting incident facts and impact',
                'Checking the RBI reporting window',
                'Drafting actions with Claude Sonnet'
            ],
            stepMs: 4000
        },
        escalation: {
            title: 'Drafting escalation note',
            steps: [
                'Reading the approved plan',
                'Summarising for leadership with Claude Haiku'
            ],
            stepMs: 3000
        },
        evidence: {
            title: 'Mapping evidence to requirements',
            steps: [
                'Splitting documents into citable passages',
                'Mapping clauses to requirements with Claude Sonnet',
                'Validating every citation against the source text',
                'Building the gap report and risk score'
            ],
            stepMs: 9000
        },
        generic: {
            title: 'Working on it',
            steps: ['Processing with Claude'],
            stepMs: 4000
        }
    };

    let overlay = null, timer = null, clock = null, startedAt = 0, current = 0, config = null;

    function build(cfg) {
        const el = document.createElement('div');
        el.className = 'ai-overlay';
        el.setAttribute('role', 'alertdialog');
        el.setAttribute('aria-modal', 'true');
        el.setAttribute('aria-live', 'polite');
        el.setAttribute('aria-busy', 'true');

        const card = document.createElement('div');
        card.className = 'ai-overlay-card';

        const orbit = document.createElement('div');
        orbit.className = 'ai-orbit';
        orbit.setAttribute('aria-hidden', 'true');
        for (let i = 0; i < 3; i++) orbit.appendChild(document.createElement('span'));

        const title = document.createElement('div');
        title.className = 'ai-overlay-title';
        title.textContent = cfg.title;

        const step = document.createElement('div');
        step.className = 'ai-overlay-step';
        step.textContent = cfg.steps[0] || '';

        const list = document.createElement('ul');
        list.className = 'ai-overlay-steps';
        if (cfg.steps.length > 1) {
            cfg.steps.forEach((s, i) => {
                const li = document.createElement('li');
                li.textContent = s;
                if (i === 0) li.classList.add('active');
                list.appendChild(li);
            });
        }

        const meta = document.createElement('div');
        meta.className = 'ai-overlay-meta';
        meta.textContent = 'Elapsed 0s \u00B7 AI output will need human review';

        card.append(orbit, title, step);
        if (cfg.steps.length > 1) card.appendChild(list);
        card.appendChild(meta);
        el.appendChild(card);

        // Swallow every interaction behind the overlay.
        ['click', 'mousedown', 'keydown', 'wheel', 'touchstart'].forEach(evt =>
            el.addEventListener(evt, e => { if (evt !== 'wheel') e.preventDefault(); e.stopPropagation(); }, true));

        return { el, step, list, meta };
    }

    function setInert(on) {
        document.querySelectorAll('body > *:not(.ai-overlay)').forEach(n => {
            if (on) { n.setAttribute('inert', ''); n.setAttribute('aria-hidden', 'true'); }
            else { n.removeAttribute('inert'); n.removeAttribute('aria-hidden'); }
        });
    }

    function advance(parts) {
        // Move through the steps on a timer, but never mark the last step done: the request decides that.
        if (current >= config.steps.length - 1) return;
        const items = parts.list.querySelectorAll('li');
        if (items[current]) { items[current].classList.remove('active'); items[current].classList.add('done'); }
        current++;
        if (items[current]) items[current].classList.add('active');
        parts.step.classList.add('fading');
        setTimeout(() => {
            parts.step.textContent = config.steps[current];
            parts.step.classList.remove('fading');
        }, 200);
    }

    function show(cfg) {
        hide(true);
        config = Object.assign({}, PRESETS.generic, cfg || {});
        current = 0;
        const parts = build(config);
        overlay = parts;
        document.body.appendChild(parts.el);
        document.body.classList.add('ai-busy');
        if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
        setInert(true);
        requestAnimationFrame(() => parts.el.classList.add('visible'));

        startedAt = Date.now();
        clock = setInterval(() => {
            const s = Math.round((Date.now() - startedAt) / 1000);
            parts.meta.textContent = `Elapsed ${s}s \u00B7 AI output will need human review`;
        }, 1000);
        timer = setInterval(() => advance(parts), config.stepMs);
    }

    function hide(immediate) {
        clearInterval(timer); clearInterval(clock);
        timer = clock = null;
        if (!overlay) return;
        const el = overlay.el;
        overlay = null;
        setInert(false);
        document.body.classList.remove('ai-busy');
        if (immediate) { el.remove(); return; }
        el.classList.remove('visible');
        setTimeout(() => el.remove(), 200);
    }

    function markAllDone() {
        if (!overlay) return;
        clearInterval(timer);
        timer = null;
        if (overlay.list) {
            overlay.list.querySelectorAll('li').forEach(li => {
                li.classList.remove('active');
                li.classList.add('done');
            });
        }
        if (overlay.step) {
            overlay.step.textContent = 'Done';
            overlay.step.classList.remove('fading');
        }
    }

    async function run(task, cfg) {
        show(typeof cfg === 'string' ? (PRESETS[cfg] || PRESETS.generic) : cfg);
        let success = false;
        try {
            const result = await (typeof task === 'function' ? task() : task);
            success = true;
            markAllDone();
            await new Promise(resolve => setTimeout(resolve, 600));
            hide();
            return result;
        } finally {
            if (!success) {
                hide(true);
            }
        }
    }

    window.AiLoader = { show, hide, run, markAllDone, PRESETS };
})();
