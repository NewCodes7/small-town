class RagSummaryManager {
    constructor() {
        this.card = document.getElementById('aiSummaryCard');
        this.loadingEl = document.getElementById('aiSummaryLoading');
        this.bubblesEl = document.getElementById('aiSummaryBubbles');
        this.fadeEl = document.getElementById('aiSummaryFade');
        this.moreBtn = document.getElementById('aiSummaryMoreBtn');
        this.errorEl = document.getElementById('aiSummaryError');
        this.fullText = '';
        this.sources = null;
        this._rafScheduled = false;
        this.currentKeyword = null;
        this._abortController = null;
        this.moreBtn?.addEventListener('click', () => this._expandClamp());
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
                this._scheduleStreamRender();
            },
            notfound: (data) => {
                this._showError(data.message || '관련 활용 사례를 찾지 못했습니다');
            },
            error: (data) => {
                this._showError(data.message || '요약을 불러올 수 없습니다');
            },
            done: () => {
                this.loadingEl.style.display = 'none';
                const bubbles = this._parseBubbles(this.fullText, this.sources || []);
                if (bubbles.length > 0) {
                    this._streamRenderBubbles(bubbles);
                    this._initSourceLinkTooltips();
                }
                this._checkClampOverflow();
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
        if (this.bubblesEl) {
            this.bubblesEl.innerHTML = '';
            this.bubblesEl.classList.remove('expanded');
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

    // [출처N] 패턴으로 텍스트를 기업별 버블로 분리 (같은 출처는 하나의 버블로 병합)
    _parseBubbles(text, sources) {
        const parts = text.split(/\[출처(\d+)\]/);
        // parts = [text0, N0, text1, N1, ..., trailing]
        const bubbleMap = new Map(); // sourceIdx -> accumulated text (insertion-order)
        for (let i = 0; i < parts.length - 1; i += 2) {
            let bubbleText = parts[i].trim();
            const sourceIdx = parseInt(parts[i + 1], 10) - 1;
            if (!bubbleText || sourceIdx < 0 || sourceIdx >= sources.length) continue;
            // AI가 [회사명]에서는 형태로 생성할 경우 대괄호만 제거하고 이름은 유지
            bubbleText = bubbleText.replace(/^\[([^\]]+)\]/, '$1').trim();
            if (bubbleMap.has(sourceIdx)) {
                bubbleMap.set(sourceIdx, bubbleMap.get(sourceIdx) + '\n' + bubbleText);
            } else {
                bubbleMap.set(sourceIdx, bubbleText);
            }
        }
        const bubbles = [];
        for (const [sourceIdx, t] of bubbleMap) {
            bubbles.push({ text: t, source: sources[sourceIdx] });
        }
        // 아직 닫는 [출처N] 태그가 도착하지 않은, 현재 스트리밍 중인 마지막 블록도
        // 다음 순번 출처로 가정하고 임시로 보여줌 (실시간 타이핑 효과)
        const pendingText = parts[parts.length - 1].trim().replace(/^\[([^\]]+)\]/, '$1').trim();
        const nextIdx = bubbles.length;
        if (pendingText && nextIdx < sources.length && !bubbleMap.has(nextIdx)) {
            bubbles.push({ text: pendingText, source: sources[nextIdx] });
        }
        return bubbles.slice(0, 5);
    }

    // 다음 프레임에 한 번만 렌더링하도록 묶어서(coalesce) 토큰 도착마다 리플로우가 발생하지 않게 함
    _scheduleStreamRender() {
        if (this._rafScheduled || !this.sources) return;
        this._rafScheduled = true;
        requestAnimationFrame(() => {
            this._rafScheduled = false;
            const bubbles = this._parseBubbles(this.fullText, this.sources);
            if (bubbles.length === 0) return;
            if (this.loadingEl) this.loadingEl.style.display = 'none';
            this._streamRenderBubbles(bubbles);
            this._checkClampOverflow();
        });
    }

    // 이미 그려진 버블은 텍스트만 갱신하고, 새로 등장한 버블만 DOM에 추가(진입 애니메이션은 최초 1회만 재생)
    _streamRenderBubbles(bubbles) {
        if (!this.bubblesEl) return;
        bubbles.forEach((bubble, idx) => {
            const { text, source } = bubble;
            let el = this.bubblesEl.children[idx];
            if (!el) {
                const corpName = source.corporationName || source.title || '';
                const logoHtml = source.logoUrl
                    ? `<img class="bubble-logo" src="${this._escapeHtml(source.logoUrl)}" alt="${this._escapeHtml(corpName)}">`
                    : `<div class="bubble-logo-fallback">${this._escapeHtml((corpName || '?')[0])}</div>`;

                el = document.createElement('div');
                el.className = 'company-bubble';
                el.innerHTML = `
                    <div class="bubble-speaker">
                        ${logoHtml}
                        <span class="bubble-corp-name">${this._escapeHtml(corpName)}</span>
                    </div>
                    <div class="bubble-body">
                        <div class="bubble-content">
                            <div class="bubble-content-text"></div>
                            <div class="bubble-source-row">
                                <a href="/articles/${source.id}" target="_blank" rel="noopener" class="bubble-source-link" data-title="${this._escapeHtml(source.title || '')}" data-thumbnail="${this._escapeHtml(source.thumbnailImage || '')}">${this._escapeHtml(source.title || '원문 읽기')} →</a>
                            </div>
                        </div>
                    </div>
                `;
                this.bubblesEl.appendChild(el);
            }
            const textEl = el.querySelector('.bubble-content-text');
            if (textEl) textEl.innerHTML = this._textToHtml(text);
        });
    }

    _initSourceLinkTooltips() {
        const tooltip = this._getOrCreateTooltip();
        this.bubblesEl.querySelectorAll('.bubble-source-link').forEach(link => {
            link.addEventListener('mouseenter', (e) => {
                const title = link.dataset.title;
                const thumbnail = link.dataset.thumbnail;
                if (!title && !thumbnail) return;
                tooltip.innerHTML = thumbnail
                    ? `<img class="source-tooltip-thumb" src="${thumbnail}" alt=""><span class="source-tooltip-title">${this._escapeHtml(title)}</span>`
                    : `<span class="source-tooltip-title">${this._escapeHtml(title)}</span>`;
                tooltip.style.display = 'block';
                this._positionTooltip(tooltip, link);
            });
            link.addEventListener('mouseleave', () => {
                tooltip.style.display = 'none';
            });
        });
    }

    _getOrCreateTooltip() {
        let tooltip = document.getElementById('bubbleSourceTooltip');
        if (!tooltip) {
            tooltip = document.createElement('div');
            tooltip.id = 'bubbleSourceTooltip';
            tooltip.className = 'bubble-source-tooltip';
            document.body.appendChild(tooltip);
        }
        return tooltip;
    }

    _positionTooltip(tooltip, anchor) {
        tooltip.style.visibility = 'hidden';
        tooltip.style.display = 'block';
        const rect = anchor.getBoundingClientRect();
        const tw = tooltip.offsetWidth;
        const th = tooltip.offsetHeight;
        let left = rect.left + rect.width / 2 - tw / 2;
        let top = rect.top - th - 8 + window.scrollY;
        left = Math.max(8, Math.min(left, window.innerWidth - tw - 8));
        tooltip.style.left = left + 'px';
        tooltip.style.top = top + 'px';
        tooltip.style.visibility = 'visible';
    }

    // 답변 영역은 처음부터 고정 높이로 잘려 있고(clamp), 넘치는 순간 fade+더보기 버튼을 노출한다
    _checkClampOverflow() {
        const box = this.bubblesEl;
        if (!box || !this.fadeEl || !this.moreBtn || box.classList.contains('expanded')) return;
        const isOverflowing = box.scrollHeight > box.clientHeight + 1;
        this.fadeEl.style.display = isOverflowing ? 'block' : 'none';
        this.moreBtn.style.display = isOverflowing ? 'block' : 'none';
    }

    _expandClamp() {
        if (!this.bubblesEl) return;
        this.bubblesEl.classList.add('expanded');
        if (this.fadeEl) this.fadeEl.style.display = 'none';
        if (this.moreBtn) this.moreBtn.style.display = 'none';
    }

    _textToHtml(text) {
        const lines = text.split('\n').map(l => l.trim()).filter(l => l);
        const bulletLines = lines.filter(l => l.startsWith('- '));
        if (bulletLines.length > 0) {
            const items = bulletLines.map(l => `<li>${this._formatInline(l.slice(2).trim())}</li>`).join('');
            return `<ul class="bubble-list">${items}</ul>`;
        }
        return `<p class="bubble-text">${this._formatInline(text)}</p>`;
    }

    _formatInline(text) {
        return this._escapeHtml(text).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
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
