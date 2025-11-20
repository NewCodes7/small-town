// 관리자 컨트롤 패널 기능
let currentArticleId = null;
let isVideoPage = false; // 영상 페이지인지 여부

// 페이지 로드 시 사용자 정보 확인 및 관리자 버튼 표시
document.addEventListener('DOMContentLoaded', async function() {
    // video 페이지인지 확인 (editVideoModal 존재 여부로 판단)
    isVideoPage = document.getElementById('editVideoModal') !== null;
    await checkUserAndShowAdminControls();
});

// 사용자 정보 확인 및 관리자 컨트롤 표시
async function checkUserAndShowAdminControls() {
    try {
        await userAuth.fetchUserInfo();

        if (userAuth.isAuthenticated() && userAuth.isAdminUser()) {
            userAuth.showAdminControls();
            setupAdminEventListeners();
            loadCategories();
        }
    } catch (error) {
        console.error('사용자 정보 확인 중 오류:', error);
    }
}

// 관리자 이벤트 리스너 설정
function setupAdminEventListeners() {
    // 이벤트 위임을 사용하여 더 효과적으로 처리
    document.body.addEventListener('click', function(e) {
        // 관리자 버튼 클릭 처리
        const editBtn = e.target.closest('.admin-edit-btn');
        const summaryBtn = e.target.closest('.admin-summary-btn');
        const deleteBtn = e.target.closest('.admin-delete-btn');

        if (editBtn || summaryBtn || deleteBtn) {
            e.preventDefault();
            e.stopPropagation();
            e.stopImmediatePropagation();

            if (editBtn) {
                currentArticleId = editBtn.dataset.articleId;
                loadArticleForEdit(currentArticleId);
            } else if (summaryBtn) {
                currentArticleId = summaryBtn.dataset.articleId;
                loadSummariesForEdit(currentArticleId);
            } else if (deleteBtn) {
                const articleId = deleteBtn.dataset.articleId;
                const articleTitle = deleteBtn.dataset.articleTitle;

                const confirmed = confirm(`정말로 "${articleTitle}" 글을 삭제하시겠습니까?\n\n삭제된 글은 복구할 수 없습니다.`);
                if (confirmed === true) {
                    deleteArticle(articleId);
                }
                // 취소시에는 아무것도 하지 않음
            }
            return false;
        }

        // 요약 항목 삭제
        if (e.target.classList.contains('remove-summary-btn') || e.target.closest('.remove-summary-btn')) {
            const summaryDiv = e.target.closest('.mb-3.p-3.border.rounded');
            summaryDiv.remove();
        }
    });

    // 저장 버튼 이벤트
    const saveArticleBtn = document.getElementById('saveArticleBtn');
    if (saveArticleBtn) {
        saveArticleBtn.addEventListener('click', saveArticle);
    }

    const saveVideoBtn = document.getElementById('saveVideoBtn');
    if (saveVideoBtn) {
        saveVideoBtn.addEventListener('click', saveArticle); // saveArticle 함수 재사용
    }

    const saveSummariesBtn = document.getElementById('saveSummariesBtn');
    if (saveSummariesBtn) {
        saveSummariesBtn.addEventListener('click', saveSummaries);
    }

    const addSummaryBtn = document.getElementById('addSummaryBtn');
    if (addSummaryBtn) {
        addSummaryBtn.addEventListener('click', function() {
            addSummaryItem();
        });
    }

    // 썸네일 미리보기
    const articleThumbnail = document.getElementById('articleThumbnail');
    if (articleThumbnail) {
        articleThumbnail.addEventListener('input', function() {
            updateThumbnailPreview(this.value);
        });
    }

    // 카테고리 입력 필드 연동
    const customCategory = document.getElementById('articleCustomCategory');
    const categorySelect = document.getElementById('articleCategorySelect');

    if (customCategory && categorySelect) {
        customCategory.addEventListener('input', function() {
            if (this.value.trim()) {
                categorySelect.value = '';
            }
        });

        categorySelect.addEventListener('change', function() {
            if (this.value) {
                customCategory.value = '';
            }
        });
    }
}

// 카테고리 목록 로드
function loadCategories() {
    fetch('/admin/categories')
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                const categorySelect = document.getElementById('articleCategorySelect');
                if (categorySelect) {
                    // 기존 옵션 유지하고 새 옵션 추가
                    data.categories.forEach(category => {
                        const option = document.createElement('option');
                        option.value = category.name;
                        option.textContent = category.name;
                        categorySelect.appendChild(option);
                    });
                }
            }
        })
        .catch(error => {
            console.error('카테고리 로드 중 오류:', error);
        });
}

// 글/영상 정보 로드
function loadArticleForEdit(articleId) {
    if (isVideoPage) {
        // 영상 정보 로드
        fetch(`/admin/videos/${articleId}/detail`)
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    // 폼에 데이터 채우기
                    document.getElementById('articleTitle').value = data.video.title || '';
                    document.getElementById('articleLink').value = data.video.link || '';
                    document.getElementById('articleThumbnail').value = data.video.thumbnailUrl || '';
                    document.getElementById('articleCategorySelect').value =
                        data.video.category ? data.video.category.name : '';

                    // 썸네일 미리보기
                    updateThumbnailPreview(data.video.thumbnailUrl);

                    // 모달 표시
                    const modal = new bootstrap.Modal(document.getElementById('editVideoModal'));
                    modal.show();
                } else {
                    alert('영상 정보 로드 실패: ' + data.message);
                }
            })
            .catch(error => {
                console.error('Error:', error);
                alert('영상 정보 로드 중 오류가 발생했습니다.');
            });
    } else {
        // 글 정보 로드
        fetch(`/admin/articles/${articleId}/detail`)
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    // 폼에 데이터 채우기
                    document.getElementById('articleTitle').value = data.article.title || '';
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

// 썸네일 미리보기 업데이트
function updateThumbnailPreview(url) {
    const preview = document.getElementById('thumbnailPreview');
    if (url && url.trim()) {
        preview.innerHTML = `<img src="${url}" style="max-width: 200px; max-height: 100px; object-fit: cover; border-radius: 4px;" />`;
    } else {
        preview.innerHTML = '';
    }
}

// 글/영상 정보 저장
function saveArticle() {
    const title = document.getElementById('articleTitle').value.trim();
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
        link: link,
        thumbnailUrl: thumbnailUrl || null,
        categoryName: categoryName || null
    };

    const apiPath = isVideoPage ? `/admin/videos/${currentArticleId}` : `/admin/articles/${currentArticleId}`;
    const modalId = isVideoPage ? 'editVideoModal' : 'editArticleModal';
    const itemType = isVideoPage ? '영상' : '글';

    fetch(apiPath, {
        method: 'PUT',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(requestData)
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert(`${itemType} 정보가 성공적으로 수정되었습니다.`);
            const modal = bootstrap.Modal.getInstance(document.getElementById(modalId));
            modal.hide();
            location.reload();
        } else {
            alert(`${itemType} 정보 수정 실패: ` + data.message);
        }
    })
    .catch(error => {
        console.error('Error:', error);
        alert(`${itemType} 정보 수정 중 오류가 발생했습니다.`);
    });
}

// 요약 저장
function saveSummaries() {
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
            alert('요약이 성공적으로 수정되었습니다.');
            const modal = bootstrap.Modal.getInstance(document.getElementById('editSummariesModal'));
            modal.hide();
            location.reload();
        } else {
            alert('요약 수정 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('Error:', error);
        alert('요약 수정 중 오류가 발생했습니다.');
    });
}

// 글 삭제
function deleteArticle(articleId) {
    // 유효성 검사
    if (!articleId) {
        alert('글 ID가 올바르지 않습니다.');
        return;
    }

    fetch(`/api/admin/articles/${articleId}`, {
        method: 'DELETE',
        headers: {
            'Content-Type': 'application/json',
        }
    })
    .then(response => {
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        return response.json();
    })
    .then(data => {
        if (data.status === 'success') {
            alert(data.message);
            location.reload();
        } else {
            alert('글 삭제 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('삭제 중 오류:', error);
        alert('글 삭제 중 오류가 발생했습니다: ' + error.message);
    });
}