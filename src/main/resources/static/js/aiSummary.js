class AiSummaryManager {
    constructor() {
        this.eventSource = null;
        this.card = document.getElementById('aiSummaryCard');
        this.loadingEl = document.getElementById('aiSummaryLoading');
        this.contentEl = document.getElementById('aiSummaryContent');
        this.errorEl = document.getElementById('aiSummaryError');
        this.sourcesEl = document.getElementById('aiSummarySources');
        this.relatedQueriesEl = document.getElementById('aiSummaryRelatedQueries');
        this.loadRecommendedQueries();
    }

    async loadRecommendedQueries() {
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

        this.eventSource = new EventSource('/api/search/ai-summary?q=' + encodeURIComponent(keyword));

        this.eventSource.addEventListener('token', (e) => {
            this.loadingEl.style.display = 'none';
            this.contentEl.textContent += e.data;
        });

        this.eventSource.addEventListener('done', (e) => {
            try {
                const data = JSON.parse(e.data);
                this._renderSources(data.sources || []);
                this._renderRelatedQueries(data.queries || []);
            } catch (_) {}
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
        if (this.contentEl) this.contentEl.textContent = '';
        if (this.errorEl) { this.errorEl.textContent = ''; this.errorEl.style.display = 'none'; }
        if (this.sourcesEl) { this.sourcesEl.innerHTML = ''; this.sourcesEl.style.display = 'none'; }
        if (this.relatedQueriesEl) { this.relatedQueriesEl.innerHTML = ''; this.relatedQueriesEl.style.display = 'none'; }
        if (this.loadingEl) this.loadingEl.style.display = 'none';
    }

    _close() {
        if (this.eventSource) {
            this.eventSource.close();
            this.eventSource = null;
        }
    }

    _renderSources(sources) {
        if (!sources.length || !this.sourcesEl) return;
        const label = document.createElement('span');
        label.className = 'ai-sources-label';
        label.textContent = '출처';
        this.sourcesEl.appendChild(label);
        sources.forEach(s => {
            const a = document.createElement('a');
            a.href = s.url;
            a.target = '_blank';
            a.rel = 'noopener noreferrer';
            a.className = 'source-badge';
            if (s.logoUrl) {
                const img = document.createElement('img');
                img.src = s.logoUrl;
                img.alt = s.title;
                img.className = 'source-badge-logo';
                a.appendChild(img);
            }
            const span = document.createElement('span');
            span.textContent = s.title;
            a.appendChild(span);
            this.sourcesEl.appendChild(a);
        });
        this.sourcesEl.style.display = 'flex';
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
