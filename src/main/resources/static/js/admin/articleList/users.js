function formatDateTime(dateString) {
    if (!dateString) return '-';
    const date = new Date(dateString);
    return date.toLocaleString('ko-KR', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
    });
}

// ============================================
// 배치 본문 추출
// ============================================

// 배치 본문 추출 버튼 클릭
const extractContentBatchBtn = document.getElementById('extractContentBatchBtn');
if (extractContentBatchBtn) {
    extractContentBatchBtn.addEventListener('click', function() {
        const modal = new bootstrap.Modal(document.getElementById('extractContentBatchModal'));
        modal.show();
    });
}

// 배치 본문 추출 시작 버튼 클릭
const startExtractContentBatchBtn = document.getElementById('startExtractContentBatchBtn');
if (startExtractContentBatchBtn) {
    startExtractContentBatchBtn.addEventListener('click', function() {
        const corporationIdSelect = document.getElementById('batchCorporationSelect');
        const corporationId = corporationIdSelect.value ? parseInt(corporationIdSelect.value) : null;
        const withoutContent = document.getElementById('batchWithoutContentOnly').checked;
        const limit = parseInt(document.getElementById('contentBatchLimit').value);

        if (limit < 1 || limit > 1000) {
            alert('최대 처리 개수는 1~1000 사이여야 합니다.');
            return;
        }

        if (!confirm(`배치 본문 추출을 시작하시겠습니까?\n\n설정:\n- 기업: ${corporationId ? corporationIdSelect.options[corporationIdSelect.selectedIndex].text : '전체'}\n- 본문 없는 글만: ${withoutContent ? '예' : '아니오'}\n- 최대 처리 개수: ${limit}개\n\n백그라운드에서 실행되며 서버 로그에서 진행 상황을 확인할 수 있습니다.`)) {
            return;
        }

        startExtractContentBatch(corporationId, withoutContent, limit);
    });
}

// 배치 본문 추출 시작
function startExtractContentBatch(corporationId, withoutContent, limit) {
    const requestBody = {
        withoutContent: withoutContent,
        limit: limit
    };

    if (corporationId) {
        requestBody.corporationId = corporationId;
    }

    fetch('/admin/articles/extract-content-batch', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(requestBody)
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert(data.message);
            bootstrap.Modal.getInstance(document.getElementById('extractContentBatchModal')).hide();
        } else {
            alert('배치 본문 추출 시작 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('배치 본문 추출 시작 오류:', error);
        alert('배치 본문 추출 시작 중 오류가 발생했습니다.');
    });
}

// ============================================
// Embedding 정보 조회 및 표시
// ============================================

// 페이지 로드 시 모든 Article의 embedding 상태 조회 (articles 탭에서만)
if (window.location.search.includes('tab=articles') || !window.location.search.includes('tab=')) {
    loadAllArticleEmbeddingStatus();
}

// 모든 Article의 embedding 상태 조회
function loadAllArticleEmbeddingStatus() {
    const embeddingStatusCells = document.querySelectorAll('.embedding-status-cell');

    embeddingStatusCells.forEach(cell => {
        const articleId = cell.dataset.articleId;
        loadArticleEmbeddingStatus(articleId, cell);
    });
}

// 개별 Article의 embedding 상태 조회
function loadArticleEmbeddingStatus(articleId, cellElement) {
    fetch(`/admin/articles/${articleId}/embeddings`)
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                const totalChunks = data.totalChunks;
                const chunksWithEmbedding = data.chunksWithEmbedding;
                const coverage = data.embeddingCoverage;

                let badgeClass = 'bg-secondary';
                let icon = 'fa-question-circle';
                let text = '없음';

                if (totalChunks === 0) {
                    badgeClass = 'bg-secondary';
                    icon = 'fa-minus-circle';
                    text = '청크 없음';
                } else if (chunksWithEmbedding === 0) {
                    badgeClass = 'bg-danger';
                    icon = 'fa-times-circle';
                    text = '미완료';
                } else if (chunksWithEmbedding < totalChunks) {
                    badgeClass = 'bg-warning';
                    icon = 'fa-exclamation-circle';
                    text = `${chunksWithEmbedding}/${totalChunks}`;
                } else {
                    badgeClass = 'bg-success';
                    icon = 'fa-check-circle';
                    text = `완료 (${totalChunks})`;
                }

                cellElement.innerHTML = `
                    <span class="badge ${badgeClass}" title="${coverage.toFixed(1)}% 완료">
                        <i class="fas ${icon} me-1"></i>${text}
                    </span>
                `;
            } else {
                cellElement.innerHTML = `
                    <span class="badge bg-secondary">
                        <i class="fas fa-question-circle"></i> 확인 실패
                    </span>
                `;
            }
        })
        .catch(error => {
            console.error(`Article ${articleId} embedding 상태 조회 오류:`, error);
            cellElement.innerHTML = `
                <span class="badge bg-secondary">
                    <i class="fas fa-exclamation-triangle"></i> 오류
                </span>
            `;
        });
}

// Embedding 상세 보기 버튼 클릭
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('view-embeddings-btn') || e.target.closest('.view-embeddings-btn')) {
        const btn = e.target.classList.contains('view-embeddings-btn') ? e.target : e.target.closest('.view-embeddings-btn');
        const articleId = btn.dataset.articleId;
        const articleTitle = btn.dataset.articleTitle;

        viewArticleEmbeddings(articleId, articleTitle);
    }
});

// Article의 embedding 상세 정보 보기
function viewArticleEmbeddings(articleId, articleTitle) {
    document.getElementById('embeddingModalArticleTitle').textContent = articleTitle;
    document.getElementById('embeddingLoadingIndicator').style.display = 'block';
    document.getElementById('embeddingDetailContent').style.display = 'none';

    const modal = new bootstrap.Modal(document.getElementById('embeddingDetailModal'));
    modal.show();

    fetch(`/admin/articles/${articleId}/embeddings`)
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                displayEmbeddingDetails(data);
            } else {
                document.getElementById('embeddingLoadingIndicator').innerHTML = `
                    <div class="alert alert-danger">
                        <i class="fas fa-exclamation-circle"></i> ${data.message}
                    </div>
                `;
            }
        })
        .catch(error => {
            console.error('Embedding 정보 조회 오류:', error);
            document.getElementById('embeddingLoadingIndicator').innerHTML = `
                <div class="alert alert-danger">
                    <i class="fas fa-exclamation-circle"></i> 임베딩 정보를 불러오는 중 오류가 발생했습니다.
                </div>
            `;
        });
}

// Embedding 상세 정보 표시
function displayEmbeddingDetails(data) {
    document.getElementById('embeddingLoadingIndicator').style.display = 'none';
    document.getElementById('embeddingDetailContent').style.display = 'block';

    // 통계 표시
    document.getElementById('totalChunksCount').textContent = data.totalChunks;
    document.getElementById('chunksWithEmbeddingCount').textContent = data.chunksWithEmbedding;
    document.getElementById('chunksWithoutEmbeddingCount').textContent = data.chunksWithoutEmbedding;
    document.getElementById('embeddingCoverage').textContent = data.embeddingCoverage.toFixed(1);

    // 청크 목록 표시
    const tbody = document.getElementById('chunkTableBody');

    if (data.chunks.length === 0) {
        tbody.innerHTML = `
            <tr>
                <td colspan="6" class="text-center text-muted py-4">
                    청크가 없습니다. 먼저 본문을 청크로 분할해야 합니다.
                </td>
            </tr>
        `;
        return;
    }

    tbody.innerHTML = data.chunks.map(chunk => {
        const statusBadge = chunk.hasEmbedding
            ? `<span class="badge bg-success"><i class="fas fa-check-circle"></i> 완료</span>`
            : `<span class="badge bg-warning"><i class="fas fa-times-circle"></i> 없음</span>`;

        const embeddingPreview = chunk.hasEmbedding && chunk.embeddingPreview
            ? `<small class="text-muted d-block mt-1">[${chunk.embeddingPreview.slice(0, 5).map(v => v.toFixed(4)).join(', ')}...]</small>`
            : '';

        const viewVectorBtn = chunk.hasEmbedding
            ? `<button class="btn btn-sm btn-info view-embedding-vector-btn" data-chunk-id="${chunk.id}" data-chunk-index="${chunk.chunkIndex}">
                   <i class="fas fa-chart-line"></i> 벡터 보기
               </button>`
            : '';

        return `
            <tr>
                <td><strong>${chunk.chunkIndex}</strong></td>
                <td>
                    <div style="white-space: pre-wrap; word-break: break-word;">
                        ${escapeHtml(chunk.content)}
                    </div>
                </td>
                <td>${chunk.contentLength}자</td>
                <td>
                    ${statusBadge}
                    <br>
                    <small class="text-muted">${chunk.embeddingDimension}차원</small>
                    ${embeddingPreview}
                </td>
                <td>
                    <small>${chunk.embeddingGeneratedAt ? formatDateTime(chunk.embeddingGeneratedAt) : '-'}</small>
                </td>
                <td>
                    ${viewVectorBtn}
                </td>
            </tr>
        `;
    }).join('');
}

// Embedding 벡터 상세 보기 버튼 클릭
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('view-embedding-vector-btn') || e.target.closest('.view-embedding-vector-btn')) {
        const btn = e.target.classList.contains('view-embedding-vector-btn') ? e.target : e.target.closest('.view-embedding-vector-btn');
        const chunkId = btn.dataset.chunkId;
        const chunkIndex = btn.dataset.chunkIndex;

        viewChunkEmbeddingVector(chunkId, chunkIndex);
    }
});

// Chunk의 전체 embedding 벡터 보기
function viewChunkEmbeddingVector(chunkId, chunkIndex) {
    document.getElementById('chunkEmbeddingModalTitle').textContent = `Chunk #${chunkIndex}`;
    document.getElementById('embeddingVectorDisplay').textContent = '로딩 중...';
    document.getElementById('embeddingDimensionInfo').textContent = '0';

    const modal = new bootstrap.Modal(document.getElementById('chunkEmbeddingVectorModal'));
    modal.show();

    fetch(`/admin/chunks/${chunkId}/embedding`)
        .then(response => response.json())
        .then(data => {
            if (data.success && data.hasEmbedding) {
                document.getElementById('embeddingDimensionInfo').textContent = data.embeddingDimension;

                // 벡터를 보기 좋게 포맷팅 (10개씩 한 줄에)
                const embedding = data.embedding;
                let formatted = '';
                for (let i = 0; i < embedding.length; i += 10) {
                    const slice = embedding.slice(i, Math.min(i + 10, embedding.length));
                    formatted += `[${i.toString().padStart(4, ' ')}] ` +
                                 slice.map(v => v.toFixed(6).padStart(10, ' ')).join(', ') + '\n';
                }

                document.getElementById('embeddingVectorDisplay').textContent = formatted;
            } else {
                document.getElementById('embeddingVectorDisplay').textContent = '임베딩 데이터가 없습니다.';
            }
        })
        .catch(error => {
            console.error('Embedding 벡터 조회 오류:', error);
            document.getElementById('embeddingVectorDisplay').textContent = '오류 발생: ' + error.message;
        });
}
// ===== 회원 상세 정보 모달 =====

// 회원 행 클릭 이벤트
document.addEventListener('click', function(e) {
    const userRow = e.target.closest('.user-row');
    if (userRow) {
        const userId = userRow.dataset.userId;
        showUserDetailModal(userId);
    }
});

// 회원 상세 정보 모달 표시
function showUserDetailModal(userId) {
    const modal = new bootstrap.Modal(document.getElementById('userDetailModal'));
    
    // 로딩 표시
    document.getElementById('userDetailLoading').style.display = 'block';
    document.getElementById('userDetailContent').style.display = 'none';
    
    modal.show();
    
    // API 호출하여 회원 정보 가져오기
    fetch(`/admin/users/${userId}`)
        .then(response => {
            if (!response.ok) {
                throw new Error('회원 정보를 불러오는데 실패했습니다.');
            }
            return response.json();
        })
        .then(data => {
            populateUserDetailModal(data);
            
            // 로딩 숨기고 내용 표시
            document.getElementById('userDetailLoading').style.display = 'none';
            document.getElementById('userDetailContent').style.display = 'block';
        })
        .catch(error => {
            console.error('회원 정보 조회 오류:', error);
            document.getElementById('userDetailLoading').innerHTML = `
                <div class="alert alert-danger">
                    <i class="fas fa-exclamation-circle"></i>
                    ${error.message}
                </div>
            `;
        });
}

// 회원 정보로 모달 채우기
function populateUserDetailModal(user) {
    // 기본 정보
    document.getElementById('userDetailNickname').textContent = user.nickname || '-';
    document.getElementById('userDetailEmail').textContent = user.email || '-';
    
    // 프로필 이미지
    const profileImg = document.getElementById('userDetailProfileImage');
    if (user.profileImageUrl) {
        profileImg.src = user.profileImageUrl;
        profileImg.style.display = 'block';
    } else {
        profileImg.src = 'https://via.placeholder.com/120?text=No+Image';
        profileImg.style.display = 'block';
    }
    
    // 상태 배지
    const statusBadge = document.getElementById('userDetailStatus');
    statusBadge.textContent = user.statusValue || user.status;
    statusBadge.className = 'badge';
    if (user.status === 'ACTIVE') {
        statusBadge.classList.add('bg-success');
    } else if (user.status === 'BANNED') {
        statusBadge.classList.add('bg-danger');
    } else {
        statusBadge.classList.add('bg-warning');
    }
    
    // 역할 배지
    const roleBadge = document.getElementById('userDetailRole');
    roleBadge.textContent = user.roleName || '-';
    roleBadge.className = 'badge';
    if (user.roleName === 'ADMIN') {
        roleBadge.classList.add('bg-danger');
    } else {
        roleBadge.classList.add('bg-info');
    }
    
    // 가입 방법
    document.getElementById('userDetailProvider').textContent = user.providerName || 'LOCAL';
    
    // 날짜 정보
    document.getElementById('userDetailCreatedAt').textContent = formatDateTime(user.createdAt) || '-';
    document.getElementById('userDetailLastLoginAt').textContent = user.lastLoginAt ? formatDateTime(user.lastLoginAt) : '로그인 기록 없음';
    
    // OAuth2 정보
    const hasOAuthInfo = user.oauthUsername || user.bio || user.company || user.location || 
                         user.blogUrl || user.profileUrl || user.publicRepos || 
                         user.followers || user.following || user.twitterUsername || 
                         user.hireable !== null;
    
    const oauthSection = document.getElementById('userOAuthSection');
    if (hasOAuthInfo) {
        oauthSection.style.display = 'block';
        
        // OAuth 사용자명
        const oauthUsername = document.getElementById('userDetailOAuthUsername');
        if (user.oauthUsername) {
            oauthUsername.innerHTML = `<a href="${user.profileUrl || '#'}" target="_blank">${user.oauthUsername}</a>`;
        } else {
            oauthUsername.textContent = '-';
        }
        
        // 프로필 URL
        const profileUrl = document.getElementById('userDetailProfileUrl');
        if (user.profileUrl) {
            profileUrl.innerHTML = `<a href="${user.profileUrl}" target="_blank">${user.profileUrl}</a>`;
        } else {
            profileUrl.textContent = '-';
        }
        
        // 자기소개
        document.getElementById('userDetailBio').textContent = user.bio || '-';
        
        // 회사
        document.getElementById('userDetailCompany').textContent = user.company || '-';
        
        // 위치
        document.getElementById('userDetailLocation').textContent = user.location || '-';
        
        // 블로그
        const blogUrl = document.getElementById('userDetailBlogUrl');
        if (user.blogUrl) {
            blogUrl.innerHTML = `<a href="${user.blogUrl.startsWith('http') ? user.blogUrl : 'https://' + user.blogUrl}" target="_blank">${user.blogUrl}</a>`;
        } else {
            blogUrl.textContent = '-';
        }
        
        // 통계
        document.getElementById('userDetailPublicRepos').textContent = user.publicRepos !== null ? user.publicRepos : '-';
        document.getElementById('userDetailFollowers').textContent = user.followers !== null ? user.followers : '-';
        document.getElementById('userDetailFollowing').textContent = user.following !== null ? user.following : '-';
        document.getElementById('userDetailHireable').textContent = user.hireable === true ? '예' : (user.hireable === false ? '아니오' : '-');
        
        // 트위터
        const twitterUsername = document.getElementById('userDetailTwitterUsername');
        if (user.twitterUsername) {
            twitterUsername.innerHTML = `<a href="https://twitter.com/${user.twitterUsername}" target="_blank">@${user.twitterUsername}</a>`;
        } else {
            twitterUsername.textContent = '-';
        }
    } else {
        oauthSection.style.display = 'none';
    }
}

// 날짜 포맷 함수
function formatDateTime(dateString) {
    if (!dateString) return '-';
    const date = new Date(dateString);
    return date.toLocaleString('ko-KR', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
    });
}
