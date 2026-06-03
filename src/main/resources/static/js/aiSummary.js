class AiSummaryManager {
    constructor() {
        this.eventSource = null;
        this.card = document.getElementById('aiSummaryCard');
        this.loadingEl = document.getElementById('aiSummaryLoading');
        this.contentEl = document.getElementById('aiSummaryContent');
        this.errorEl = document.getElementById('aiSummaryError');
        this.sourcesEl = document.getElementById('aiSummarySources');
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
                `<button class="recommended-query-btn" data-query="${this._escape(q)}">${this._escape(q)}</button>`
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
        this.loadingEl.style.display = 'block';
        const recSection = document.getElementById('recommendedQueriesSection');
        if (recSection) recSection.style.display = 'none';

        this.eventSource = new EventSource('/api/search/ai-summary?q=' + encodeURIComponent(keyword));

        this.eventSource.addEventListener('token', (e) => {
            this.loadingEl.style.display = 'none';
            const text = JSON.parse(e.data);
            this.fullText += text;
            this.contentEl.textContent += text;
        });

        this.eventSource.addEventListener('done', (e) => {
            try {
                const data = JSON.parse(e.data);
                this._linkifySources(data.sources || []);
                this._renderRelatedQueries(data.queries || []);
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
        if (this.contentEl) this.contentEl.textContent = '';
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

    _linkifySources(sources) {
        if (!sources.length || !this.contentEl || !this.fullText) return;
        const split = this.fullText.replace(/\[(출처\d+(?:[,，]\s*출처\d+)+)\]/g, (_, inner) =>
            inner.split(/[,，]\s*/).map(s => `[${s.trim()}]`).join('')
        );
        const normalized = split.replace(/(\[출처\d+\])\./g, '.$1');
        const html = this._escape(normalized)
            .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
            .replace(
            /\[출처(\d+)\]/g,
            (match, numStr) => {
                const idx = parseInt(numStr, 10) - 1;
                const s = sources[idx];
                if (!s) return '';
                const inlineLogo = s.logoUrl
                    ? `<img src="${this._escape(s.logoUrl)}" alt="${this._escape(s.title)}" class="source-inline-logo">`
                    : `<span class="source-inline-num">${numStr}</span>`;
                const tooltipLogo = s.logoUrl
                    ? `<img src="${this._escape(s.logoUrl)}" alt="" class="source-tooltip-logo">`
                    : '';
                return `<a href="/articles/${s.id}" class="source-ref">${inlineLogo}<span class="source-tooltip">${tooltipLogo}<span class="source-tooltip-title">${this._escape(s.title)}</span></span></a>`;
            }
        );
        this.contentEl.innerHTML = html;
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

    _escape(str) {
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }
}

document.addEventListener('DOMContentLoaded', () => {
    if (document.getElementById('aiSummaryCard')) {
        window.aiSummaryManager = new AiSummaryManager();
    }
});
