// ============================================
// Embedding 관련 기능
// ============================================

// ============================================
// 본문 보기 기능
// ============================================

// 본문 보기 버튼 클릭 이벤트
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('view-content-btn') || e.target.closest('.view-content-btn')) {
        const btn = e.target.classList.contains('view-content-btn') ? e.target : e.target.closest('.view-content-btn');
        const articleId = btn.dataset.articleId;
        const articleTitle = btn.dataset.articleTitle;

        // 모달 열기
        const modal = new bootstrap.Modal(document.getElementById('viewContentModal'));

        // 제목 설정
        document.getElementById('contentArticleTitle').textContent = articleTitle;
        document.getElementById('contentInfo').textContent = '로딩 중...';
        document.getElementById('contentDisplay').innerHTML = '<div class="text-center"><i class="fas fa-spinner fa-spin"></i> 본문을 불러오는 중...</div>';
        document.getElementById('noContentMessage').style.display = 'none';

        modal.show();

        // API 호출하여 본문 가져오기
        fetch(`/admin/articles/${articleId}/content`)
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    if (data.hasContent) {
                        document.getElementById('contentDisplay').textContent = data.content;
                        document.getElementById('contentInfo').textContent = `본문 길이: ${data.contentLength.toLocaleString()}자`;
                        document.getElementById('contentDisplay').style.display = 'block';
                        document.getElementById('noContentMessage').style.display = 'none';
                    } else {
                        document.getElementById('contentDisplay').style.display = 'none';
                        document.getElementById('noContentMessage').style.display = 'block';
                        document.getElementById('contentInfo').textContent = '본문 없음';
                    }
                } else {
                    document.getElementById('contentDisplay').innerHTML = `<div class="alert alert-danger"><i class="fas fa-exclamation-circle"></i> ${data.message}</div>`;
                    document.getElementById('contentInfo').textContent = '오류 발생';
                }
            })
            .catch(error => {
                console.error('본문 조회 오류:', error);
                document.getElementById('contentDisplay').innerHTML = '<div class="alert alert-danger"><i class="fas fa-exclamation-circle"></i> 본문을 불러오는 중 오류가 발생했습니다.</div>';
                document.getElementById('contentInfo').textContent = '오류';
            });
    }
});

// ============================================
// 임베딩 배치 처리
// ============================================

// 임베딩 배치 시작 버튼 클릭
const startEmbeddingBatchBtn = document.getElementById('startEmbeddingBatchBtn');
if (startEmbeddingBatchBtn) {
    startEmbeddingBatchBtn.addEventListener('click', function() {
        // 설정 모달 표시
        const modal = new bootstrap.Modal(document.getElementById('embeddingBatchModal'));
        modal.show();
    });
}

// 임베딩 배치 실행
function startEmbeddingBatch() {
    const limit = parseInt(document.getElementById('embeddingBatchLimit').value) || 50;
    const withoutEmbedding = document.getElementById('embeddingWithoutOnly').checked;

    // 모달 닫기
    const configModal = bootstrap.Modal.getInstance(document.getElementById('embeddingBatchModal'));
    configModal.hide();

    // 진행 상황 모달 표시
    const progressModal = new bootstrap.Modal(document.getElementById('embeddingProgressModal'));
    progressModal.show();

    // 버튼 비활성화
    const btn = document.getElementById('startEmbeddingBatchBtn');
    const originalText = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = '<i class="fas fa-spinner fa-spin me-2"></i>처리 중...';

    // 진행 상황 초기화
    updateEmbeddingProgress({
        status: 'processing',
        message: '임베딩 생성을 시작합니다...'
    });

    // API 호출
    fetch('/admin/articles/generate-chunk-embeddings-batch', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({
            withoutEmbedding: withoutEmbedding,
            limit: limit
        })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            updateEmbeddingProgress({
                status: 'success',
                message: '임베딩 생성이 완료되었습니다!',
                totalArticles: data.totalArticles,
                successArticles: data.successArticles,
                failureArticles: data.failureArticles,
                totalChunksGenerated: data.totalChunksGenerated,
                errors: data.errors
            });
        } else {
            updateEmbeddingProgress({
                status: 'error',
                message: '임베딩 생성 실패: ' + (data.message || '알 수 없는 오류')
            });
        }

        // 버튼 복구
        btn.disabled = false;
        btn.innerHTML = originalText;
    })
    .catch(error => {
        console.error('Error:', error);
        updateEmbeddingProgress({
            status: 'error',
            message: '임베딩 생성 중 오류가 발생했습니다: ' + error.message
        });

        // 버튼 복구
        btn.disabled = false;
        btn.innerHTML = originalText;
    });
}

// 진행 상황 업데이트
function updateEmbeddingProgress(data) {
    const progressContent = document.getElementById('embeddingProgressContent');

    if (data.status === 'processing') {
        progressContent.innerHTML = `
            <div class="text-center">
                <div class="spinner-border text-primary mb-3" role="status">
                    <span class="visually-hidden">Loading...</span>
                </div>
                <p class="mb-0">${data.message}</p>
            </div>
        `;
    } else if (data.status === 'success') {
        let html = `
            <div class="alert alert-success">
                <i class="fas fa-check-circle me-2"></i>${data.message}
            </div>
            <div class="row">
                <div class="col-md-6">
                    <div class="card mb-3">
                        <div class="card-body">
                            <h6 class="card-title">처리 결과</h6>
                            <ul class="list-unstyled mb-0">
                                <li><strong>전체 Article:</strong> ${data.totalArticles}개</li>
                                <li><strong>성공:</strong> <span class="text-success">${data.successArticles}개</span></li>
                                <li><strong>실패:</strong> <span class="text-danger">${data.failureArticles}개</span></li>
                                <li><strong>생성된 청크:</strong> ${data.totalChunksGenerated}개</li>
                            </ul>
                        </div>
                    </div>
                </div>
            </div>
        `;

        if (data.errors && data.errors.length > 0) {
            html += `
                <div class="alert alert-warning mt-3">
                    <h6>오류 내역:</h6>
                    <ul class="mb-0" style="max-height: 200px; overflow-y: auto;">
                        ${data.errors.map(err => `<li>${err}</li>`).join('')}
                    </ul>
                </div>
            `;
        }

        progressContent.innerHTML = html;
    } else if (data.status === 'error') {
        progressContent.innerHTML = `
            <div class="alert alert-danger">
                <i class="fas fa-exclamation-triangle me-2"></i>${data.message}
            </div>
        `;
    }
}
