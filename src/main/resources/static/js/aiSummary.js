class AiSummaryManager {
    constructor() {
        this.eventSource = null;
        this.card = document.getElementById('aiSummaryCard');
        this.loadingEl = document.getElementById('aiSummaryLoading');
        this.bubblesEl = document.getElementById('aiSummaryBubbles');
        this.errorEl = document.getElementById('aiSummaryError');
        this.relatedQueriesEl = document.getElementById('aiSummaryRelatedQueries');
        this.fullText = '';
        this.loadRecommendedQueries();
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
        this.reset();
        this.card.style.display = 'block';
        this.loadingEl.style.display = 'flex';
        const recSection = document.getElementById('recommendedQueriesSection');
        if (recSection) recSection.style.display = 'none';

        this.eventSource = new EventSource('/api/search/ai-summary?q=' + encodeURIComponent(keyword));

        // token: 화면에 표시하지 않고 fullText에만 축적 (스켈레톤 유지)
        this.eventSource.addEventListener('token', (e) => {
            this.fullText += JSON.parse(e.data);
        });

        this.eventSource.addEventListener('done', (e) => {
            try {
                const data = JSON.parse(e.data);
                const sources = data.sources || [];
                const queries = data.queries || [];

                this.loadingEl.style.display = 'none';

                const bubbles = this._parseBubbles(this.fullText, sources);
                if (bubbles.length > 0) {
                    this._renderBubbles(bubbles);
                }
                this._renderRelatedQueries(queries);
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
        if (this.bubblesEl) this.bubblesEl.innerHTML = '';
        if (this.errorEl) { this.errorEl.textContent = ''; this.errorEl.style.display = 'none'; }
        if (this.relatedQueriesEl) { this.relatedQueriesEl.innerHTML = ''; this.relatedQueriesEl.style.display = 'none'; }
        if (this.loadingEl) this.loadingEl.style.display = 'none';
    }

    _close() {
        if (this.eventSource) {
            this.eventSource.close();
            this.eventSource = null;
        }
    }

    // [출처N] 패턴으로 텍스트를 기업별 버블로 분리
    _parseBubbles(text, sources) {
        const bubbles = [];
        // split으로 분리하면 [^\[] 계열 regex의 [ 경계 문제를 피할 수 있음
        const parts = text.split(/\[출처(\d+)\]/);
        // parts = [text0, N0, text1, N1, ..., trailing]
        for (let i = 0; i < parts.length - 1; i += 2) {
            let bubbleText = parts[i].trim();
            const sourceIdx = parseInt(parts[i + 1], 10) - 1;
            if (!bubbleText || sourceIdx < 0 || sourceIdx >= sources.length) continue;
            // AI가 [회사명]에서는 형태로 생성할 경우 대괄호만 제거하고 이름은 유지
            bubbleText = bubbleText.replace(/^\[([^\]]+)\]/, '$1').trim();
            bubbles.push({ text: bubbleText, source: sources[sourceIdx] });
        }
        return bubbles.slice(0, 5);
    }

    _renderBubbles(bubbles) {
        if (!this.bubblesEl) return;
        this.bubblesEl.innerHTML = '';
        bubbles.forEach((bubble, idx) => {
            const { text, source } = bubble;
            const corpName = source.corporationName || source.title || '';
            const logoHtml = source.logoUrl
                ? `<img class="bubble-logo" src="${this._escapeHtml(source.logoUrl)}" alt="${this._escapeHtml(corpName)}">`
                : `<div class="bubble-logo-fallback">${this._escapeHtml((corpName || '?')[0])}</div>`;

            const el = document.createElement('div');
            el.className = 'company-bubble';
            el.style.animationDelay = `${idx * 80}ms`;
            el.innerHTML = `
                <div class="bubble-speaker">
                    ${logoHtml}
                    <span class="bubble-corp-name">${this._escapeHtml(corpName)}</span>
                </div>
                <div class="bubble-body">
                    <div class="bubble-content">
                        <p class="bubble-text">${this._escapeHtml(text).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')}<a href="/articles/${source.id}" target="_blank" rel="noopener" class="bubble-source-link">원문 읽기 →</a></p>
                    </div>
                </div>
            `;
            this.bubblesEl.appendChild(el);
        });
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

    _showError(message) {
        if (!this.errorEl) return;
        this.loadingEl.style.display = 'none';
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
        window.aiSummaryManager = new AiSummaryManager();
    }
});
