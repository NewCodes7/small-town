function bindArticleEvents() {
    initPagination();
    bindMoreButtonEvents();
}

// '더보기' 버튼 클릭 이벤트
function bindMoreButtonEvents() {
    document.querySelectorAll('.more-corporation-articles').forEach(more => {
        more.addEventListener('click', function(e) {
            e.preventDefault();
            window.location.href = more.querySelector('a');
        })
    })
}

// localStorage 유틸리티
const LIKED_ARTICLES_KEY = 'likedArticles';

function getLikedArticleIds() {
    try {
        const stored = localStorage.getItem(LIKED_ARTICLES_KEY);
        return stored ? JSON.parse(stored) : [];
    } catch (error) {
        console.error('Error reading liked articles:', error);
        return [];
    }
}

function saveLikedArticleIds(ids) {
    try {
        localStorage.setItem(LIKED_ARTICLES_KEY, JSON.stringify(ids));
    } catch (error) {
        console.error('Error saving liked articles:', error);
    }
}

function addToLikedArticles(articleId) {
    const likedIds = getLikedArticleIds();
    const numericId = parseInt(articleId);
    if (!likedIds.includes(numericId)) {
        likedIds.push(numericId);
        saveLikedArticleIds(likedIds);
    }
}

function removeFromLikedArticles(articleId) {
    const likedIds = getLikedArticleIds();
    const numericId = parseInt(articleId);
    const filtered = likedIds.filter(id => id !== numericId);
    saveLikedArticleIds(filtered);
}

// localStorage 좋아요 마이그레이션 (블로그)
async function migrateLikesFromLocalStorage() {
    try {
        const stored = localStorage.getItem(LIKED_ARTICLES_KEY);

        if (!stored) {
            return null; // 저장된 좋아요가 없으면 null 반환
        }

        const likedIds = JSON.parse(stored);

        if (!likedIds || likedIds.length === 0) {
            return null; // 빈 배열이면 null 반환
        }

        // 마이그레이션 API 호출
        const response = await fetch('/api/articles/migrate-likes', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            credentials: 'include',
            body: JSON.stringify(likedIds)
        });

        if (response.ok) {
            const data = await response.json();
            console.log(`Successfully migrated ${data.totalCount} article likes`);
            // 마이그레이션 성공 후 localStorage 클리어
            localStorage.removeItem(LIKED_ARTICLES_KEY);
            return data;
        }
        return null;
    } catch (error) {
        console.error('Failed to migrate article likes:', error);
        // 마이그레이션 실패해도 로그인은 계속 진행
        return null;
    }
}

// localStorage 좋아요 마이그레이션 (유튜브)
async function migrateVideoLikesFromLocalStorage() {
    try {
        const LIKED_VIDEOS_KEY = 'likedVideos';
        const stored = localStorage.getItem(LIKED_VIDEOS_KEY);

        if (!stored) {
            return null; // 저장된 좋아요가 없으면 null 반환
        }

        const likedIds = JSON.parse(stored);

        if (!likedIds || likedIds.length === 0) {
            return null; // 빈 배열이면 null 반환
        }

        // 마이그레이션 API 호출
        const response = await fetch('/video/api/videos/migrate-likes', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            credentials: 'include',
            body: JSON.stringify(likedIds)
        });

        if (response.ok) {
            const data = await response.json();
            console.log(`Successfully migrated ${data.totalCount} video likes`);
            // 마이그레이션 성공 후 localStorage 클리어
            localStorage.removeItem(LIKED_VIDEOS_KEY);
            return data;
        }
        return null;
    } catch (error) {
        console.error('Failed to migrate video likes:', error);
        // 마이그레이션 실패해도 로그인은 계속 진행
        return null;
    }
}

// 페이지 로드 후 상대 시간 적용 및 좋아요 상태 로드
async function initPagination() {
    // 사용자 정보 로드
    // await loadUserInfo();
    document.querySelectorAll('.relative-time').forEach(element => {
        const dateString = element.getAttribute('data-date');
        if (dateString) {
            // 상대 시간 표시
            element.textContent = getRelativeTime(dateString);
            // 툴팁에 포맷된 날짜 표시
            element.title = formatDate(dateString);
        }
    });
    
    // 떠다니는 로고는 CSS에서 위치가 고정되므로 JavaScript 설정 불필요
    
    // 기본 썸네일 설정
    setupDefaultThumbnails();
    
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
                    // localStorage에 저장된 좋아요 마이그레이션 (블로그 + 유튜브)
                    const [articleResult, videoResult] = await Promise.all([
                        migrateLikesFromLocalStorage(),
                        migrateVideoLikesFromLocalStorage()
                    ]);

                    messageDiv.textContent = '로그인 성공! 페이지를 새로고침합니다.';
                    messageDiv.className = 'alert alert-success';
                    messageDiv.classList.remove('d-none');

                    // 마이그레이션 결과 모달 표시
                    if ((articleResult?.totalCount || 0) + (videoResult?.totalCount || 0) > 0) {
                        setTimeout(() => {
                            // 로그인 모달 닫기
                            const loginModal = bootstrap.Modal.getInstance(document.getElementById('loginModal'));
                            if (loginModal) {
                                loginModal.hide();
                            }

                            // 마이그레이션 결과 모달 표시
                            showMigrationModal(articleResult, videoResult);

                            // 모달 닫힐 때 페이지 새로고침
                            const migrationModalEl = document.getElementById('migrationModal');
                            migrationModalEl.addEventListener('hidden.bs.modal', function () {
                                window.location.reload();
                            }, { once: true });
                        }, 1000);
                    } else {
                        // 마이그레이션된 항목이 없으면 바로 새로고침
                        setTimeout(() => {
                            window.location.reload();
                        }, 1000);
                    }
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
    
    // 윈도우 리사이즈 시 CSS 미디어 쿼리가 자동으로 처리하므로 JavaScript 처리 불필요
}

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

// positionFloatingLogos 함수 제거됨 - CSS에서 고정 위치 사용

// 뷰 토글 버튼 초기화
function initViewToggle() {
    const groupedViewBtn = document.getElementById('groupedViewBtn');
    const listViewBtn = document.getElementById('listViewBtn');

    if (groupedViewBtn) {
        groupedViewBtn.addEventListener('click', function() {
            updateViewParam('grouped');
        });
    }

    if (listViewBtn) {
        listViewBtn.addEventListener('click', function() {
            updateViewParam('list');
        });
    }
}

// 지역 토글 버튼 초기화
function initRegionToggle() {
    const regionAllBtn = document.getElementById('regionAllBtn');
    const regionDomesticBtn = document.getElementById('regionDomesticBtn');
    const regionOverseasBtn = document.getElementById('regionOverseasBtn');

    if (regionAllBtn) {
        regionAllBtn.addEventListener('click', function() {
            updateRegionParam(null);
        });
    }

    if (regionDomesticBtn) {
        regionDomesticBtn.addEventListener('click', function() {
            updateRegionParam('domestic');
        });
    }

    if (regionOverseasBtn) {
        regionOverseasBtn.addEventListener('click', function() {
            updateRegionParam('overseas');
        });
    }
}

// 뷰 파라미터 업데이트 및 페이지 리다이렉트
function updateViewParam(viewType) {
    const url = new URL(window.location.href);
    url.searchParams.set('view', viewType);
    // 페이지를 처음으로 리셋
    url.searchParams.set('page', '0');
    window.location.href = url.toString();
}

// 지역 파라미터 업데이트 및 페이지 리다이렉트
function updateRegionParam(region) {
    const url = new URL(window.location.href);
    // 기존 regions 파라미터 삭제
    url.searchParams.delete('regions');
    // 새 지역 파라미터 설정 (전체가 아닌 경우에만)
    if (region) {
        url.searchParams.set('regions', region);
    }
    // 페이지를 처음으로 리셋
    url.searchParams.set('page', '0');
    window.location.href = url.toString();
}

// 정렬 드롭다운 초기화
function initSortDropdown() {
    const sortOptions = document.querySelectorAll('.sort-option');
    const sortLabel = document.getElementById('sortLabel');

    // 현재 URL에서 sort 파라미터 가져오기
    const url = new URL(window.location.href);
    const currentSort = url.searchParams.get('sort') || 'latest';

    // 정렬 레이블 업데이트
    const sortLabels = {
        'relevance': '적합도순',
        'latest': '최신순',
        'oldest': '오래된순'
    };

    if (sortLabel) {
        sortLabel.textContent = sortLabels[currentSort] || '최신순';
    }

    // 각 정렬 옵션에 클릭 이벤트 추가
    sortOptions.forEach(option => {
        option.addEventListener('click', function(e) {
            e.preventDefault();
            const sortType = this.getAttribute('data-sort');
            updateSortParam(sortType);
        });
    });
}

// 정렬 파라미터 업데이트 및 페이지 리다이렉트
function updateSortParam(sortType) {
    const url = new URL(window.location.href);
    url.searchParams.set('sort', sortType);
    // 페이지를 처음으로 리셋
    url.searchParams.set('page', '0');
    window.location.href = url.toString();
}

// OAuth 알림창 닫기
function closeOAuthNotice() {
    const notice = document.getElementById('oauthNotice');
    if (notice) {
        notice.classList.add('hidden');
        // localStorage에 저장하여 다시 표시하지 않음
        localStorage.setItem('oauthNoticeClosed', 'true');
    }
}

// OAuth 알림창 초기화
function initOAuthNotice() {
    const notice = document.getElementById('oauthNotice');
    if (notice) {
        const isClosed = localStorage.getItem('oauthNoticeClosed');
        if (isClosed === 'true') {
            notice.classList.add('hidden');
        }
    }
}

document.addEventListener('DOMContentLoaded', function() {
    bindArticleEvents();
    initViewToggle();
    initRegionToggle();
    initSortDropdown();
    initOAuthNotice();
});
