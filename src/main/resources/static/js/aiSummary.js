class AiSummaryManager {
    constructor() {
        this.eventSource = null;
        this.card = document.getElementById('aiSummaryCard');
        this.loadingEl = document.getElementById('aiSummaryLoading');
        this.bubblesEl = document.getElementById('aiSummaryBubbles');
        this.errorEl = document.getElementById('aiSummaryError');
        this.relatedQueriesEl = document.getElementById('aiSummaryRelatedQueries');
        this.tokenUsageEl = document.getElementById('aiSummaryTokenUsage');
        this.promptBtn = document.getElementById('promptPreviewBtn');
        this.fullText = '';
        this.sources = null;
        this._rafScheduled = false;
        this.currentKeyword = null;
        this.loadRecommendedQueries();
        this._initPromptBtn();
    }

    _initPromptBtn() {
        if (!this.promptBtn) return;
        this.promptBtn.addEventListener('click', () => this._showPromptPreview());
    }

    async _showPromptPreview() {
        if (!this.currentKeyword) return;
        const modal = document.getElementById('promptPreviewModal');
        const metaEl = document.getElementById('promptModalMeta');
        const sysEl = document.getElementById('promptModalSystem');
        const userEl = document.getElementById('promptModalUser');
        if (!modal || !sysEl || !userEl) return;

        metaEl.textContent = '불러오는 중...';
        sysEl.textContent = '';
        userEl.textContent = '';
        modal.style.display = 'flex';

        try {
            const res = await fetch('/api/search/ai-summary-prompt?q=' + encodeURIComponent(this.currentKeyword));
            if (!res.ok) throw new Error('HTTP ' + res.status);
            const data = await res.json();
            metaEl.textContent = `아티클 ${data.articleCount}개 · 청크 ${data.chunkCount}개 선택됨 · 검색어: "${this.currentKeyword}"`;
            sysEl.textContent = data.systemPrompt;
            userEl.textContent = data.userMessage;
        } catch (e) {
            metaEl.textContent = '프롬프트를 불러오지 못했습니다.';
        }
    }

    async loadRecommendedQueries() {
        const hasKeyword = new URLSearchParams(window.location.search).get('keyword');
        if (hasKeyword) return;
        try {
            const res = await fetch('/api/search/recommended-queries');
            if (!res.ok) return;
            const queries = await res.json();
            const section = document.getElementById('recommendedQueriesSection');
            const list = document.getElementById('recommendedQueriesList');
            if (!section || !list || !queries.length) return;
            list.innerHTML = queries.map(q =>
                `<button class="recommended-query-btn" data-query="${this._escapeHtml(q)}">${this._escapeHtml(q)}</button>`
            ).join('');
            list.querySelectorAll('.recommended-query-btn').forEach(btn => {
                btn.addEventListener('click', () => this._triggerSearch(btn.dataset.query));
            });
            section.style.display = 'flex';
        } catch (e) {}
    }

    _triggerSearch(query) {
        const input = document.getElementById('searchInput');
        if (input) input.value = query;
        if (typeof articleManager !== 'undefined' && articleManager) {
            articleManager.handleSearch();
        }
    }

    start(keyword) {
        if (!keyword || !this.card) return;
        this.currentKeyword = keyword;
        this.reset();
        this.card.style.display = 'block';
        this.loadingEl.style.display = 'flex';
        const recSection = document.getElementById('recommendedQueriesSection');
        if (recSection) recSection.style.display = 'none';

        this.eventSource = new EventSource('/api/search/ai-summary?q=' + encodeURIComponent(keyword));

        // sources: 회사/출처 메타데이터를 먼저 받아 토큰이 도착하는 대로 버블을 실시간으로 그릴 수 있게 함
        this.eventSource.addEventListener('sources', (e) => {
            try {
                this.sources = JSON.parse(e.data);
            } catch (_) {
                this.sources = [];
            }
        });

        // token: 도착하는 즉시 화면에 반영 (rAF로 프레임당 한 번씩 묶어서 렌더링)
        this.eventSource.addEventListener('token', (e) => {
            this.fullText += JSON.parse(e.data);
            this._scheduleStreamRender();
        });

        this.eventSource.addEventListener('done', (e) => {
            try {
                const data = JSON.parse(e.data);
                const sources = this.sources || data.sources || [];
                const queries = data.queries || [];

                this.loadingEl.style.display = 'none';

                const bubbles = this._parseBubbles(this.fullText, sources);
                if (bubbles.length > 0) {
                    this._streamRenderBubbles(bubbles);
                    this._initSourceLinkTooltips();
                    if (this.promptBtn) this.promptBtn.style.display = 'inline-block';
                }
                this._renderRelatedQueries(queries);
                this._renderTokenUsage(data.inputTokens, data.outputTokens, data.totalTokens);
            } catch (_) {}
            this._close();
        });

        this.eventSource.addEventListener('hide', () => {
            this.hide();
            this._close();
        });

        this.eventSource.addEventListener('error', (e) => {
            if (e.data) {
                try {
                    const data = JSON.parse(e.data);
                    this._showError(data.message || '요약을 불러올 수 없습니다');
                } catch (_) {
                    this._showError('요약을 불러올 수 없습니다');
                }
            } else {
                this.hide();
            }
            this._close();
        });

        this.eventSource.onerror = () => {
            this.hide();
            this._close();
        };
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
        if (this.bubblesEl) this.bubblesEl.innerHTML = '';
        if (this.errorEl) { this.errorEl.textContent = ''; this.errorEl.style.display = 'none'; }
        if (this.relatedQueriesEl) { this.relatedQueriesEl.innerHTML = ''; this.relatedQueriesEl.style.display = 'none'; }
        if (this.tokenUsageEl) { this.tokenUsageEl.textContent = ''; this.tokenUsageEl.style.display = 'none'; }
        if (this.loadingEl) this.loadingEl.style.display = 'none';
        if (this.promptBtn) this.promptBtn.style.display = 'none';
    }

    _close() {
        if (this.eventSource) {
            this.eventSource.close();
            this.eventSource = null;
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

    _renderRelatedQueries(queries) {
        if (!queries.length || !this.relatedQueriesEl) return;
        const label = document.createElement('span');
        label.className = 'ai-related-label';
        label.textContent = '관련 검색어';
        this.relatedQueriesEl.appendChild(label);
        queries.forEach(q => {
            const btn = document.createElement('button');
            btn.className = 'related-query-btn';
            btn.textContent = q;
            btn.addEventListener('click', () => this._triggerSearch(q));
            this.relatedQueriesEl.appendChild(btn);
        });
        this.relatedQueriesEl.style.display = 'flex';
    }

    _renderTokenUsage(input, output, total) {
        if (!this.tokenUsageEl) return;
        if (input == null) {
            this.tokenUsageEl.textContent = '캐시';
        } else {
            this.tokenUsageEl.textContent =
                `in ${input.toLocaleString()} / out ${output.toLocaleString()} / total ${total.toLocaleString()} tokens`;
        }
        this.tokenUsageEl.style.display = 'block';
    }

    _showError(message) {
        if (!this.errorEl) return;
        this.loadingEl.style.display = 'none';
        this.errorEl.textContent = message;
        this.errorEl.style.display = 'block';
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
        window.aiSummaryManager = new AiSummaryManager();
    }
});

function closePromptModal() {
    const modal = document.getElementById('promptPreviewModal');
    if (modal) modal.style.display = 'none';
}
