// 카드 클릭 시 외부 링크로 이동
document.querySelectorAll('.article-card').forEach(card => {
    card.addEventListener('click', function(e) {
c        // 태그나 다른 링크, 좋아요 버튼, 관리자 버튼을 클릭한 경우가 아니라면
        if (e.target.tagName !== 'A' && !e.target.closest('a') && !e.target.closest('.badge') &&
            !e.target.closest('.like-button') && !e.target.closest('.admin-delete-btn') &&
            !e.target.closest('.admin-edit-btn')) {
            const titleLink = this.querySelector('h5 a');
            if (titleLink) {
                window.open(titleLink.href, '_blank');
            }
        }
    });

    // 카드에 커서 스타일 추가
    card.style.cursor = 'pointer';
});

// 로그인 모달 표시 함수
function showLoginPopup() {
    const modalElement = document.getElementById('loginModal');
    
    if (modalElement) {
        // Bootstrap이 로드되었는지 확인
        if (typeof bootstrap !== 'undefined') {
            const loginModal = new bootstrap.Modal(modalElement);
            loginModal.show();
        } else {
            // Bootstrap이 없으면 수동으로 모달 표시
            modalElement.style.display = 'block';
            modalElement.classList.add('show');
            document.body.classList.add('modal-open');
            
            // 백드롭 추가
            const backdrop = document.createElement('div');
            backdrop.className = 'modal-backdrop fade show';
            backdrop.id = 'modalBackdrop';
            document.body.appendChild(backdrop);
            
            // 닫기 버튼 이벤트
            const closeBtn = modalElement.querySelector('.btn-close');
            if (closeBtn) {
                closeBtn.onclick = function() {
                    modalElement.style.display = 'none';
                    modalElement.classList.remove('show');
                    document.body.classList.remove('modal-open');
                    const backdrop = document.getElementById('modalBackdrop');
                    if (backdrop) backdrop.remove();
                };
            }
        }
    } else {
        console.error('loginModal 엘리먼트를 찾을 수 없습니다');
    }
}


// 좋아요 버튼 클릭 이벤트
document.querySelectorAll('.like-button').forEach(btn => {
    btn.addEventListener('click', async function(e) {
        e.preventDefault();
        e.stopPropagation();
        
        const articleId = this.getAttribute('data-article-id');
        const likeIcon = this.querySelector('.like-icon');
        const likeCount = this.querySelector('.like-count');
        
        try {
            const response = await fetch(`/api/articles/${articleId}/like`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                credentials: 'same-origin',
                redirect: 'manual'
            });
            
            if (response.status === 401 || response.status === 0) {
                // 인증되지 않은 사용자 (401) 또는 수동 리다이렉트로 인한 opaque response (0)
                showLoginPopup();
                return;
            }
            
            if (response.ok) {
                const data = await response.json();
                
                // 좋아요 수 업데이트
                likeCount.textContent = data.likeCount;
                
                // 좋아요 상태에 따른 스타일 변경
                if (data.isLiked) {
                    this.classList.add('liked');
                } else {
                    this.classList.remove('liked');
                }
            }
        } catch (error) {
            console.error('좋아요 처리 중 오류 발생:', error);
        }
    });
});

// 페이지 로드 시 좋아요 상태 확인
async function loadLikeStatuses() {
    const likeButtons = document.querySelectorAll('.like-button');
    
    for (const btn of likeButtons) {
        const articleId = btn.getAttribute('data-article-id');
        
        try {
            const response = await fetch(`/api/articles/${articleId}/like-status`, {
                credentials: 'same-origin'
            });
            if (response.ok) {
                const data = await response.json();
                const likeIcon = btn.querySelector('.like-icon');
                
                if (data.hasLiked) {
                    btn.classList.add('liked');
                }
            }
        } catch (error) {
            console.error('좋아요 상태 로드 중 오류 발생:', error);
        }
    }
}

// 상대 시간 계산 함수
function getRelativeTime(dateString) {
    const now = new Date();
    const date = new Date(dateString);
    const diffInSeconds = Math.floor((now - date) / 1000);
    
    const minute = 60;
    const hour = minute * 60;
    const day = hour * 24;
    const week = day * 7;
    const month = day * 30;
    const year = day * 365;
    
    if (diffInSeconds < minute) {
        return '방금 전';
    } else if (diffInSeconds < hour) {
        const minutes = Math.floor(diffInSeconds / minute);
        return `${minutes}분 전`;
    } else if (diffInSeconds < day) {
        const hours = Math.floor(diffInSeconds / hour);
        return `${hours}시간 전`;
    } else if (diffInSeconds < week) {
        const days = Math.floor(diffInSeconds / day);
        return `${days}일 전`;
    } else if (diffInSeconds < month) {
        const weeks = Math.floor(diffInSeconds / week);
        return `${weeks}주일 전`;
    } else if (diffInSeconds < year) {
        const months = Math.floor(diffInSeconds / month);
        return `${months}달 전`;
    } else {
        const years = Math.floor(diffInSeconds / year);
        return `${years}년 전`;
    }
}

// 날짜 포맷팅 함수
function formatDate(dateString) {
    const date = new Date(dateString);
    return date.toLocaleDateString('ko-KR', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
    });
}

// 기본 썸네일 아이콘 랜덤 설정
function setupDefaultThumbnails() {
    document.querySelectorAll('.default-thumbnail').forEach((thumbnail, index) => {
        const iconElement = thumbnail.querySelector('i');
        const textElement = thumbnail.querySelector('.thumbnail-text');
        
        if (iconElement && textElement) {
            iconElement.className = "fas fa-code";
            textElement.textContent = "NewCodes";
        }
    });
}

// 관리자 권한 확인 및 버튼 표시
async function checkAdminStatus() {
    try {
        const response = await fetch('/api/user-info', {
            credentials: 'same-origin'
        });

        if (response.ok) {
            const data = await response.json();

            if (data.isAdmin) {
                // 관리자인 경우 삭제 및 수정 버튼 표시
                document.querySelectorAll('.admin-buttons-container').forEach(container => {
                    container.classList.remove('d-none');
                });

                // 크롤링 버튼 표시
                const crawlBtn = document.getElementById('adminCrawlBtn');
                if (crawlBtn) {
                    crawlBtn.classList.remove('d-none');
                }
            }
        }
    } catch (error) {
        console.error('관리자 권한 확인 중 오류 발생:', error);
    }
}

// 게시글 삭제 처리
async function deleteArticle(articleId) {
    if (!confirm('정말로 이 게시글을 삭제하시겠습니까?')) {
        return;
    }

    try {
        const response = await fetch(`/api/admin/articles/${articleId}`, {
            method: 'DELETE',
            credentials: 'same-origin'
        });

        const data = await response.json();

        if (response.ok) {
            alert(data.message || '게시글이 삭제되었습니다.');
            window.location.reload();
        } else {
            alert(data.message || '게시글 삭제에 실패했습니다.');
        }
    } catch (error) {
        console.error('게시글 삭제 중 오류 발생:', error);
        alert('게시글 삭제 중 오류가 발생했습니다.');
    }
}

// 게시글 수정 모달 표시
function showEditModal(articleId, publishedAt, thumbnail) {
    const modal = new bootstrap.Modal(document.getElementById('editArticleModal'));

    // 폼 데이터 설정
    document.getElementById('editArticleId').value = articleId;

    // publishedAt을 datetime-local 형식으로 변환 (YYYY-MM-DDTHH:mm)
    if (publishedAt) {
        const date = new Date(publishedAt);
        const formattedDate = date.getFullYear() + '-' +
            String(date.getMonth() + 1).padStart(2, '0') + '-' +
            String(date.getDate()).padStart(2, '0') + 'T' +
            String(date.getHours()).padStart(2, '0') + ':' +
            String(date.getMinutes()).padStart(2, '0');
        document.getElementById('editPublishedAt').value = formattedDate;
    }

    document.getElementById('editThumbnail').value = thumbnail || '';

    // 메시지 초기화
    const messageDiv = document.getElementById('editMessage');
    messageDiv.classList.add('d-none');

    modal.show();
}

// 게시글 수정 저장
async function saveArticleEdit(articleId, publishedAt, thumbnail) {
    try {
        // 발행일 수정
        const publishDateResponse = await fetch(`/api/admin/articles/${articleId}/publish-date`, {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json',
            },
            credentials: 'same-origin',
            body: JSON.stringify({ publishedAt })
        });

        const publishDateData = await publishDateResponse.json();

        if (!publishDateResponse.ok) {
            throw new Error(publishDateData.message || '발행일 수정 실패');
        }

        // 썸네일 수정 (기존 Admin API 사용)
        const thumbnailResponse = await fetch(`/admin/articles/${articleId}`, {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json',
            },
            credentials: 'same-origin',
            body: JSON.stringify({ thumbnailUrl: thumbnail })
        });

        const thumbnailData = await thumbnailResponse.json();

        if (!thumbnailResponse.ok) {
            throw new Error(thumbnailData.message || '썸네일 수정 실패');
        }

        return { success: true, message: '게시글 정보가 성공적으로 수정되었습니다.' };
    } catch (error) {
        return { success: false, message: error.message };
    }
}

// 기업 크롤링 시작
async function startCrawling(corporationId) {
    const crawlBtn = document.getElementById('adminCrawlBtn');
    const originalHtml = crawlBtn.innerHTML;

    // 버튼 비활성화 및 로딩 표시
    crawlBtn.disabled = true;
    crawlBtn.innerHTML = '<i class="fas fa-spinner fa-spin me-2"></i>크롤링 중...';

    try {
        const response = await fetch(`/api/crawling/corporation/${corporationId}`, {
            method: 'GET',
            credentials: 'same-origin'
        });

        const data = await response.json();

        if (response.ok && data.success) {
            const message = `크롤링이 완료되었습니다!\n\n` +
                          `기업: ${data.corporationName}\n` +
                          `전체 글: ${data.totalArticles}개\n` +
                          `새로운 글: ${data.newArticles}개`;
            alert(message);
            window.location.reload();
        } else {
            alert(data.message || '크롤링에 실패했습니다.');
        }
    } catch (error) {
        console.error('크롤링 중 오류 발생:', error);
        alert('크롤링 중 오류가 발생했습니다.');
    } finally {
        // 버튼 복원
        crawlBtn.disabled = false;
        crawlBtn.innerHTML = originalHtml;
    }
}

// 기업 페이지 조회수 증가
async function incrementCorporationViewCount() {
    // URL에서 기업 ID 추출
    const pathParts = window.location.pathname.split('/');
    const corporationId = pathParts[pathParts.indexOf('corporations') + 1];

    if (!corporationId || corporationId === '' || isNaN(corporationId)) {
        return;
    }

    try {
        await fetch(`/api/corporations/${corporationId}/view`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            credentials: 'same-origin',
            redirect: 'manual'
        });
    } catch (error) {
        console.log('조회수 증가 요청 실패:', error);
    }
}

// 페이지 로드 후 상대 시간 적용 및 좋아요 상태 로드
document.addEventListener('DOMContentLoaded', function() {
    // 기업 페이지 조회수 증가
    incrementCorporationViewCount();

    document.querySelectorAll('.relative-time').forEach(element => {
        const dateString = element.getAttribute('data-date');
        if (dateString) {
            // 상대 시간 표시
            element.textContent = getRelativeTime(dateString);
            // 툴팁에 포맷된 날짜 표시
            element.title = formatDate(dateString);
        }
    });

    // 좋아요 상태 로드
    loadLikeStatuses();

    // 기본 썸네일 설정
    setupDefaultThumbnails();

    // 관리자 권한 확인 및 버튼 표시
    checkAdminStatus();

    // Content Type Toggle 이벤트 리스너
    document.querySelectorAll('input[name="contentTypeToggle"]').forEach(radio => {
        radio.addEventListener('change', function() {
            const contentType = this.value;
            const corporationId = window.location.pathname.split('/').pop();
            window.location.href = `/corporations/${corporationId}?contentType=${contentType}`;
        });
    });

    // 삭제 버튼 이벤트 리스너
    document.querySelectorAll('.admin-delete-btn').forEach(btn => {
        btn.addEventListener('click', function(e) {
            e.preventDefault();
            e.stopPropagation();
            const articleId = this.getAttribute('data-article-id');
            deleteArticle(articleId);
        });
    });

    // 수정 버튼 이벤트 리스너
    document.querySelectorAll('.admin-edit-btn').forEach(btn => {
        btn.addEventListener('click', function(e) {
            e.preventDefault();
            e.stopPropagation();
            const articleId = this.getAttribute('data-article-id');
            const publishedAt = this.getAttribute('data-published-at');
            const thumbnail = this.getAttribute('data-thumbnail');
            showEditModal(articleId, publishedAt, thumbnail);
        });
    });

    // 크롤링 버튼 이벤트 리스너
    const crawlBtn = document.getElementById('adminCrawlBtn');
    if (crawlBtn) {
        crawlBtn.addEventListener('click', function() {
            const corporationId = this.getAttribute('data-corporation-id');
            if (confirm('이 기업의 블로그를 크롤링하시겠습니까?')) {
                startCrawling(corporationId);
            }
        });
    }

    // 게시글 편집 폼 제출 이벤트
    const editForm = document.getElementById('editArticleForm');
    if (editForm) {
        editForm.addEventListener('submit', async function(e) {
            e.preventDefault();

            const articleId = document.getElementById('editArticleId').value;
            const publishedAt = document.getElementById('editPublishedAt').value;
            const thumbnail = document.getElementById('editThumbnail').value;
            const messageDiv = document.getElementById('editMessage');

            const result = await saveArticleEdit(articleId, publishedAt, thumbnail);

            if (result.success) {
                messageDiv.textContent = result.message;
                messageDiv.className = 'alert alert-success';
                messageDiv.classList.remove('d-none');

                // 성공 후 페이지 새로고침
                setTimeout(() => {
                    window.location.reload();
                }, 1000);
            } else {
                messageDiv.textContent = result.message;
                messageDiv.className = 'alert alert-danger';
                messageDiv.classList.remove('d-none');
            }
        });
    }

    // 모달 로그인 처리
    const loginForm = document.getElementById('loginForm');
    if (loginForm) {
        loginForm.addEventListener('submit', async function(e) {
            e.preventDefault();

            const email = document.getElementById('modalEmail').value;
            const password = document.getElementById('modalPassword').value;
            const messageDiv = document.getElementById('loginMessage');

            try {
                const response = await fetch('/api/auth/login', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                    },
                    credentials: 'same-origin',
                    body: JSON.stringify({ email, password })
                });

                if (response.ok) {
                    messageDiv.textContent = '로그인 성공! 페이지를 새로고침합니다.';
                    messageDiv.className = 'alert alert-success';
                    messageDiv.classList.remove('d-none');

                    // 로그인 성공 후 페이지 새로고침하여 인증 상태 반영
                    setTimeout(() => {
                        window.location.reload();
                    }, 1000);
                } else {
                    const data = await response.json();
                    messageDiv.textContent = data.message || '로그인에 실패했습니다.';
                    messageDiv.className = 'alert alert-danger';
                    messageDiv.classList.remove('d-none');
                }
            } catch (error) {
                messageDiv.textContent = '로그인 중 오류가 발생했습니다.';
                messageDiv.className = 'alert alert-danger';
                messageDiv.classList.remove('d-none');
            }
        });
    }
});