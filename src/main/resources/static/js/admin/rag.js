class AdminRagTester {
    constructor() {
        this.eventSource = null;
        this.fullText = '';
        this.sources = null;
        this._rafScheduled = false;

        this.questionInput = document.getElementById('ragQuestion');
        this.askBtn = document.getElementById('ragAskBtn');
        this.debugCard = document.getElementById('ragDebugCard');
        this.promptCard = document.getElementById('ragPromptCard');
        this.promptSystem = document.getElementById('ragPromptSystem');
        this.promptUser = document.getElementById('ragPromptUser');
        this.answerCard = document.getElementById('ragAnswerCard');
        this.answerLoading = document.getElementById('ragAnswerLoading');
        this.answerText = document.getElementById('ragAnswerText');
        this.answerNotFound = document.getElementById('ragAnswerNotFound');
        this.answerError = document.getElementById('ragAnswerError');
        this.sourcesCard = document.getElementById('ragSourcesCard');
        this.sourcesList = document.getElementById('ragSourcesList');

        this.askBtn.addEventListener('click', () => this.ask());
        this.questionInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') this.ask();
        });
    }

    ask() {
        const question = this.questionInput.value.trim();
        if (!question) return;

        this.reset();
        this.answerCard.style.display = 'block';
        this.answerLoading.style.display = 'block';
        this.askBtn.disabled = true;

        const params = new URLSearchParams({
            q: question,
            topArticles: document.getElementById('ragTopArticles').value || '5',
            chunksPerArticle: document.getElementById('ragChunksPerArticle').value || '3',
            threshold: document.getElementById('ragThreshold').value || '0.6'
        });
        this.eventSource = new EventSource('/api/admin/rag/answer?' + params.toString());

        // preprocess: 전처리 결과(추출 기업/매칭 ID/키워드/벡터 쿼리)를 디버그 패널에 표시
        this.eventSource.addEventListener('preprocess', (e) => {
            try {
                this.renderDebug(JSON.parse(e.data));
            } catch (_) {}
        });

        // prompt: Gemini에 전송되는 입력 프롬프트 원문(시스템/유저 메시지)을 그대로 표시
        this.eventSource.addEventListener('prompt', (e) => {
            try {
                const prompt = JSON.parse(e.data);
                this.promptSystem.textContent = prompt.systemPrompt || '';
                this.promptUser.textContent = prompt.userMessage || '';
                this.promptCard.style.display = 'block';
            } catch (_) {}
        });

        this.eventSource.addEventListener('sources', (e) => {
            try {
                this.sources = JSON.parse(e.data);
                this.renderSources();
            } catch (_) {
                this.sources = [];
            }
        });

        this.eventSource.addEventListener('token', (e) => {
            this.answerLoading.style.display = 'none';
            this.fullText += JSON.parse(e.data);
            this._scheduleRender();
        });

        this.eventSource.addEventListener('done', (e) => {
            try {
                const done = JSON.parse(e.data);
                this.renderTokens(done);
            } catch (_) {}
            this.close();
        });

        this.eventSource.addEventListener('notfound', (e) => {
            let message = '결과가 없습니다';
            try {
                message = JSON.parse(e.data).message || message;
            } catch (_) {}
            this.answerLoading.style.display = 'none';
            this.answerNotFound.textContent = message;
            this.answerNotFound.style.display = 'block';
            this.close();
        });

        this.eventSource.addEventListener('error', (e) => {
            // EventSource 네이티브 error(연결 종료)와 서버 error 이벤트 모두 여기로 옴
            if (this.eventSource && this.eventSource.readyState === EventSource.CLOSED
                && this.fullText) {
                this.close();
                return;
            }
            let message = '답변을 불러올 수 없습니다';
            try {
                if (e.data) message = JSON.parse(e.data).message || message;
            } catch (_) {}
            if (!this.fullText && this.answerNotFound.style.display === 'none') {
                this.answerLoading.style.display = 'none';
                this.answerError.textContent = message;
                this.answerError.style.display = 'block';
            }
            this.close();
        });
    }

    renderDebug(pre) {
        this.debugCard.style.display = 'block';
        const raw = pre.rawCorporations || [];
        const matchedNames = pre.matchedCorporationNames || [];
        const matchedIds = pre.matchedCorporationIds || [];
        document.getElementById('ragDebugCorporations').textContent =
            raw.length ? raw.join(', ') : '(기업 미지목 — 전체 검색)';
        document.getElementById('ragDebugMatched').textContent =
            matchedIds.length
                ? matchedNames.map((n, i) => `${n} (id=${matchedIds[i]})`).join(', ')
                : (raw.length ? '매칭 실패' : '-');
        document.getElementById('ragDebugKeywords').textContent = pre.keywords || '-';
        document.getElementById('ragDebugVectorQuery').textContent = pre.vectorQuery || '-';
        if (pre.totalTokens) {
            document.getElementById('ragDebugTokens').textContent =
                `전처리: 입력 ${pre.inputTokens ?? '-'} / 출력 ${pre.outputTokens ?? '-'} / 합계 ${pre.totalTokens}`;
        }
    }

    renderTokens(done) {
        if (!done.totalTokens) return;
        document.getElementById('ragDebugTokens').textContent =
            `전처리+생성 합계: 입력 ${done.inputTokens ?? '-'} / 출력 ${done.outputTokens ?? '-'} / 합계 ${done.totalTokens}`;
    }

    renderSources() {
        if (!this.sources || !this.sources.length) return;
        this.sourcesCard.style.display = 'block';

        const sanitizeUrl = (url) => {
            const u = String(url || '').trim();
            if (!u) return '';
            if (u.startsWith('/') || /^https?:\/\//i.test(u)) return this._escapeHtml(u);
            return '';
        };

        this.sourcesList.innerHTML = this.sources.map((s, i) => {
            const safeHref = sanitizeUrl(s.url) || '#';
            const safeLogoUrl = sanitizeUrl(s.logoUrl);
            return `
            <div class="col-md-4">
                <a class="card rag-source-card text-decoration-none" href="${safeHref}" target="_blank" rel="noopener">
                    <div class="card-body">
                        <div class="rag-source-corp mb-1">
                            ${safeLogoUrl ? `<img class="rag-source-logo" src="${safeLogoUrl}" alt="">` : ''}
                            [출처${i + 1}] ${this._escapeHtml(s.corporationName || '')}
                        </div>
                        <div class="rag-source-title">${this._escapeHtml(s.title || '')}</div>
                    </div>
                </a>
            </div>`;
        }).join('');
    }

    _scheduleRender() {
        if (this._rafScheduled) return;
        this._rafScheduled = true;
        requestAnimationFrame(() => {
            this._rafScheduled = false;
            this.answerText.innerHTML = this._renderMarkdown(this.fullText);
        });
    }

    // 최소 마크다운 렌더링: 볼드, [출처N] 배지 (전체 텍스트는 escape 후 처리)
    _renderMarkdown(text) {
        let html = this._escapeHtml(text);
        html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
        html = html.replace(/\[출처(\d+)\]/g, '<span class="rag-source-ref">출처$1</span>');
        return html;
    }

    _escapeHtml(str) {
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    reset() {
        this.close();
        this.fullText = '';
        this.sources = null;
        this.answerText.innerHTML = '';
        this.answerNotFound.style.display = 'none';
        this.answerError.style.display = 'none';
        this.debugCard.style.display = 'none';
        this.promptCard.style.display = 'none';
        this.promptSystem.textContent = '';
        this.promptUser.textContent = '';
        this.sourcesCard.style.display = 'none';
        this.sourcesList.innerHTML = '';
        ['ragDebugCorporations', 'ragDebugMatched', 'ragDebugKeywords', 'ragDebugVectorQuery', 'ragDebugTokens']
            .forEach(id => document.getElementById(id).textContent = '-');
    }

    close() {
        if (this.eventSource) {
            this.eventSource.close();
            this.eventSource = null;
        }
        this.askBtn.disabled = false;
    }
}

document.addEventListener('DOMContentLoaded', () => new AdminRagTester());
