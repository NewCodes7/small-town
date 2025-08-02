function bindArticleEvents() {
    clickCard();
    likeButton();
    initPagination();
}

// 카드 클릭 시 외부 링크로 이동
function clickCard() {
    document.querySelectorAll('.article-card').forEach(card => {
        card.addEventListener('click', async function(e) {
            // 태그나 다른 링크, 좋아요 버튼, 회사 링크, 삭제 버튼을 클릭한 경우가 아니라면
            if (e.target.tagName !== 'A' && !e.target.closest('a') && !e.target.closest('.badge') && !e.target.closest('.like-button') && !e.target.closest('.company-link') && !e.target.closest('.admin-delete-btn')) {
                const titleLink = this.querySelector('h5 a');
                const articleId = this.getAttribute('data-article-id');
                if (titleLink) {
                    window.open(titleLink.href, '_blank');

                    const response = await fetch(`/api/articles/${articleId}/view`, {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/json',
                        },
                        credentials: 'same-origin',
                        redirect: 'manual'
                    });

                    if (response.ok) {
                        const data = await response.json();

                        if (data.incremented) {
                            const viewCount = this.querySelector('.view-count');
                            viewCount.textContent = data.viewCount;
                        }
                    }
                }
            }
        });
        
        // 카드에 커서 스타일 추가
        card.style.cursor = 'pointer';
    });
};

// 좋아요 버튼 클릭 이벤트
function likeButton() {
    document.querySelectorAll('.like-button').forEach(btn => {
        console.log('좋아요 버튼 이벤트 리스너 추가:', btn);
        btn.addEventListener('click', async function(e) {
            console.log('좋아요 버튼 클릭됨');
            e.preventDefault();
            e.stopPropagation();
            
            const articleId = this.getAttribute('data-article-id');
            const likeIcon = this.querySelector('.like-icon');
            const likeCount = this.querySelector('.like-count');
            console.log('articleId:', articleId);
            
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
                    console.log('401 응답 받음, 로그인 모달 표시');
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
}

// 페이지 로드 후 상대 시간 적용 및 좋아요 상태 로드
async function initPagination() {
    // 사용자 정보 로드
    await loadUserInfo();
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
    
    // 떠다니는 로고 위치 설정
    positionFloatingLogos();
    
    // 기본 썸네일 설정
    setupDefaultThumbnails();
    
    // 관리자 삭제 버튼 표시
    showAdminDeleteButtons();
    
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
    
    // 윈도우 리사이즈 시 위치 재조정
    window.addEventListener('resize', function() {
        setTimeout(positionFloatingLogos, 100);
    });
}

// 로그인 모달 표시 함수
function showLoginPopup() {
    console.log('showLoginPopup 함수 호출됨');
    const modalElement = document.getElementById('loginModal');
    console.log('모달 엘리먼트:', modalElement);
    
    if (modalElement) {
        // Bootstrap이 로드되었는지 확인
        if (typeof bootstrap !== 'undefined') {
            console.log('Bootstrap 사용하여 모달 표시');
            const loginModal = new bootstrap.Modal(modalElement);
            loginModal.show();
        } else {
            console.log('Bootstrap이 없어서 수동으로 모달 표시');
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
                
                // 인증된 사용자의 좋아요 상태만 반영
                if (data.authenticated && data.hasLiked) {
                    btn.classList.add('liked');
                }
                
                // 좋아요 수는 항상 업데이트
                const likeCount = btn.querySelector('.like-count');
                likeCount.textContent = data.likeCount;
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

// 랜덤 위치 배정 함수 (겹침 방지)
function positionFloatingLogos() {
    const container = document.querySelector('.floating-logos-container');
    if (!container) return;
    
    const logos = container.querySelectorAll('.floating-logo');
    const positions = [];
    const logoSize = 80; // 로고 크기 + 여백
    
    logos.forEach((logo, index) => {
        let position;
        let attempts = 0;
        const maxAttempts = 50;
        
        do {
            position = {
                left: Math.random() * (container.offsetWidth - logoSize),
                top: Math.random() * (container.offsetHeight - logoSize)
            };
            attempts++;
        } while (attempts < maxAttempts && positions.some(pos => 
            Math.abs(pos.left - position.left) < logoSize && 
            Math.abs(pos.top - position.top) < logoSize
        ));
        
        positions.push(position);
        
        // 위치 및 애니메이션 설정
        logo.style.left = position.left + 'px';
        logo.style.top = position.top + 'px';
        logo.style.animationDuration = (7 + Math.random() * 6) + 's'; // 7-13초
        logo.style.animationDelay = (index * 0.5) + 's';
    });
}

// 관리자 삭제 버튼 표시 함수
function showAdminDeleteButtons() {
    if (currentUser && currentUser.isAdmin) {
        document.querySelectorAll('.article-card').forEach(card => {
            if (!card.querySelector('.admin-delete-btn')) {
                const articleId = card.querySelector('.like-button').getAttribute('data-article-id');
                const deleteBtn = document.createElement('button');
                deleteBtn.className = 'admin-delete-btn';
                deleteBtn.onclick = () => deleteArticle(articleId);
                deleteBtn.title = '글 삭제';
                deleteBtn.innerHTML = '<i class="fas fa-trash"></i>';
                card.appendChild(deleteBtn);
            }
        });
    }
}

document.addEventListener('DOMContentLoaded', function() {
    bindArticleEvents();
});