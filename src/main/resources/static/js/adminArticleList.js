
let currentArticleId = null;
let currentArticleData = null;

// Term 통계 정렬 드롭다운 핸들러
const termSortSelect = document.getElementById('termSortSelect');
if (termSortSelect) {
    termSortSelect.addEventListener('change', function() {
        const urlParams = new URLSearchParams(window.location.search);
        urlParams.set('sort', this.value);
        urlParams.set('page', '0'); // 정렬 변경 시 첫 페이지로
        window.location.search = urlParams.toString();
    });
}

// 글 편집 버튼 클릭
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('edit-article-btn') || e.target.closest('.edit-article-btn')) {
        const btn = e.target.classList.contains('edit-article-btn') ? e.target : e.target.closest('.edit-article-btn');
        currentArticleId = btn.dataset.articleId;

        // 현재 글 정보 로드
        loadArticleForEdit(currentArticleId);
    }

    if (e.target.classList.contains('edit-summaries-btn') || e.target.closest('.edit-summaries-btn')) {
        const btn = e.target.classList.contains('edit-summaries-btn') ? e.target : e.target.closest('.edit-summaries-btn');
        currentArticleId = btn.dataset.articleId;

        // 요약 정보 로드
        loadSummariesForEdit(currentArticleId);
    }

    if (e.target.classList.contains('edit-category-btn') || e.target.closest('.edit-category-btn')) {
        const btn = e.target.classList.contains('edit-category-btn') ? e.target : e.target.closest('.edit-category-btn');
        currentArticleId = btn.dataset.articleId;
        const currentCategory = btn.dataset.currentCategory;

        // 현재 카테고리 선택
        const categorySelect = document.getElementById('categorySelect');
        categorySelect.value = currentCategory || '';

        // 커스텀 입력 초기화
        document.getElementById('customCategory').value = '';

        // 모달 표시
        const modal = new bootstrap.Modal(document.getElementById('editCategoryModal'));
        modal.show();
    }

    if (e.target.classList.contains('delete-article-btn') || e.target.closest('.delete-article-btn')) {
        const btn = e.target.classList.contains('delete-article-btn') ? e.target : e.target.closest('.delete-article-btn');
        const articleId = btn.dataset.articleId;
        const articleTitle = btn.dataset.articleTitle;

        if (confirm(`정말로 "${articleTitle}" 글을 삭제하시겠습니까?\n\n삭제된 글은 복구할 수 없습니다.`)) {
            deleteArticle(articleId);
        }
    }

    if (e.target.classList.contains('edit-publish-date-btn') || e.target.closest('.edit-publish-date-btn')) {
        const btn = e.target.classList.contains('edit-publish-date-btn') ? e.target : e.target.closest('.edit-publish-date-btn');
        currentArticleId = btn.dataset.articleId;
        const currentDate = btn.dataset.currentDate;

        // 현재 발행일을 모달의 input에 설정
        document.getElementById('publishDateInput').value = currentDate;

        // 모달 표시
        const modal = new bootstrap.Modal(document.getElementById('editPublishDateModal'));
        modal.show();
    }

    if (e.target.classList.contains('edit-translated-title-btn') || e.target.closest('.edit-translated-title-btn')) {
        const btn = e.target.classList.contains('edit-translated-title-btn') ? e.target : e.target.closest('.edit-translated-title-btn');
        currentArticleId = btn.dataset.articleId;
        const currentTranslatedTitle = btn.dataset.currentTranslatedTitle;

        // 현재 번역된 제목을 모달의 textarea에 설정
        document.getElementById('translatedTitleInput').value = currentTranslatedTitle || '';

        // 모달 표시
        const modal = new bootstrap.Modal(document.getElementById('editTranslatedTitleModal'));
        modal.show();
    }
});

// 글 정보 로드
function loadArticleForEdit(articleId) {
    fetch(`/admin/articles/${articleId}/detail`)
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                currentArticleData = data.article;

                // 폼에 데이터 채우기
                document.getElementById('articleTitle').value = data.article.title || '';
                document.getElementById('articleTranslatedTitle').value = data.article.translatedTitle || '';
                document.getElementById('articleLink').value = data.article.link || '';
                document.getElementById('articleThumbnail').value = data.article.thumbnailImage || '';
                document.getElementById('articleCategorySelect').value =
                    data.article.category ? data.article.category.name : '';

                // 썸네일 미리보기
                updateThumbnailPreview(data.article.thumbnailImage);

                // 모달 표시
                const modal = new bootstrap.Modal(document.getElementById('editArticleModal'));
                modal.show();
            } else {
                alert('글 정보 로드 실패: ' + data.message);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert('글 정보 로드 중 오류가 발생했습니다.');
        });
}

// 요약 정보 로드
function loadSummariesForEdit(articleId) {
    fetch(`/admin/articles/${articleId}/summaries`)
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                const container = document.getElementById('summariesContainer');
                container.innerHTML = '';

                // 기존 요약들 표시
                data.summaries.forEach((summary, index) => {
                    addSummaryItem(summary, index);
                });

                // 모달 표시
                const modal = new bootstrap.Modal(document.getElementById('editSummariesModal'));
                modal.show();
            } else {
                alert('요약 정보 로드 실패: ' + data.message);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert('요약 정보 로드 중 오류가 발생했습니다.');
        });
}

// 요약 항목 추가
function addSummaryItem(summary = null, index = null) {
    const container = document.getElementById('summariesContainer');
    const summaryIndex = index !== null ? index : container.children.length;

    const summaryDiv = document.createElement('div');
    summaryDiv.className = 'mb-3 p-3 border rounded';
    summaryDiv.innerHTML = `
        <div class="d-flex justify-content-between align-items-center mb-2">
            <h6 class="mb-0">요약 ${summaryIndex + 1}</h6>
            <button type="button" class="btn btn-sm btn-danger remove-summary-btn">
                <i class="fas fa-trash"></i> 삭제
            </button>
        </div>
        <div class="mb-2">
            <label class="form-label">타입</label>
            <select class="form-select summary-type">
                <option value="h3" ${summary && summary.contentType === 'h3' ? 'selected' : ''}>제목 (H3)</option>
                <option value="li" ${summary && summary.contentType === 'li' ? 'selected' : ''}>항목 (LI)</option>
            </select>
        </div>
        <div class="mb-2">
            <label class="form-label">내용</label>
            <textarea class="form-control summary-content" rows="2" maxlength="500"
                        placeholder="요약 내용을 입력하세요 (최대 500자)">${summary ? summary.content : ''}</textarea>
        </div>
        <input type="hidden" class="summary-id" value="${summary ? summary.id : ''}">
    `;

    container.appendChild(summaryDiv);
}

// 요약 항목 삭제
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('remove-summary-btn') || e.target.closest('.remove-summary-btn')) {
        const summaryDiv = e.target.closest('.mb-3.p-3.border.rounded');
        summaryDiv.remove();
    }
});

// 요약 추가 버튼
document.getElementById('addSummaryBtn').addEventListener('click', function() {
    addSummaryItem();
});

// 썸네일 미리보기 업데이트
function updateThumbnailPreview(url) {
    const preview = document.getElementById('thumbnailPreview');
    if (url && url.trim()) {
        preview.innerHTML = `<img src="${url}" style="max-width: 200px; max-height: 100px; object-fit: cover; border-radius: 4px;" />`;
    } else {
        preview.innerHTML = '';
    }
}

// 썸네일 URL 변경 시 미리보기 업데이트
document.getElementById('articleThumbnail').addEventListener('input', function() {
    updateThumbnailPreview(this.value);
});

// 글 정보 저장
document.getElementById('saveArticleBtn').addEventListener('click', function() {
    const title = document.getElementById('articleTitle').value.trim();
    const translatedTitle = document.getElementById('articleTranslatedTitle').value.trim();
    const link = document.getElementById('articleLink').value.trim();
    const thumbnailUrl = document.getElementById('articleThumbnail').value.trim();
    const categoryName = document.getElementById('articleCustomCategory').value.trim() ||
                        document.getElementById('articleCategorySelect').value;

    if (!title || !link) {
        alert('제목과 링크는 필수 입력 항목입니다.');
        return;
    }

    const requestData = {
        title: title,
        translatedTitle: translatedTitle || null,
        link: link,
        thumbnailUrl: thumbnailUrl || null,
        categoryName: categoryName || null
    };

    fetch(`/admin/articles/${currentArticleId}`, {
        method: 'PUT',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(requestData)
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            location.reload();
        } else {
            alert('글 정보 수정 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('Error:', error);
        alert('글 정보 수정 중 오류가 발생했습니다.');
    });
});

// 요약 저장
document.getElementById('saveSummariesBtn').addEventListener('click', function() {
    const summaryItems = document.querySelectorAll('#summariesContainer > div');
    const summaries = [];

    summaryItems.forEach(item => {
        const id = item.querySelector('.summary-id').value;
        const contentType = item.querySelector('.summary-type').value;
        const content = item.querySelector('.summary-content').value.trim();

        if (content) {
            summaries.push({
                id: id || null,
                contentType: contentType,
                content: content
            });
        }
    });

    fetch(`/admin/articles/${currentArticleId}/summaries`, {
        method: 'PUT',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({summaries: summaries})
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            location.reload();
        } else {
            alert('요약 수정 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('Error:', error);
        alert('요약 수정 중 오류가 발생했습니다.');
    });
});

// 발행일 저장
document.getElementById('savePublishDateBtn').addEventListener('click', function() {
    const publishDate = document.getElementById('publishDateInput').value;

    if (!publishDate) {
        alert('발행일을 입력해주세요.');
        return;
    }

    // 발행일 업데이트 API 호출
    fetch(`/api/admin/articles/${currentArticleId}/publish-date`, {
        method: 'PUT',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({
            publishedAt: publishDate
        })
    })
    .then(response => response.json())
    .then(data => {
        if (data.status === 'success') {
            alert(data.message);
            const modal = bootstrap.Modal.getInstance(document.getElementById('editPublishDateModal'));
            modal.hide();
            location.reload();
        } else {
            alert('발행일 수정 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('Error:', error);
        alert('발행일 수정 중 오류가 발생했습니다.');
    });
});

// 카테고리 저장
document.getElementById('saveCategoryBtn').addEventListener('click', function() {
    const categorySelect = document.getElementById('categorySelect');
    const customCategory = document.getElementById('customCategory');

    let categoryName = customCategory.value.trim() || categorySelect.value;

    if (!categoryName) {
        alert('카테고리를 선택하거나 입력해주세요.');
        return;
    }

    // API 호출
    fetch(`/admin/articles/${currentArticleId}/category`, {
        method: 'PUT',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({
            categoryName: categoryName
        })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            // 성공 시 페이지 새로고침
            location.reload();
        } else {
            alert('카테고리 수정 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('Error:', error);
        alert('카테고리 수정 중 오류가 발생했습니다.');
    });
});

// 커스텀 카테고리 입력 시 셀렉트 초기화
document.getElementById('customCategory').addEventListener('input', function() {
    if (this.value.trim()) {
        document.getElementById('categorySelect').value = '';
    }
});

document.getElementById('articleCustomCategory').addEventListener('input', function() {
    if (this.value.trim()) {
        document.getElementById('articleCategorySelect').value = '';
    }
});

// 셀렉트 선택 시 커스텀 입력 초기화
document.getElementById('categorySelect').addEventListener('change', function() {
    if (this.value) {
        document.getElementById('customCategory').value = '';
    }
});

document.getElementById('articleCategorySelect').addEventListener('change', function() {
    if (this.value) {
        document.getElementById('articleCustomCategory').value = '';
    }
});

// 글 삭제 함수
function deleteArticle(articleId) {
    fetch(`/api/admin/articles/${articleId}`, {
        method: 'DELETE',
        headers: {
            'Content-Type': 'application/json',
        }
    })
    .then(response => response.json())
    .then(data => {
        if (data.status === 'success') {
            alert(data.message);
            location.reload();
        } else {
            alert('글 삭제 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('Error:', error);
        alert('글 삭제 중 오류가 발생했습니다.');
    });
}

// AI 분석 버튼 클릭
document.getElementById('analyzeArticlesBtn').addEventListener('click', function() {
    if (confirm('분석되지 않은 모든 글에 대해 OpenAI 분석을 실행합니다. 이 작업은 시간이 오래 걸릴 수 있습니다. 계속하시겠습니까?')) {
        const btn = this;
        const originalText = btn.innerHTML;

        // 버튼 비활성화 및 로딩 표시
        btn.disabled = true;
        btn.innerHTML = '<i class="fas fa-spinner fa-spin me-2"></i>분석 중...';

        fetch('/api/crawling/analyze-existing', {
            method: 'GET'
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                alert(`AI 분석이 완료되었습니다.\n분석된 글: ${data.analyzedCount || 0}개`);
                location.reload();
            } else {
                alert('AI 분석 실패: ' + (data.message || '알 수 없는 오류'));
            }
            btn.disabled = false;
            btn.innerHTML = originalText;
        })
        .catch(error => {
            console.error('Error:', error);
            alert('AI 분석 중 오류가 발생했습니다.');
            btn.disabled = false;
            btn.innerHTML = originalText;
        });
    }
});

// 번역된 제목 저장
document.getElementById('saveTranslatedTitleBtn').addEventListener('click', function() {
    const translatedTitle = document.getElementById('translatedTitleInput').value.trim();

    if (!translatedTitle) {
        alert('번역된 제목을 입력해주세요.');
        return;
    }

    // 번역된 제목 업데이트 API 호출
    fetch(`/admin/articles/${currentArticleId}/translated-title`, {
        method: 'PUT',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({
            translatedTitle: translatedTitle
        })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert(data.message);
            const modal = bootstrap.Modal.getInstance(document.getElementById('editTranslatedTitleModal'));
            modal.hide();
            location.reload();
        } else {
            alert('번역된 제목 수정 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('Error:', error);
        alert('번역된 제목 수정 중 오류가 발생했습니다.');
    });
});

// 번역 버튼 클릭
document.getElementById('translateTitlesBtn').addEventListener('click', function() {
    if (confirm('해외 기업의 모든 글 제목을 번역합니다. 이 작업은 시간이 오래 걸릴 수 있습니다. 계속하시겠습니까?')) {
        const btn = this;
        const originalText = btn.innerHTML;

        // 버튼 비활성화 및 로딩 표시
        btn.disabled = true;
        btn.innerHTML = '<i class="fas fa-spinner fa-spin me-2"></i>번역 중...';

        fetch('/admin/articles/translate-titles', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                alert('번역 작업이 시작되었습니다. 로그를 확인해주세요.');
                // 5분 후 버튼 재활성화
                setTimeout(() => {
                    btn.disabled = false;
                    btn.innerHTML = originalText;
                }, 300000);
            } else {
                alert('번역 작업 시작 실패: ' + data.message);
                btn.disabled = false;
                btn.innerHTML = originalText;
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert('번역 작업 시작 중 오류가 발생했습니다.');
            btn.disabled = false;
            btn.innerHTML = originalText;
        });
    }
});

// ===== 비디오 관련 이벤트 핸들러 =====
let currentVideoId = null;

// 비디오 편집 버튼 클릭
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('edit-video-btn') || e.target.closest('.edit-video-btn')) {
        const btn = e.target.classList.contains('edit-video-btn') ? e.target : e.target.closest('.edit-video-btn');
        currentVideoId = btn.dataset.videoId;
        alert('비디오 편집 기능은 개발 중입니다.');
    }

    if (e.target.classList.contains('delete-video-btn') || e.target.closest('.delete-video-btn')) {
        const btn = e.target.classList.contains('delete-video-btn') ? e.target : e.target.closest('.delete-video-btn');
        const videoId = btn.dataset.videoId;
        const videoTitle = btn.dataset.videoTitle;

        if (confirm(`정말로 "${videoTitle}" 영상을 삭제하시겠습니까?\n\n삭제된 영상은 복구할 수 없습니다.`)) {
            deleteVideo(videoId);
        }
    }

    if (e.target.classList.contains('edit-video-translated-title-btn') || e.target.closest('.edit-video-translated-title-btn')) {
        const btn = e.target.classList.contains('edit-video-translated-title-btn') ? e.target : e.target.closest('.edit-video-translated-title-btn');
        currentVideoId = btn.dataset.videoId;
        const currentTranslatedTitle = btn.dataset.currentTranslatedTitle;

        const newTitle = prompt('번역된 제목을 입력하세요:', currentTranslatedTitle || '');

        if (newTitle !== null) {
            updateVideoTranslatedTitle(currentVideoId, newTitle);
        }
    }

    if (e.target.classList.contains('video-category-badge')) {
        currentVideoId = e.target.dataset.videoId;
        const currentCategory = e.target.textContent.trim();

        const newCategory = prompt('카테고리를 입력하세요:', currentCategory === '미분류' ? '' : currentCategory);

        if (newCategory !== null && newCategory.trim()) {
            updateVideoCategory(currentVideoId, newCategory.trim());
        }
    }
});

// 비디오 삭제 함수
function deleteVideo(videoId) {
    fetch(`/video/api/admin/videos/${videoId}`, {
        method: 'DELETE',
        headers: {
            'Content-Type': 'application/json',
        }
    })
    .then(response => response.json())
    .then(data => {
        if (data.status === 'success') {
            alert(data.message);
            location.reload();
        } else {
            alert('영상 삭제 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('Error:', error);
        alert('영상 삭제 중 오류가 발생했습니다.');
    });
}

// 비디오 번역된 제목 수정
function updateVideoTranslatedTitle(videoId, translatedTitle) {
    fetch(`/admin/videos/${videoId}/translated-title`, {
        method: 'PUT',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({
            translatedTitle: translatedTitle
        })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert(data.message);
            location.reload();
        } else {
            alert('번역된 제목 수정 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('Error:', error);
        alert('번역된 제목 수정 중 오류가 발생했습니다.');
    });
}

// 비디오 카테고리 수정
function updateVideoCategory(videoId, categoryName) {
    fetch(`/admin/videos/${videoId}/category`, {
        method: 'PUT',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({
            categoryName: categoryName
        })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert(data.message);
            location.reload();
        } else {
            alert('카테고리 수정 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('Error:', error);
        alert('카테고리 수정 중 오류가 발생했습니다.');
    });
}

// ===== Term 관련 이벤트 핸들러 =====

// Article Term 추출 실행 버튼
const extractArticleTermsBtn = document.getElementById('extractArticleTermsBtn');
if (extractArticleTermsBtn) {
    extractArticleTermsBtn.addEventListener('click', function() {
        if (confirm('모든 article의 term을 추출합니다. 이미 분석된 article은 건너뜁니다. 계속하시겠습니까?')) {
            const btn = this;
            const originalText = btn.innerHTML;

            // 버튼 비활성화 및 로딩 표시
            btn.disabled = true;
            btn.innerHTML = '<i class="fas fa-spinner fa-spin me-2"></i>Term 추출 중...';

            fetch('/admin/articles/extract-terms', {
                method: 'GET'
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    alert(data.message);
                    // 3분 후 버튼 재활성화 및 페이지 새로고침
                    setTimeout(() => {
                        btn.disabled = false;
                        btn.innerHTML = originalText;
                        location.reload();
                    }, 180000);
                } else {
                    alert('Term 추출 실패: ' + data.message);
                    btn.disabled = false;
                    btn.innerHTML = originalText;
                }
            })
            .catch(error => {
                console.error('Error:', error);
                alert('Term 추출 중 오류가 발생했습니다.');
                btn.disabled = false;
                btn.innerHTML = originalText;
            });
        }
    });
}

// Article Term 강제 재분석 버튼
const reextractArticleTermsBtn = document.getElementById('reextractArticleTermsBtn');
if (reextractArticleTermsBtn) {
    reextractArticleTermsBtn.addEventListener('click', function() {
        if (confirm('⚠️ 모든 article의 term을 강제로 재분석합니다.\n이미 분석된 article도 모두 다시 분석됩니다.\n\n시간이 오래 걸릴 수 있습니다. 계속하시겠습니까?')) {
            const btn = this;
            const originalText = btn.innerHTML;

            // 버튼 비활성화 및 로딩 표시
            btn.disabled = true;
            btn.innerHTML = '<i class="fas fa-spinner fa-spin me-2"></i>강제 재분석 중...';

            fetch('/admin/articles/reextract-all-terms', {
                method: 'GET'
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    alert('✅ ' + data.message);
                } else {
                    alert('❌ ' + data.message);
                }
                btn.disabled = false;
                btn.innerHTML = originalText;
            })
            .catch(error => {
                console.error('Error:', error);
                alert('❌ 오류가 발생했습니다: ' + error);
                btn.disabled = false;
                btn.innerHTML = originalText;
            });
        }
    });
}

// Video Term 추출 실행 버튼
const extractVideoTermsBtn = document.getElementById('extractVideoTermsBtn');
if (extractVideoTermsBtn) {
    extractVideoTermsBtn.addEventListener('click', function() {
        if (confirm('모든 video의 term을 추출합니다. 이미 분석된 video는 건너뜁니다. 계속하시겠습니까?')) {
            const btn = this;
            const originalText = btn.innerHTML;

            // 버튼 비활성화 및 로딩 표시
            btn.disabled = true;
            btn.innerHTML = '<i class="fas fa-spinner fa-spin me-2"></i>Term 추출 중...';

            fetch('/admin/videos/extract-terms', {
                method: 'GET'
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    alert(data.message);
                    // 3분 후 버튼 재활성화 및 페이지 새로고침
                    setTimeout(() => {
                        btn.disabled = false;
                        btn.innerHTML = originalText;
                        location.reload();
                    }, 180000);
                } else {
                    alert('Term 추출 실패: ' + data.message);
                    btn.disabled = false;
                    btn.innerHTML = originalText;
                }
            })
            .catch(error => {
                console.error('Error:', error);
                alert('Term 추출 중 오류가 발생했습니다.');
                btn.disabled = false;
                btn.innerHTML = originalText;
            });
        }
    });
}

// Video Term 강제 재분석 버튼
const reextractVideoTermsBtn = document.getElementById('reextractVideoTermsBtn');
if (reextractVideoTermsBtn) {
    reextractVideoTermsBtn.addEventListener('click', function() {
        if (confirm('⚠️ 모든 video의 term을 강제로 재분석합니다.\n이미 분석된 video도 모두 다시 분석됩니다.\n\n시간이 오래 걸릴 수 있습니다. 계속하시겠습니까?')) {
            const btn = this;
            const originalText = btn.innerHTML;

            // 버튼 비활성화 및 로딩 표시
            btn.disabled = true;
            btn.innerHTML = '<i class="fas fa-spinner fa-spin me-2"></i>강제 재분석 중...';

            fetch('/admin/videos/reextract-all-terms', {
                method: 'GET'
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    alert('✅ ' + data.message);
                } else {
                    alert('❌ ' + data.message);
                }
                btn.disabled = false;
                btn.innerHTML = originalText;
            })
            .catch(error => {
                console.error('Error:', error);
                alert('❌ 오류가 발생했습니다: ' + error);
                btn.disabled = false;
                btn.innerHTML = originalText;
            });
        }
    });
}

// Term 삭제 버튼 클릭 (불용어 등록)
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('delete-term-btn') || e.target.closest('.delete-term-btn')) {
        const btn = e.target.classList.contains('delete-term-btn') ? e.target : e.target.closest('.delete-term-btn');
        const termId = btn.dataset.termId;
        const term = btn.dataset.term;
        const termType = btn.dataset.termType;

        if (confirm(`"${term}" (${termType})를 불용어로 등록하시겠습니까?\n\n이 term은 모든 article에서 삭제되며, 향후 추출되지 않습니다.`)) {
            const reason = prompt('불용어 등록 사유를 입력하세요 (선택사항):', '');

            fetch(`/admin/terms/${termId}`, {
                method: 'DELETE',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    reason: reason || null
                })
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    alert(data.message);
                    location.reload();
                } else {
                    alert('Term 삭제 실패: ' + data.message);
                }
            })
            .catch(error => {
                console.error('Error:', error);
                alert('Term 삭제 중 오류가 발생했습니다.');
            });
        }
    }
});

// Term 로드 및 표시 (페이지 로드 시) - Bulk API 사용
document.addEventListener('DOMContentLoaded', function() {
    // Article Term 로드 - Bulk API로 한 번에 조회
    const termsContainers = document.querySelectorAll('.terms-container');
    if (termsContainers.length > 0) {
        // 모든 article ID 수집
        const articleIds = Array.from(termsContainers).map(container => container.dataset.articleId);

        // Bulk API 호출
        fetch(`/admin/articles/terms/bulk?articleIds=${articleIds.join(',')}`)
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    const termsByArticle = data.termsByArticle;

                    // 각 container에 해당 article의 term 표시
                    termsContainers.forEach(container => {
                        const articleId = container.dataset.articleId;
                        const terms = termsByArticle[articleId];

                        if (terms && terms.length > 0) {
                            // Term을 뱃지로 표시 (클릭 가능)
                            let html = '<div class="d-flex flex-wrap gap-1">';
                            terms.forEach((term, index) => {
                                const scorePercent = ((term.score || 0) * 100).toFixed(0);
                                const badgeClass = (term.score || 0) > 0.7 ? 'bg-danger' : (term.score || 0) > 0.4 ? 'bg-warning text-dark' : 'bg-info';
                                html += `
                                    <span class="badge ${badgeClass} term-badge-editable"
                                            style="cursor: pointer;"
                                            data-article-id="${articleId}"
                                            data-article-term-id="${term.id}"
                                            data-term-id="${term.termId}"
                                            data-term="${term.term}"
                                            data-score="${term.score || 0}"
                                            data-frequency="${term.frequency}"
                                            title="클릭하여 수정/삭제 (${term.termType})">
                                        ${term.term} <small>score: ${scorePercent}%</small>
                                    </span>
                                `;
                            });
                            html += `<button class="btn btn-sm btn-success add-term-btn" data-article-id="${articleId}">
                                        <i class="fas fa-plus"></i> Term 추가
                                        </button>`;
                            html += '</div>';
                            html += `<small class="text-muted mt-1 d-block">총 ${terms.length}개 term</small>`;

                            container.innerHTML = html;
                        } else {
                            container.innerHTML = '<span class="badge bg-secondary">Term 없음</span>';
                        }
                    });
                } else {
                    // 실패 시 각 container에 에러 표시
                    termsContainers.forEach(container => {
                        container.innerHTML = '<span class="badge bg-secondary">-</span>';
                    });
                }
            })
            .catch(error => {
                console.error('Error loading terms:', error);
                termsContainers.forEach(container => {
                    container.innerHTML = '<span class="badge bg-danger"><i class="fas fa-exclamation"></i> 오류</span>';
                });
            });
    }

    // Video Term 로드 - Bulk API로 한 번에 조회
    const videoTermsContainers = document.querySelectorAll('.video-terms-container');
    if (videoTermsContainers.length > 0) {
        // 모든 video ID 수집
        const videoIds = Array.from(videoTermsContainers).map(container => container.dataset.videoId);

        // Bulk API 호출
        fetch(`/admin/videos/terms/bulk?videoIds=${videoIds.join(',')}`)
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    const termsByVideo = data.termsByVideo;

                    // 각 container에 해당 video의 term 표시
                    videoTermsContainers.forEach(container => {
                        const videoId = container.dataset.videoId;
                        const terms = termsByVideo[videoId];

                        if (terms && terms.length > 0) {
                            let html = '<div class="d-flex flex-wrap gap-1">';
                            terms.forEach((term, index) => {
                                const badgeClass = term.frequency > 2 ? 'bg-danger' : term.frequency > 1 ? 'bg-warning text-dark' : 'bg-info';
                                html += `
                                    <span class="badge ${badgeClass}" title="${term.termType}">
                                        ${term.term} <small>x${term.frequency}</small>
                                    </span>
                                `;
                            });
                            html += '</div>';
                            html += `<small class="text-muted mt-1 d-block">총 ${terms.length}개 term</small>`;

                            container.innerHTML = html;
                        } else {
                            container.innerHTML = '<span class="badge bg-secondary">Term 없음</span>';
                        }
                    });
                } else {
                    // 실패 시 각 container에 에러 표시
                    videoTermsContainers.forEach(container => {
                        container.innerHTML = '<span class="badge bg-secondary">-</span>';
                    });
                }
            })
            .catch(error => {
                console.error('Error loading video terms:', error);
                videoTermsContainers.forEach(container => {
                    container.innerHTML = '<span class="badge bg-danger"><i class="fas fa-exclamation"></i> 오류</span>';
                });
            });
    }
});

// ===== Term 재분석 기능 =====

// Article Term 재분석 버튼 클릭
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('reanalyze-terms-btn') || e.target.closest('.reanalyze-terms-btn')) {
        const btn = e.target.classList.contains('reanalyze-terms-btn') ? e.target : e.target.closest('.reanalyze-terms-btn');
        const articleId = btn.dataset.articleId;
        const articleTitle = btn.dataset.articleTitle;

        if (confirm(`"${articleTitle}"\n\n이 게시글의 term을 재분석하시겠습니까?`)) {
            const originalHtml = btn.innerHTML;
            btn.disabled = true;
            btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i>';

            fetch(`/admin/articles/${articleId}/reanalyze-terms`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                }
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    alert(data.message);
                    // Term 목록 새로고침
                    loadTermsForArticle(articleId);
                } else {
                    alert('Term 재분석 실패: ' + data.message);
                }
                btn.disabled = false;
                btn.innerHTML = originalHtml;
            })
            .catch(error => {
                console.error('Error:', error);
                alert('Term 재분석 중 오류가 발생했습니다.');
                btn.disabled = false;
                btn.innerHTML = originalHtml;
            });
        }
    }
});

// Video Term 재분석 버튼 클릭
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('reanalyze-video-terms-btn') || e.target.closest('.reanalyze-video-terms-btn')) {
        const btn = e.target.classList.contains('reanalyze-video-terms-btn') ? e.target : e.target.closest('.reanalyze-video-terms-btn');
        const videoId = btn.dataset.videoId;
        const videoTitle = btn.dataset.videoTitle;

        if (confirm(`"${videoTitle}"\n\n이 영상의 term을 재분석하시겠습니까?`)) {
            const originalHtml = btn.innerHTML;
            btn.disabled = true;
            btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i>';

            fetch(`/admin/videos/${videoId}/reanalyze-terms`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                }
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    alert(data.message);
                    // Term 목록 새로고침
                    loadTermsForVideo(videoId);
                } else {
                    alert('Term 재분석 실패: ' + data.message);
                }
                btn.disabled = false;
                btn.innerHTML = originalHtml;
            })
            .catch(error => {
                console.error('Error:', error);
                alert('Term 재분석 중 오류가 발생했습니다.');
                btn.disabled = false;
                btn.innerHTML = originalHtml;
            });
        }
    }
});

// 특정 Article의 Term 로드
function loadTermsForArticle(articleId) {
    const container = document.querySelector(`.terms-container[data-article-id="${articleId}"]`);
    if (!container) return;

    container.innerHTML = '<div class="text-muted"><i class="fas fa-spinner fa-spin me-2"></i>로딩 중...</div>';

    fetch(`/admin/articles/${articleId}/terms`)
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                if (data.terms && data.terms.length > 0) {
                    const sortedTerms = data.terms.sort((a, b) => (b.score || 0) - (a.score || 0));

                    let html = '<div class="d-flex flex-wrap gap-1">';
                    sortedTerms.forEach((term, index) => {
                        const scorePercent = ((term.score || 0) * 100).toFixed(0);
                        const badgeClass = (term.score || 0) > 0.7 ? 'bg-danger' : (term.score || 0) > 0.4 ? 'bg-warning text-dark' : 'bg-info';
                        html += `
                            <span class="badge ${badgeClass} term-badge-editable"
                                    style="cursor: pointer;"
                                    data-article-id="${articleId}"
                                    data-article-term-id="${term.id}"
                                    data-term-id="${term.termId}"
                                    data-term="${term.term}"
                                    data-score="${term.score || 0}"
                                    data-frequency="${term.frequency}"
                                    title="클릭하여 수정/삭제 (${term.termType})">
                                ${term.term} <small>score: ${scorePercent}%</small>
                            </span>
                        `;
                    });
                    html += `<button class="btn btn-sm btn-success add-term-btn" data-article-id="${articleId}">
                                <i class="fas fa-plus"></i> Term 추가
                                </button>`;
                    html += '</div>';
                    html += `<small class="text-muted mt-1 d-block">총 ${sortedTerms.length}개 term</small>`;

                    container.innerHTML = html;
                } else {
                    container.innerHTML = '<span class="badge bg-secondary">Term 없음</span>';
                }
            } else {
                container.innerHTML = '<span class="badge bg-secondary">-</span>';
            }
        })
        .catch(error => {
            console.error('Error loading terms:', error);
            container.innerHTML = '<span class="badge bg-danger"><i class="fas fa-exclamation"></i> 오류</span>';
        });
}

// 특정 Video의 Term 로드
function loadTermsForVideo(videoId) {
    const container = document.querySelector(`.video-terms-container[data-video-id="${videoId}"]`);
    if (!container) return;

    container.innerHTML = '<div class="text-muted"><i class="fas fa-spinner fa-spin me-2"></i>로딩 중...</div>';

    fetch(`/admin/videos/${videoId}/terms`)
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                if (data.terms && data.terms.length > 0) {
                    const sortedTerms = data.terms.sort((a, b) => b.frequency - a.frequency);

                    let html = '<div class="d-flex flex-wrap gap-1">';
                    sortedTerms.forEach((term, index) => {
                        const badgeClass = term.frequency > 2 ? 'bg-danger' : term.frequency > 1 ? 'bg-warning text-dark' : 'bg-info';
                        html += `
                            <span class="badge ${badgeClass}" title="${term.termType}">
                                ${term.term} <small>x${term.frequency}</small>
                            </span>
                        `;
                    });
                    html += '</div>';
                    html += `<small class="text-muted mt-1 d-block">총 ${sortedTerms.length}개 term</small>`;

                    container.innerHTML = html;
                } else {
                    container.innerHTML = '<span class="badge bg-secondary">Term 없음</span>';
                }
            } else {
                container.innerHTML = '<span class="badge bg-secondary">-</span>';
            }
        })
        .catch(error => {
            console.error('Error loading video terms:', error);
            container.innerHTML = '<span class="badge bg-danger"><i class="fas fa-exclamation"></i> 오류</span>';
        });
}

// ===== 사용자 사전 관리 기능 =====

// 사용자 사전 관리 버튼 클릭
const manageUserDictionaryBtn = document.getElementById('manageUserDictionaryBtn');
if (manageUserDictionaryBtn) {
    manageUserDictionaryBtn.addEventListener('click', function() {
        loadUserDictionary();
        const modal = new bootstrap.Modal(document.getElementById('userDictionaryModal'));
        modal.show();
    });
}

// 사용자 사전 로드
function loadUserDictionary() {
    const listContainer = document.getElementById('userDictionaryList');
    listContainer.innerHTML = '<div class="text-center text-muted py-3"><i class="fas fa-spinner fa-spin me-2"></i>로딩 중...</div>';

    fetch('/admin/user-dictionary')
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                document.getElementById('userDictCount').textContent = data.totalCount;

                if (data.userDictionaries && data.userDictionaries.length > 0) {
                    let html = '<table class="table table-sm table-hover">';
                    html += '<thead class="table-light">';
                    html += '<tr><th>단어</th><th>품사</th><th>사유</th><th>등록일</th><th>액션</th></tr>';
                    html += '</thead><tbody>';

                    data.userDictionaries.forEach(dict => {
                        const createdAt = new Date(dict.createdAt).toLocaleString('ko-KR');
                        html += `
                            <tr>
                                <td><strong>${dict.word}</strong></td>
                                <td><span class="badge bg-secondary">${dict.posTag}</span></td>
                                <td>${dict.reason || '-'}</td>
                                <td><small class="text-muted">${createdAt}</small></td>
                                <td>
                                    <button class="btn btn-sm btn-danger delete-user-word-btn"
                                            data-word-id="${dict.id}"
                                            data-word="${dict.word}">
                                        <i class="fas fa-trash"></i>
                                    </button>
                                </td>
                            </tr>
                        `;
                    });

                    html += '</tbody></table>';
                    listContainer.innerHTML = html;
                } else {
                    listContainer.innerHTML = '<div class="text-center text-muted py-3">등록된 단어가 없습니다.</div>';
                }
            } else {
                listContainer.innerHTML = '<div class="alert alert-danger">오류: ' + data.message + '</div>';
            }
        })
        .catch(error => {
            console.error('Error:', error);
            listContainer.innerHTML = '<div class="alert alert-danger">사용자 사전 로드 중 오류가 발생했습니다.</div>';
        });
}

// 사용자 단어 추가 폼 제출
document.getElementById('addUserWordForm').addEventListener('submit', function(e) {
    e.preventDefault();

    const word = document.getElementById('userWord').value.trim();
    const posTag = document.getElementById('userPosTag').value;
    const reason = document.getElementById('userReason').value.trim();

    if (!word) {
        alert('단어를 입력해주세요.');
        return;
    }

    fetch('/admin/user-dictionary', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({
            word: word,
            posTag: posTag,
            reason: reason || null
        })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert(data.message);
            // 폼 초기화
            document.getElementById('userWord').value = '';
            document.getElementById('userPosTag').value = 'NNG';
            document.getElementById('userReason').value = '';
            // 목록 새로고침
            loadUserDictionary();
        } else {
            alert('단어 추가 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('Error:', error);
        alert('단어 추가 중 오류가 발생했습니다.');
    });
});

// 사용자 단어 삭제 버튼 클릭 (이벤트 위임)
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('delete-user-word-btn') || e.target.closest('.delete-user-word-btn')) {
        const btn = e.target.classList.contains('delete-user-word-btn') ? e.target : e.target.closest('.delete-user-word-btn');
        const wordId = btn.dataset.wordId;
        const word = btn.dataset.word;

        if (confirm(`"${word}" 단어를 삭제하시겠습니까?\n\n애플리케이션을 재시작하면 형태소 분석에서 제외됩니다.`)) {
            fetch(`/admin/user-dictionary/${wordId}`, {
                method: 'DELETE',
                headers: {
                    'Content-Type': 'application/json',
                }
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    alert(data.message);
                    loadUserDictionary();
                } else {
                    alert('단어 삭제 실패: ' + data.message);
                }
            })
            .catch(error => {
                console.error('Error:', error);
                alert('단어 삭제 중 오류가 발생했습니다.');
            });
        }
    }
});

// ========== 유의어 관리 ==========

// 유의어 목록 로드
function loadSynonyms() {
    fetch('/admin/term-synonyms')
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                const tbody = document.getElementById('synonymTableBody');
                if (data.synonyms.length === 0) {
                    tbody.innerHTML = '<tr><td colspan="6" class="text-center text-muted">등록된 유의어가 없습니다.</td></tr>';
                } else {
                    tbody.innerHTML = data.synonyms.map(syn => `
                        <tr>
                            <td>${syn.id}</td>
                            <td><span class="badge bg-primary">${syn.term1}</span> <small class="text-muted">(${syn.term1Type})</small></td>
                            <td class="text-center">↔</td>
                            <td><span class="badge bg-primary">${syn.term2}</span> <small class="text-muted">(${syn.term2Type})</small></td>
                            <td>${new Date(syn.createdAt).toLocaleString()}</td>
                            <td>
                                <button class="btn btn-sm btn-danger delete-synonym-btn" data-synonym-id="${syn.id}">
                                    <i class="fas fa-trash"></i> 삭제
                                </button>
                            </td>
                        </tr>
                    `).join('');
                }
            } else {
                console.error('유의어 목록 로드 실패:', data.message);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            document.getElementById('synonymTableBody').innerHTML =
                '<tr><td colspan="6" class="text-center text-danger">로드 중 오류가 발생했습니다.</td></tr>';
        });
}

// 페이지가 synonyms 탭일 때 유의어 목록 로드
const currentTab = new URLSearchParams(window.location.search).get('tab');
if (currentTab === 'synonyms') {
    loadSynonyms();
}

// Term 검색 함수
function searchTerms(query, selectId) {
    if (query.length < 1) {
        return;
    }

    fetch(`/admin/terms/search?q=${encodeURIComponent(query)}`)
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                const select = document.getElementById(selectId);
                select.innerHTML = data.terms.map(term =>
                    `<option value="${term.id}">${term.term} (${term.termType})</option>`
                ).join('');
            }
        })
        .catch(error => console.error('Error:', error));
}

// Term 검색 입력 이벤트
const term1Search = document.getElementById('term1Search');
const term2Search = document.getElementById('term2Search');

if (term1Search) {
    term1Search.addEventListener('input', function() {
        searchTerms(this.value, 'term1Select');
    });
}

if (term2Search) {
    term2Search.addEventListener('input', function() {
        searchTerms(this.value, 'term2Select');
    });
}

// 유의어 저장
const saveSynonymBtn = document.getElementById('saveSynonymBtn');
if (saveSynonymBtn) {
    saveSynonymBtn.addEventListener('click', function() {
        const term1Id = document.getElementById('term1Select').value;
        const term2Id = document.getElementById('term2Select').value;

        if (!term1Id || !term2Id) {
            alert('두 개의 term을 모두 선택해주세요.');
            return;
        }

        fetch('/admin/term-synonyms', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                termId1: parseInt(term1Id),
                termId2: parseInt(term2Id)
            })
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                alert(data.message);
                bootstrap.Modal.getInstance(document.getElementById('addSynonymModal')).hide();
                loadSynonyms();

                // 입력 초기화
                document.getElementById('term1Search').value = '';
                document.getElementById('term2Search').value = '';
                document.getElementById('term1Select').innerHTML = '<option value="">검색 결과가 여기에 표시됩니다</option>';
                document.getElementById('term2Select').innerHTML = '<option value="">검색 결과가 여기에 표시됩니다</option>';
            } else {
                alert('유의어 추가 실패: ' + data.message);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert('유의어 추가 중 오류가 발생했습니다.');
        });
    });
}

// 유의어 삭제
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('delete-synonym-btn') || e.target.closest('.delete-synonym-btn')) {
        const btn = e.target.classList.contains('delete-synonym-btn') ? e.target : e.target.closest('.delete-synonym-btn');
        const synonymId = btn.dataset.synonymId;

        if (confirm('이 유의어 관계를 삭제하시겠습니까?')) {
            fetch(`/admin/term-synonyms/${synonymId}`, {
                method: 'DELETE'
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    alert(data.message);
                    loadSynonyms();
                } else {
                    alert('유의어 삭제 실패: ' + data.message);
                }
            })
            .catch(error => {
                console.error('Error:', error);
                alert('유의어 삭제 중 오류가 발생했습니다.');
            });
        }
    }
});

// ========== Term 통계 탭 - 유의어 표시 ==========
let currentEditTermId = null;
let currentEditTermName = null;

// Term 통계 탭일 때 유의어 로드
if (currentTab === 'stats') {
    // 페이지 로드 후 모든 synonym-cell에 대해 유의어 로드
    document.querySelectorAll('.synonym-cell').forEach(cell => {
        const termId = parseInt(cell.dataset.termId);
        console.log('[Init] Loading synonyms for cell with termId:', termId);
        loadSynonymsForCell(termId, cell);
    });
}

// 각 셀에 대해 유의어 로드
function loadSynonymsForCell(termId, cell) {
    console.log('[loadSynonymsForCell] Loading synonyms for termId:', termId);

    fetch(`/admin/term-synonyms`)
        .then(response => response.json())
        .then(data => {
            console.log('[loadSynonymsForCell] API Response:', data);

            if (data.success) {
                // 현재 termId와 관련된 유의어 필터링
                const synonyms = data.synonyms.filter(syn => {
                    const matches = syn.term1Id == termId || syn.term2Id == termId;
                    if (matches) {
                        console.log('[loadSynonymsForCell] Found synonym for termId', termId, ':', syn);
                    }
                    return matches;
                });

                console.log('[loadSynonymsForCell] Filtered synonyms for termId', termId, ':', synonyms);

                if (synonyms.length === 0) {
                    cell.innerHTML = '<small class="text-muted">-</small>';
                } else {
                    const synonymTerms = synonyms.map(syn => {
                        const synonymTerm = syn.term1Id == termId ? syn.term2 : syn.term1;
                        return `<span class="badge bg-secondary me-1">${synonymTerm}</span>`;
                    }).join('');
                    cell.innerHTML = synonymTerms;
                }
            } else {
                console.error('[loadSynonymsForCell] API returned success:false');
                cell.innerHTML = '<small class="text-danger">로드 실패</small>';
            }
        })
        .catch(error => {
            console.error('[loadSynonymsForCell] Error:', error);
            cell.innerHTML = '<small class="text-danger">오류</small>';
        });
}

// 유의어 편집 버튼 클릭
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('edit-synonyms-btn') || e.target.closest('.edit-synonyms-btn')) {
        const btn = e.target.classList.contains('edit-synonyms-btn') ? e.target : e.target.closest('.edit-synonyms-btn');
        currentEditTermId = btn.dataset.termId;
        currentEditTermName = btn.dataset.term;

        document.getElementById('editSynonymTermName').textContent = currentEditTermName;

        // 기존 유의어 로드
        loadExistingSynonyms(currentEditTermId);

        // 추천 숨기기
        document.getElementById('recommendedSynonyms').style.display = 'none';

        // 모달 표시
        const modal = new bootstrap.Modal(document.getElementById('editSynonymsModal'));
        modal.show();
    }
});

// 기존 유의어 로드
function loadExistingSynonyms(termId) {
    fetch(`/admin/term-synonyms`)
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                const synonyms = data.synonyms.filter(syn =>
                    syn.term1Id == termId || syn.term2Id == termId
                );

                const container = document.getElementById('existingSynonymsList');
                if (synonyms.length === 0) {
                    container.innerHTML = '<small class="text-muted">등록된 유의어가 없습니다.</small>';
                } else {
                    container.innerHTML = synonyms.map(syn => {
                        const synonymTerm = syn.term1Id == termId ? syn.term2 : syn.term1;
                        return `
                            <span class="badge bg-primary me-2 mb-2">
                                ${synonymTerm}
                                <button class="btn-close btn-close-white btn-sm ms-2"
                                        onclick="deleteSynonymFromModal(${syn.id})" style="font-size: 0.6rem;"></button>
                            </span>
                        `;
                    }).join('');
                }
            }
        })
        .catch(error => {
            console.error('Error:', error);
            document.getElementById('existingSynonymsList').innerHTML =
                '<small class="text-danger">로드 중 오류가 발생했습니다.</small>';
        });
}

// 모달에서 유의어 삭제
window.deleteSynonymFromModal = function(synonymId) {
    if (confirm('이 유의어 관계를 삭제하시겠습니까?')) {
        fetch(`/admin/term-synonyms/${synonymId}`, {
            method: 'DELETE'
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                loadExistingSynonyms(currentEditTermId);
                // 테이블도 업데이트
                const cell = document.querySelector(`.synonym-cell[data-term-id="${currentEditTermId}"]`);
                if (cell) {
                    loadSynonymsForCell(currentEditTermId, cell);
                }
            } else {
                alert('삭제 실패: ' + data.message);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert('삭제 중 오류가 발생했습니다.');
        });
    }
};

// 유의어 추가 버튼
const addNewSynonymBtn = document.getElementById('addNewSynonymBtn');
if (addNewSynonymBtn) {
    addNewSynonymBtn.addEventListener('click', function() {
        const newSynonym = document.getElementById('newSynonymInput').value.trim();

        if (!newSynonym) {
            alert('유의어를 입력해주세요.');
            return;
        }

        fetch('/admin/term-synonyms', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                termId1: parseInt(currentEditTermId),
                termString2: newSynonym
            })
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                alert(data.message);
                document.getElementById('newSynonymInput').value = '';
                loadExistingSynonyms(currentEditTermId);

                // 테이블도 업데이트
                const cell = document.querySelector(`.synonym-cell[data-term-id="${currentEditTermId}"]`);
                if (cell) {
                    loadSynonymsForCell(currentEditTermId, cell);
                }
            } else {
                alert('추가 실패: ' + data.message);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert('추가 중 오류가 발생했습니다.');
        });
    });
}

// AI 유의어 추천 버튼
const recommendSynonymsBtn = document.getElementById('recommendSynonymsBtn');
if (recommendSynonymsBtn) {
    recommendSynonymsBtn.addEventListener('click', function() {
        const btn = this;
        const originalText = btn.innerHTML;

        btn.disabled = true;
        btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> 추천 중...';

        fetch('/admin/term-synonyms/recommend', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                term: currentEditTermName
            })
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                const container = document.getElementById('recommendedSynonyms');
                const listDiv = document.getElementById('recommendedSynonymsList');

                if (data.recommendations.length === 0) {
                    listDiv.innerHTML = '<small class="text-muted">추천 결과가 없습니다.</small>';
                } else {
                    listDiv.innerHTML = data.recommendations.map(syn => `
                        <button class="btn btn-sm btn-outline-primary me-2 mb-2 add-recommended-syn-btn"
                                data-synonym="${syn}">
                            <i class="fas fa-plus"></i> ${syn}
                        </button>
                    `).join('');
                }

                container.style.display = 'block';
            } else {
                alert('추천 실패: ' + data.message);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert('추천 중 오류가 발생했습니다.');
        })
        .finally(() => {
            btn.disabled = false;
            btn.innerHTML = originalText;
        });
    });
}

// 추천된 유의어 추가 버튼 클릭
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('add-recommended-syn-btn') || e.target.closest('.add-recommended-syn-btn')) {
        const btn = e.target.classList.contains('add-recommended-syn-btn') ? e.target : e.target.closest('.add-recommended-syn-btn');
        const synonym = btn.dataset.synonym;

        fetch('/admin/term-synonyms', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                termId1: parseInt(currentEditTermId),
                termString2: synonym
            })
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                // 버튼 제거
                btn.remove();

                // 기존 유의어 목록 업데이트
                loadExistingSynonyms(currentEditTermId);

                // 테이블도 업데이트
                const cell = document.querySelector(`.synonym-cell[data-term-id="${currentEditTermId}"]`);
                if (cell) {
                    loadSynonymsForCell(currentEditTermId, cell);
                }

                // 성공 메시지
                alert(`'${synonym}' 유의어가 추가되었습니다.`);
            } else {
                alert('추가 실패: ' + data.message);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert('추가 중 오류가 발생했습니다.');
        });
    }
});

// ========== 일괄 유의어 추천 ==========
const startBatchRecommendBtn = document.getElementById('startBatchRecommendBtn');
if (startBatchRecommendBtn) {
    startBatchRecommendBtn.addEventListener('click', function() {
        const btn = this;
        const originalText = btn.innerHTML;
        const limit = parseInt(document.getElementById('synonymBatchLimit').value);

        if (limit < 1 || limit > 50) {
            alert('1~50 사이의 값을 입력해주세요.');
            return;
        }

        btn.disabled = true;
        btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> 처리 중...';

        const resultsDiv = document.getElementById('batchRecommendResults');
        resultsDiv.innerHTML = '<div class="text-center"><i class="fas fa-spinner fa-spin fa-2x"></i><p class="mt-2">AI가 유의어를 추천하고 있습니다. 잠시만 기다려주세요...</p></div>';

        fetch('/admin/term-synonyms/batch-recommend', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                limit: limit
            })
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                const recommendations = data.recommendations;
                const processedCount = data.processedCount;

                if (processedCount === 0) {
                    resultsDiv.innerHTML = '<div class="alert alert-info">유의어가 없는 term이 없습니다!</div>';
                } else {
                    let html = `<div class="alert alert-success">총 ${processedCount}개 term에 대한 유의어 추천 완료!</div>`;
                    html += '<div class="table-responsive"><table class="table table-sm">';
                    html += '<thead><tr><th>Term</th><th>추천 유의어</th><th>액션</th></tr></thead><tbody>';

                    for (const [term, synonyms] of Object.entries(recommendations)) {
                        if (synonyms.length > 0) {
                            html += `<tr>
                                <td><strong>${term}</strong></td>
                                <td>${synonyms.join(', ')}</td>
                                <td>
                                    ${synonyms.map(syn => `
                                        <button class="btn btn-sm btn-success batch-add-syn-btn"
                                                data-term="${term}" data-synonym="${syn}">
                                            <i class="fas fa-plus"></i>
                                        </button>
                                    `).join('')}
                                </td>
                            </tr>`;
                        } else {
                            html += `<tr>
                                <td><strong>${term}</strong></td>
                                <td colspan="2"><small class="text-muted">추천 결과 없음</small></td>
                            </tr>`;
                        }
                    }

                    html += '</tbody></table></div>';
                    resultsDiv.innerHTML = html;
                }
            } else {
                resultsDiv.innerHTML = `<div class="alert alert-danger">오류: ${data.message}</div>`;
            }
        })
        .catch(error => {
            console.error('Error:', error);
            resultsDiv.innerHTML = '<div class="alert alert-danger">일괄 추천 중 오류가 발생했습니다.</div>';
        })
        .finally(() => {
            btn.disabled = false;
            btn.innerHTML = originalText;
        });
    });
}

// 일괄 추천 결과에서 유의어 추가
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('batch-add-syn-btn') || e.target.closest('.batch-add-syn-btn')) {
        const btn = e.target.classList.contains('batch-add-syn-btn') ? e.target : e.target.closest('.batch-add-syn-btn');
        const term = btn.dataset.term;
        const synonym = btn.dataset.synonym;

        fetch('/admin/term-synonyms', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                termString1: term,
                termString2: synonym
            })
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                btn.innerHTML = '<i class="fas fa-check"></i>';
                btn.classList.remove('btn-success');
                btn.classList.add('btn-secondary');
                btn.disabled = true;

                alert(`'${term}' ↔ '${synonym}' 유의어가 추가되었습니다.`);
            } else {
                alert('추가 실패: ' + data.message);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert('추가 중 오류가 발생했습니다.');
        });
    }
});

// ===== Article Term CRUD 기능 =====

// Term 클릭 시 수정/삭제 모달 열기
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('term-badge-editable') || e.target.closest('.term-badge-editable')) {
        const badge = e.target.classList.contains('term-badge-editable') ? e.target : e.target.closest('.term-badge-editable');
        const articleId = badge.dataset.articleId;
        const articleTermId = badge.dataset.articleTermId;
        const term = badge.dataset.term;
        const score = parseFloat(badge.dataset.score);
        const frequency = parseInt(badge.dataset.frequency);

        openEditTermModal(articleId, articleTermId, term, score, frequency);
    }
});

// Term 추가 버튼 클릭
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('add-term-btn') || e.target.closest('.add-term-btn')) {
        const btn = e.target.classList.contains('add-term-btn') ? e.target : e.target.closest('.add-term-btn');
        const articleId = btn.dataset.articleId;

        openAddTermModal(articleId);
    }
});

// Term 추가 모달 열기
function openAddTermModal(articleId) {
    const termInput = prompt('추가할 Term을 입력하세요:');
    if (!termInput || termInput.trim() === '') return;

    const scoreInput = prompt('Score를 입력하세요 (0.0 ~ 1.0):', '0.5');
    if (scoreInput === null) return;

    const score = parseFloat(scoreInput);
    if (isNaN(score) || score < 0 || score > 1) {
        alert('Score는 0.0에서 1.0 사이의 숫자여야 합니다.');
        return;
    }

    fetch(`/admin/articles/${articleId}/terms`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({
            term: termInput.trim(),
            score: score,
            frequency: 1
        })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert(data.message);
            loadTermsForArticle(articleId);
        } else {
            alert('Term 추가 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('Error:', error);
        alert('Term 추가 중 오류가 발생했습니다.');
    });
}

// Term 수정/삭제 모달 열기
function openEditTermModal(articleId, articleTermId, term, score, frequency) {
    const action = confirm(`Term: ${term}\nScore: ${(score * 100).toFixed(0)}%\nFrequency: ${frequency}\n\n수정하려면 [확인], 삭제하려면 [취소]를 누르세요.`);

    if (action) {
        // 수정
        const newScoreInput = prompt(`새로운 Score를 입력하세요 (0.0 ~ 1.0):`, score.toString());
        if (newScoreInput === null) return;

        const newScore = parseFloat(newScoreInput);
        if (isNaN(newScore) || newScore < 0 || newScore > 1) {
            alert('Score는 0.0에서 1.0 사이의 숫자여야 합니다.');
            return;
        }

        fetch(`/admin/articles/${articleId}/terms/${articleTermId}`, {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                score: newScore
            })
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                alert(data.message);
                loadTermsForArticle(articleId);
            } else {
                alert('Term 수정 실패: ' + data.message);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert('Term 수정 중 오류가 발생했습니다.');
        });
    } else {
        // 삭제
        if (confirm(`정말로 Term '${term}'을 삭제하시겠습니까?`)) {
            fetch(`/admin/articles/${articleId}/terms/${articleTermId}`, {
                method: 'DELETE'
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    alert(data.message);
                    loadTermsForArticle(articleId);
                } else {
                    alert('Term 삭제 실패: ' + data.message);
                }
            })
            .catch(error => {
                console.error('Error:', error);
                alert('Term 삭제 중 오류가 발생했습니다.');
            });
        }
    }
}

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
// 관련 글 키워드 관리
// ============================================

let currentKeywordId = null;

// 키워드 탭이 활성화되어 있으면 키워드 목록 로드
if (window.location.search.includes('tab=keywords')) {
    loadKeywords();
}

// 키워드 목록 로드
function loadKeywords() {
    fetch('/admin/related-content-keywords')
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                displayKeywords(data.keywords);
            } else {
                document.getElementById('keywordTableBody').innerHTML = `
                    <tr>
                        <td colspan="4" class="text-center text-danger">
                            <i class="fas fa-exclamation-circle"></i> ${data.message}
                        </td>
                    </tr>`;
            }
        })
        .catch(error => {
            console.error('키워드 로드 오류:', error);
            document.getElementById('keywordTableBody').innerHTML = `
                <tr>
                    <td colspan="4" class="text-center text-danger">
                        <i class="fas fa-exclamation-circle"></i> 키워드를 불러오는 중 오류가 발생했습니다.
                    </td>
                </tr>`;
        });
}

// 키워드 목록 표시
function displayKeywords(keywords) {
    const tbody = document.getElementById('keywordTableBody');

    if (keywords.length === 0) {
        tbody.innerHTML = `
            <tr>
                <td colspan="4" class="text-center text-muted">
                    등록된 키워드가 없습니다. 키워드를 추가해주세요.
                </td>
            </tr>`;
        return;
    }

    tbody.innerHTML = keywords.map(keyword => `
        <tr>
            <td>${keyword.id}</td>
            <td><strong>${escapeHtml(keyword.keyword)}</strong></td>
            <td>${formatDateTime(keyword.createdAt)}</td>
            <td>
                <button class="btn btn-sm btn-primary edit-keyword-btn" data-keyword-id="${keyword.id}" data-keyword="${escapeHtml(keyword.keyword)}">
                    <i class="fas fa-edit"></i>
                </button>
                <button class="btn btn-sm btn-danger delete-keyword-btn" data-keyword-id="${keyword.id}" data-keyword="${escapeHtml(keyword.keyword)}">
                    <i class="fas fa-trash"></i>
                </button>
            </td>
        </tr>
    `).join('');
}

// 키워드 추가 버튼 클릭
const addKeywordBtn = document.getElementById('addKeywordBtn');
if (addKeywordBtn) {
    addKeywordBtn.addEventListener('click', function() {
        currentKeywordId = null;
        document.getElementById('keywordModalTitle').textContent = '키워드 추가';
        document.getElementById('keywordInput').value = '';
        const modal = new bootstrap.Modal(document.getElementById('keywordModal'));
        modal.show();
    });
}

// 키워드 편집 버튼 클릭
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('edit-keyword-btn') || e.target.closest('.edit-keyword-btn')) {
        const btn = e.target.classList.contains('edit-keyword-btn') ? e.target : e.target.closest('.edit-keyword-btn');
        currentKeywordId = btn.dataset.keywordId;
        const keyword = btn.dataset.keyword;

        document.getElementById('keywordModalTitle').textContent = '키워드 수정';
        document.getElementById('keywordInput').value = keyword;

        const modal = new bootstrap.Modal(document.getElementById('keywordModal'));
        modal.show();
    }

    // 키워드 삭제 버튼 클릭
    if (e.target.classList.contains('delete-keyword-btn') || e.target.closest('.delete-keyword-btn')) {
        const btn = e.target.classList.contains('delete-keyword-btn') ? e.target : e.target.closest('.delete-keyword-btn');
        const keywordId = btn.dataset.keywordId;
        const keyword = btn.dataset.keyword;

        if (confirm(`"${keyword}" 키워드를 삭제하시겠습니까?`)) {
            deleteKeyword(keywordId);
        }
    }
});

// 키워드 저장 버튼 클릭
const saveKeywordBtn = document.getElementById('saveKeywordBtn');
if (saveKeywordBtn) {
    saveKeywordBtn.addEventListener('click', function() {
        const keyword = document.getElementById('keywordInput').value.trim();

        if (!keyword) {
            alert('키워드를 입력해주세요.');
            return;
        }

        if (currentKeywordId) {
            // 수정
            updateKeyword(currentKeywordId, keyword);
        } else {
            // 추가
            addKeyword(keyword);
        }
    });
}

// 키워드 추가
function addKeyword(keyword) {
    fetch('/admin/related-content-keywords', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({ keyword: keyword })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert('키워드가 추가되었습니다.');
            bootstrap.Modal.getInstance(document.getElementById('keywordModal')).hide();
            loadKeywords();
        } else {
            alert('키워드 추가 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('키워드 추가 오류:', error);
        alert('키워드 추가 중 오류가 발생했습니다.');
    });
}

// 키워드 수정
function updateKeyword(id, keyword) {
    fetch(`/admin/related-content-keywords/${id}`, {
        method: 'PUT',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({ keyword: keyword })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert('키워드가 수정되었습니다.');
            bootstrap.Modal.getInstance(document.getElementById('keywordModal')).hide();
            loadKeywords();
        } else {
            alert('키워드 수정 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('키워드 수정 오류:', error);
        alert('키워드 수정 중 오류가 발생했습니다.');
    });
}

// 키워드 삭제
function deleteKeyword(id) {
    fetch(`/admin/related-content-keywords/${id}`, {
        method: 'DELETE'
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert('키워드가 삭제되었습니다.');
            loadKeywords();
        } else {
            alert('키워드 삭제 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('키워드 삭제 오류:', error);
        alert('키워드 삭제 중 오류가 발생했습니다.');
    });
}

// HTML 이스케이프 헬퍼 함수
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// 날짜 포맷팅 헬퍼 함수
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
