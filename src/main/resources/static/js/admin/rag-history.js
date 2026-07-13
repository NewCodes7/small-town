(function () {
    const rows = document.querySelectorAll('.rag-history-row');
    if (!rows.length) {
        return;
    }

    const modalEl = document.getElementById('ragHistoryDetailModal');
    const modal = new bootstrap.Modal(modalEl);
    const loadingEl = document.getElementById('rhDetailLoading');
    const bodyEl = document.getElementById('rhDetailBody');

    const fields = {
        id: document.getElementById('rhDetailId'),
        createdAt: document.getElementById('rhCreatedAt'),
        outcome: document.getElementById('rhOutcome'),
        model: document.getElementById('rhModel'),
        tokens: document.getElementById('rhTokens'),
        conversationId: document.getElementById('rhConversationId'),
        ipAddress: document.getElementById('rhIpAddress'),
        userId: document.getElementById('rhUserId'),
        question: document.getElementById('rhQuestion'),
        answer: document.getElementById('rhAnswer'),
        extractedCorporations: document.getElementById('rhExtractedCorporations'),
        extractedKeywords: document.getElementById('rhExtractedKeywords'),
        vectorQuery: document.getElementById('rhVectorQuery'),
        matchedCorporationIds: document.getElementById('rhMatchedCorporationIds'),
        articleCount: document.getElementById('rhArticleCount')
    };

    function fill(el, value, fallback) {
        el.textContent = (value === null || value === undefined || value === '') ? (fallback || '-') : value;
    }

    async function openDetail(id) {
        loadingEl.classList.remove('d-none');
        bodyEl.classList.add('d-none');
        modal.show();

        try {
            const res = await fetch(`/api/admin/rag/history/${id}`);
            if (!res.ok) {
                throw new Error('상세 정보를 불러오지 못했습니다.');
            }
            const data = await res.json();

            fill(fields.id, `#${data.id}`);
            fill(fields.createdAt, data.createdAt);
            fill(fields.outcome, data.outcome);
            fill(fields.model, data.model);
            fill(fields.tokens, `${data.inputTokens ?? '-'} / ${data.outputTokens ?? '-'} / ${data.totalTokens ?? '-'}`);
            fill(fields.conversationId, data.conversationId);
            fill(fields.ipAddress, data.ipAddress);
            fill(fields.userId, data.userId, '비로그인');
            fill(fields.question, data.question);
            fill(fields.answer, data.answer, '(답변 없음)');
            fill(fields.extractedCorporations, data.extractedCorporations);
            fill(fields.extractedKeywords, data.extractedKeywords);
            fill(fields.vectorQuery, data.vectorQuery);
            fill(fields.matchedCorporationIds, data.matchedCorporationIds);
            fill(fields.articleCount, data.articleCount);

            loadingEl.classList.add('d-none');
            bodyEl.classList.remove('d-none');
        } catch (error) {
            loadingEl.textContent = '상세 정보를 불러오지 못했습니다.';
            console.error(error);
        }
    }

    rows.forEach(row => {
        row.addEventListener('click', () => {
            openDetail(row.getAttribute('data-log-id'));
        });
    });
})();
