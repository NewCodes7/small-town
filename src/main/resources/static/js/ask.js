(() => {
    const conversationId = crypto.randomUUID();

    const messagesEl = document.getElementById('askMessages');
    const formEl = document.getElementById('askForm');
    const inputEl = document.getElementById('askInput');
    const submitEl = document.getElementById('askSubmit');
    const toastEl = document.getElementById('askToast');

    let toastTimer = null;

    function showToast(message) {
        toastEl.textContent = message;
        toastEl.style.display = 'block';
        if (toastTimer) clearTimeout(toastTimer);
        toastTimer = setTimeout(() => {
            toastEl.style.display = 'none';
        }, 4000);
    }

    function setBusy(busy) {
        inputEl.disabled = busy;
        submitEl.disabled = busy;
    }

    function appendQuestion(question) {
        const turn = document.createElement('div');
        turn.className = 'ask-turn';
        const q = document.createElement('div');
        q.className = 'ask-question';
        q.textContent = question;
        turn.appendChild(q);
        messagesEl.appendChild(turn);
        return turn;
    }

    function appendAnswerPlaceholder(turn) {
        const a = document.createElement('div');
        a.className = 'ask-answer';
        a.textContent = '';
        turn.appendChild(a);
        return a;
    }

    function appendSources(turn, sources) {
        if (!Array.isArray(sources) || sources.length === 0) return;
        const box = document.createElement('div');
        box.className = 'ask-sources';
        sources.forEach((s) => {
            const link = document.createElement('a');
            const href = typeof s.articleUrl === 'string' && /^https?:\/\//.test(s.articleUrl) ? s.articleUrl : '#';
            link.href = href;
            link.target = '_blank';
            link.rel = 'noopener noreferrer';
            link.textContent = `${s.corporationName || ''} · ${s.articleTitle || ''}`;
            box.appendChild(link);
        });
        turn.appendChild(box);
    }

    async function parseEventStream(response, handlers) {
        const reader = response.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';

        for (;;) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });

            let sepIndex;
            while ((sepIndex = buffer.indexOf('\n\n')) !== -1) {
                const rawEvent = buffer.slice(0, sepIndex);
                buffer = buffer.slice(sepIndex + 2);

                let eventName = 'message';
                let data = '';
                for (const line of rawEvent.split('\n')) {
                    if (line.startsWith('event:')) {
                        eventName = line.slice(6).trim();
                    } else if (line.startsWith('data:')) {
                        data += line.slice(5).trim();
                    }
                }
                if (!data) continue;
                const handler = handlers[eventName];
                if (handler) {
                    try {
                        handler(JSON.parse(data));
                    } catch (e) {
                        console.error('SSE 이벤트 파싱 실패', eventName, e);
                    }
                }
            }
        }
    }

    async function ask(question) {
        const turn = appendQuestion(question);
        const answerEl = appendAnswerPlaceholder(turn);
        let answerText = '';

        let response;
        try {
            response = await fetch('/api/rag/answer', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ question, conversationId }),
            });
        } catch (e) {
            answerEl.classList.add('ask-answer-error');
            answerEl.textContent = '답변을 불러올 수 없습니다';
            setBusy(false);
            return;
        }

        if (response.status === 429) {
            turn.remove();
            showToast('요청이 너무 많습니다. 잠시 후 다시 시도해주세요.');
            setBusy(false);
            return;
        }
        if (!response.ok) {
            answerEl.classList.add('ask-answer-error');
            answerEl.textContent = '답변을 불러올 수 없습니다';
            setBusy(false);
            return;
        }

        await parseEventStream(response, {
            token: (text) => {
                answerText += text;
                answerEl.textContent = answerText;
            },
            sources: (sources) => {
                appendSources(turn, sources);
            },
            notfound: (payload) => {
                answerEl.textContent = payload.message || '관련 내용을 찾지 못했습니다';
            },
            error: (payload) => {
                answerEl.classList.add('ask-answer-error');
                answerEl.textContent = payload.message || '답변을 불러올 수 없습니다';
            },
        });

        setBusy(false);
        inputEl.focus();
    }

    formEl.addEventListener('submit', (e) => {
        e.preventDefault();
        const question = inputEl.value.trim();
        if (!question) return;
        inputEl.value = '';
        setBusy(true);
        ask(question);
    });
})();
