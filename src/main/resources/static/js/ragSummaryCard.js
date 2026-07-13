class RagSummaryManager {
    constructor() {
        this.card = document.getElementById('aiSummaryCard');
        this.loadingEl = document.getElementById('aiSummaryLoading');
        this.answerEl = document.getElementById('aiSummaryAnswer');
        this.referencesEl = document.getElementById('aiSummaryReferences');
        this.fadeEl = document.getElementById('aiSummaryFade');
        this.moreBtn = document.getElementById('aiSummaryMoreBtn');
        this.errorEl = document.getElementById('aiSummaryError');
        this.fullText = '';
        this.sources = null;
        this._rafScheduled = false;
        this.currentKeyword = null;
        this._abortController = null;
        this.moreBtn?.addEventListener('click', () => this._expandClamp());
        this.answerEl?.addEventListener('click', (e) => this._handleCitationClick(e));
    }

    async start(keyword) {
        if (!keyword || !this.card) return;
        this.currentKeyword = keyword;
        this.reset();
        this.card.style.display = 'block';
        this.loadingEl.style.display = 'flex';

        const controller = new AbortController();
        this._abortController = controller;

        let response;
        try {
            response = await fetch('/api/rag/answer', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ question: keyword, conversationId: crypto.randomUUID() }),
                signal: controller.signal,
            });
        } catch (e) {
            if (controller.signal.aborted) return;
            this._showError('요약을 불러올 수 없습니다');
            return;
        }

        if (response.status === 429) {
            this._showError('요청이 너무 많습니다. 잠시 후 다시 시도해주세요.');
            return;
        }
        if (!response.ok) {
            this._showError('요약을 불러올 수 없습니다');
            return;
        }

        await this._parseEventStream(response, {
            sources: (data) => {
                this.sources = data;
            },
            token: (text) => {
                this.fullText += text;
                this._scheduleRender();
            },
            notfound: (data) => {
                this._showError(data.message || '관련 활용 사례를 찾지 못했습니다');
            },
            error: (data) => {
                this._showError(data.message || '요약을 불러올 수 없습니다');
            },
            done: () => {
                // rAF로 미룬 마지막 토큰 렌더링이 아직 반영되지 않았을 수 있어 동기적으로 한 번 더 그린다
                this._render();
                this._renderReferences();
            },
        }, controller.signal);
    }

    hide() {
        if (this.card) this.card.style.display = 'none';
        this._close();
    }

    reset() {
        this._close();
        this.fullText = '';
        this.sources = null;
        this._rafScheduled = false;
        if (this.answerEl) {
            this.answerEl.innerHTML = '';
            this.answerEl.classList.remove('expanded');
        }
        if (this.referencesEl) {
            this.referencesEl.innerHTML = '';
            this.referencesEl.style.display = 'none';
        }
        if (this.errorEl) { this.errorEl.textContent = ''; this.errorEl.style.display = 'none'; }
        if (this.fadeEl) this.fadeEl.style.display = 'none';
        if (this.moreBtn) this.moreBtn.style.display = 'none';
        if (this.loadingEl) this.loadingEl.style.display = 'none';
    }

    _close() {
        if (this._abortController) {
            this._abortController.abort();
            this._abortController = null;
        }
    }

    // fetch + ReadableStream 기반 SSE 파서 (EventSource는 429 같은 HTTP 상태 코드를 읽을 수 없어 사용 불가)
    async _parseEventStream(response, handlers, signal) {
        const reader = response.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';

        for (;;) {
            let value, done;
            try {
                ({ value, done } = await reader.read());
            } catch (e) {
                if (signal.aborted) return;
                throw e;
            }
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

    // 다음 프레임에 한 번만 렌더링하도록 묶어서(coalesce) 토큰 도착마다 리플로우가 발생하지 않게 함
    _scheduleRender() {
        if (this._rafScheduled) return;
        this._rafScheduled = true;
        requestAnimationFrame(() => {
            this._rafScheduled = false;
            this._render();
        });
    }

    _render() {
        if (!this.answerEl) return;
        if (this.loadingEl) this.loadingEl.style.display = 'none';
        this.answerEl.innerHTML = this._renderMarkdown(this.fullText);
        this._checkClampOverflow();
    }

    // marked.js로 정식 마크다운 렌더링 후 DOMPurify로 sanitize, 마지막으로 [출처N]을 인라인 각주 배지로 치환
    _renderMarkdown(text) {
        if (!text) return '';
        const rawHtml = window.marked.parse(text, { breaks: true, gfm: true });
        const safeHtml = window.DOMPurify.sanitize(rawHtml);
        return safeHtml.replace(/\[출처(\d+)\]/g, (match, n) =>
            `<sup class="ai-citation"><a href="#ai-source-${n}" class="ai-citation-link" data-ref="${n}">${n}</a></sup>`);
    }

    // 답변 하단에 번호가 매겨진 출처 목록을 렌더링 (로고 없이 기업명 + 제목 텍스트만)
    _renderReferences() {
        if (!this.referencesEl) return;
        if (!Array.isArray(this.sources) || this.sources.length === 0) {
            this.referencesEl.innerHTML = '';
            this.referencesEl.style.display = 'none';
            return;
        }
        this.referencesEl.innerHTML = this.sources.map((source, i) => {
            const n = i + 1;
            const corpName = this._escapeHtml(source.corporationName || '');
            const title = this._escapeHtml(source.title || '원문 보기');
            return `
                <a href="/articles/${source.id}" target="_blank" rel="noopener"
                   id="ai-source-${n}" class="ai-reference-item">
                    <span class="ai-reference-num">${n}</span>
                    <span class="ai-reference-text">${corpName ? `<strong>${corpName}</strong> · ` : ''}${title}</span>
                </a>`;
        }).join('');
        this.referencesEl.style.display = 'flex';
    }

    // 본문 중 각주 배지 클릭 시 하단 출처 목록으로 부드럽게 스크롤 + 잠시 하이라이트
    _handleCitationClick(e) {
        const link = e.target.closest('.ai-citation-link');
        if (!link) return;
        e.preventDefault();
        const target = document.getElementById(`ai-source-${link.dataset.ref}`);
        if (!target) return;
        target.scrollIntoView({ behavior: 'smooth', block: 'center' });
        target.classList.add('ai-reference-highlight');
        setTimeout(() => target.classList.remove('ai-reference-highlight'), 1200);
    }

    // 답변 영역은 처음부터 고정 높이로 잘려 있고(clamp), 넘치는 순간 fade+더보기 버튼을 노출한다
    _checkClampOverflow() {
        const box = this.answerEl;
        if (!box || !this.fadeEl || !this.moreBtn || box.classList.contains('expanded')) return;
        const isOverflowing = box.scrollHeight > box.clientHeight + 1;
        this.fadeEl.style.display = isOverflowing ? 'block' : 'none';
        this.moreBtn.style.display = isOverflowing ? 'block' : 'none';
    }

    _expandClamp() {
        if (!this.answerEl) return;
        this.answerEl.classList.add('expanded');
        if (this.fadeEl) this.fadeEl.style.display = 'none';
        if (this.moreBtn) this.moreBtn.style.display = 'none';
    }

    _showError(message) {
        if (!this.errorEl) return;
        if (this.loadingEl) this.loadingEl.style.display = 'none';
        this.errorEl.textContent = message;
        this.errorEl.style.display = 'block';
    }

    _escapeHtml(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }
}

document.addEventListener('DOMContentLoaded', () => {
    if (document.getElementById('aiSummaryCard')) {
        window.ragSummaryManager = new RagSummaryManager();
    }
});
