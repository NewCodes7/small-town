const LIKED_ARTICLES_KEY = 'likedArticles';

// 페이지 초기화
async function initLikedArticlesPage() {
    try {
        const response = await fetch('/api/user-info', { credentials: 'include' });
        const data = await response.json();

        if (data.authenticated) {
            await loadAuthenticatedLikedArticles();
        } else {
            await loadAnonymousLikedArticles();
        }
    } catch (error) {
        console.error('Failed to initialize liked articles page:', error);
        showError('페이지를 불러오는 중 오류가 발생했습니다.');
    }
}

// 로그인 사용자: API에서 조회 (아티클 + 비디오)
async function loadAuthenticatedLikedArticles(page = 0) {
    try {
        const response = await fetch(`/api/liked-items?page=${page}&size=10`, {
            credentials: 'include'
        });

        if (response.status === 401) {
            // 인증 실패 시 비로그인 모드로 전환
            await loadAnonymousLikedArticles();
            return;
        }

        if (!response.ok) {
            throw new Error('Failed to fetch liked items');
        }

        const data = await response.json();

        if (data.content && data.content.length > 0) {
            renderItems(data.content);
            renderPagination(data);
            showArticleList();
        } else {
            showEmptyState(true);
        }
    } catch (error) {
        console.error('Error loading authenticated liked items:', error);
        showError('좋아요한 글을 불러오는 중 오류가 발생했습니다.');
    }
}

// 비로그인 사용자: localStorage 사용
async function loadAnonymousLikedArticles() {
    try {
        // 비로그인 사용자 안내 배너 표시
        document.getElementById('anonymousNotice').classList.remove('d-none');

        const likedIds = getLikedArticleIds();

        if (likedIds.length === 0) {
            showEmptyState(false);
            return;
        }

        const response = await fetch(`/api/articles/batch?ids=${likedIds.join(',')}`);

        if (!response.ok) {
            throw new Error('Failed to fetch articles by IDs');
        }

        const articles = await response.json();

        if (articles && articles.length > 0) {
            renderArticles(articles);
            showArticleList();
        } else {
            showEmptyState(false);
        }
    } catch (error) {
        console.error('Error loading anonymous liked articles:', error);
        showError('좋아요한 글을 불러오는 중 오류가 발생했습니다.');
    }
}

// localStorage 유틸리티
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

// 아티클과 비디오 렌더링
function renderItems(items) {
    const container = document.querySelector('.articles-list-container');
    container.innerHTML = '';

    items.forEach(item => {
        const card = item.type === 'video' ? createVideoCard(item) : createArticleCard(item);
        container.appendChild(card);
    });

    // 좋아요 버튼 이벤트 바인딩
    bindLikeButtons();

    // 상대 시간 표시
    updateRelativeTimes();
}

// 하위 호환성을 위한 함수
function renderArticles(articles) {
    renderItems(articles);
}

// Corporation 정보 추출 헬퍼 함수 (중첩 구조와 flat 구조 모두 지원)
function getCorporationName(item) {
    return item.corporation?.name || item.corporationName || '';
}

function getCorporationLogoUrl(item) {
    return item.corporation?.logoS3Url || item.corporation?.logoUrl || item.corporationLogoUrl || '';
}

// 아티클 카드 생성
function createArticleCard(article) {
    const wrapper = document.createElement('div');
    wrapper.className = 'article-card-wrapper';

    wrapper.innerHTML = `
        <div class="article-card" data-article-id="${article.id}">
            <!-- Article Content (Left Side) -->
            <div class="article-content">
                <!-- Title -->
                <h5 class="mb-3 lh-base" style="color: var(--text-dark); font-weight: 700; font-size: 1.15rem;">
                    <a href="${article.link}"
                       target="_blank"
                       class="text-decoration-none title-link"
                       style="color: inherit;">
                        <span>${escapeHtml(article.translatedTitle || article.title)}</span>
                    </a>
                </h5>

                <!-- Company Info & Stats -->
                <div class="d-flex align-items-center flex-wrap">
                    <div class="d-flex align-items-center me-4">
                        <div class="d-flex align-items-center text-decoration-none company-link">
                            ${getCorporationLogoUrl(article) ?
                                `<img width="20px;" src="${getCorporationLogoUrl(article)}"
                                     alt="${escapeHtml(getCorporationName(article))}"
                                     class="company-logo me-2" style="border-radius: 4px;">` : ''
                            }
                            <span class="fw-bold me-2" style="color: var(--primary-green); font-size: 0.9rem;">
                                ${escapeHtml(getCorporationName(article))}
                            </span>
                        </div>
                    </div>
                    <div class="d-flex align-items-center gap-3">
                        <small class="text-muted relative-time"
                               data-date="${article.publishedAt}"
                               title="${article.publishedAt}">
                            ${article.publishedAt}
                        </small>
                        <button class="like-button liked" data-article-id="${article.id}">
                            <i class="fas fa-heart like-icon"></i>
                            <span class="like-count">${article.likeCount || 0}</span>
                        </button>
                    </div>
                </div>
            </div>

            <!-- Thumbnail Container (Right Side) -->
            <div class="article-thumbnail-container">
                ${article.thumbnailUrl ?
                    `<img src="${article.thumbnailUrl}"
                          alt="${escapeHtml(article.title)}"
                          width="300"
                          height="155"
                          class="article-thumbnail">` :
                    `<div class="article-thumbnail default-thumbnail">
                        <div class="default-thumbnail-content">
                            <i class="fas fa-code"></i>
                            <div class="thumbnail-pattern"></div>
                        </div>
                    </div>`
                }
            </div>
        </div>
    `;

    return wrapper;
}

// 비디오 카드 생성
function createVideoCard(video) {
    const wrapper = document.createElement('div');
    wrapper.className = 'article-card-wrapper';

    wrapper.innerHTML = `
        <div class="article-card" data-video-id="${video.id}">
            <!-- Video Content (Left Side) -->
            <div class="article-content">
                <!-- Title with Video Badge -->
                <h5 class="mb-3 lh-base" style="color: var(--text-dark); font-weight: 700; font-size: 1.15rem;">
                    <span class="badge bg-danger me-2" style="font-size: 0.7rem; vertical-align: middle;">
                        <i class="fab fa-youtube"></i>
                    </span>
                    <a href="${video.link}"
                       target="_blank"
                       class="text-decoration-none title-link"
                       style="color: inherit;">
                        <span>${escapeHtml(video.translatedTitle || video.title)}</span>
                    </a>
                </h5>

                <!-- Company Info & Stats -->
                <div class="d-flex align-items-center flex-wrap">
                    <div class="d-flex align-items-center me-4">
                        <div class="d-flex align-items-center text-decoration-none company-link">
                            ${getCorporationLogoUrl(video) ?
                                `<img width="20px;" src="${getCorporationLogoUrl(video)}"
                                     alt="${escapeHtml(getCorporationName(video))}"
                                     class="company-logo me-2" style="border-radius: 4px;">` : ''
                            }
                            <span class="fw-bold me-2" style="color: var(--primary-green); font-size: 0.9rem;">
                                ${escapeHtml(getCorporationName(video))}
                            </span>
                        </div>
                    </div>
                    <div class="d-flex align-items-center gap-3">
                        <small class="text-muted relative-time"
                               data-date="${video.publishedAt}"
                               title="${video.publishedAt}">
                            ${video.publishedAt}
                        </small>
                        <button class="like-button liked" data-video-id="${video.id}" data-item-type="video">
                            <i class="fas fa-heart like-icon"></i>
                            <span class="like-count">${video.likeCount || 0}</span>
                        </button>
                    </div>
                </div>
            </div>

            <!-- Thumbnail Container (Right Side) -->
            <div class="article-thumbnail-container">
                ${video.thumbnailUrl ?
                    `<img src="${video.thumbnailUrl}"
                          alt="${escapeHtml(video.title)}"
                          width="300"
                          height="155"
                          class="article-thumbnail">` :
                    `<div class="article-thumbnail default-thumbnail">
                        <div class="default-thumbnail-content">
                            <i class="fab fa-youtube"></i>
                            <div class="thumbnail-pattern"></div>
                        </div>
                    </div>`
                }
            </div>
        </div>
    `;

    return wrapper;
}

// 페이지네이션 렌더링 (로그인 사용자만)
function renderPagination(data) {
    const { currentPage, totalPages, hasPrevious, hasNext } = data;

    if (totalPages <= 1) {
        document.getElementById('paginationContainer').classList.add('d-none');
        return;
    }

    const paginationList = document.getElementById('paginationList');
    paginationList.innerHTML = '';

    // Previous button
    const prevLi = document.createElement('li');
    prevLi.className = `page-item ${!hasPrevious ? 'disabled' : ''}`;
    prevLi.innerHTML = `
        <a class="page-link" href="#" data-page="${currentPage - 1}">
            <i class="fas fa-chevron-left me-1"></i><span class="pagination-text">이전</span>
        </a>
    `;
    paginationList.appendChild(prevLi);

    // Page numbers (최대 5개 표시)
    const maxPagesToShow = 5;
    const halfRange = 2;
    let startPage, endPage;

    if (totalPages <= maxPagesToShow) {
        startPage = 0;
        endPage = totalPages - 1;
    } else if (currentPage <= halfRange) {
        startPage = 0;
        endPage = maxPagesToShow - 1;
    } else if (currentPage >= totalPages - halfRange - 1) {
        startPage = totalPages - maxPagesToShow;
        endPage = totalPages - 1;
    } else {
        startPage = currentPage - halfRange;
        endPage = currentPage + halfRange;
    }

    for (let i = startPage; i <= endPage; i++) {
        const pageLi = document.createElement('li');
        pageLi.className = `page-item page-number ${i === currentPage ? 'active' : ''}`;
        pageLi.innerHTML = `<a class="page-link" href="#" data-page="${i}">${i + 1}</a>`;
        paginationList.appendChild(pageLi);
    }

    // Next button
    const nextLi = document.createElement('li');
    nextLi.className = `page-item ${!hasNext ? 'disabled' : ''}`;
    nextLi.innerHTML = `
        <a class="page-link" href="#" data-page="${currentPage + 1}">
            <span class="pagination-text">다음</span><i class="fas fa-chevron-right ms-1"></i>
        </a>
    `;
    paginationList.appendChild(nextLi);

    // 페이지네이션 이벤트 바인딩
    paginationList.querySelectorAll('a.page-link').forEach(link => {
        link.addEventListener('click', function(e) {
            e.preventDefault();
            if (this.parentElement.classList.contains('disabled')) return;

            const page = parseInt(this.getAttribute('data-page'));
            loadAuthenticatedLikedArticles(page);
            window.scrollTo({ top: 0, behavior: 'smooth' });
        });
    });

    document.getElementById('paginationContainer').classList.remove('d-none');
}

// 좋아요 버튼 이벤트 바인딩
function bindLikeButtons() {
    document.querySelectorAll('.like-button').forEach(btn => {
        btn.addEventListener('click', async function(e) {
            e.preventDefault();
            e.stopPropagation();

            const articleId = this.getAttribute('data-article-id');
            const videoId = this.getAttribute('data-video-id');
            const itemType = this.getAttribute('data-item-type') || 'article';

            const id = parseInt(articleId || videoId);
            const endpoint = itemType === 'video'
                ? `/video/api/videos/${id}/like`
                : `/api/articles/${id}/like`;

            try {
                const response = await fetch(endpoint, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    credentials: 'same-origin'
                });

                if (response.ok) {
                    const data = await response.json();

                    // 좋아요 취소된 경우 해당 카드 제거
                    if (!data.isLiked) {
                        this.closest('.article-card-wrapper').remove();

                        // 마지막 카드가 제거되면 빈 상태 표시
                        if (document.querySelectorAll('.article-card-wrapper').length === 0) {
                            showEmptyState(!window.isAuthenticated);
                        }

                        // localStorage 업데이트 (비로그인 사용자, 아티클만)
                        if (!window.isAuthenticated && itemType === 'article') {
                            removeFromLikedArticles(id);
                        }
                    }
                }
            } catch (error) {
                console.error('좋아요 처리 중 오류 발생:', error);
            }
        });
    });
}

function removeFromLikedArticles(articleId) {
    const likedIds = getLikedArticleIds();
    const filtered = likedIds.filter(id => id !== articleId);
    saveLikedArticleIds(filtered);
}

// 상대 시간 업데이트
function updateRelativeTimes() {
    document.querySelectorAll('.relative-time').forEach(element => {
        const dateString = element.getAttribute('data-date');
        if (dateString) {
            element.textContent = getRelativeTime(dateString);
            element.title = formatDate(dateString);
        }
    });
}

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

    if (diffInSeconds < minute) return '방금 전';
    if (diffInSeconds < hour) return `${Math.floor(diffInSeconds / minute)}분 전`;
    if (diffInSeconds < day) return `${Math.floor(diffInSeconds / hour)}시간 전`;
    if (diffInSeconds < week) return `${Math.floor(diffInSeconds / day)}일 전`;
    if (diffInSeconds < month) return `${Math.floor(diffInSeconds / week)}주일 전`;
    if (diffInSeconds < year) return `${Math.floor(diffInSeconds / month)}달 전`;
    return `${Math.floor(diffInSeconds / year)}년 전`;
}

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

// UI 상태 변경
function showArticleList() {
    document.getElementById('loadingState').classList.add('d-none');
    document.getElementById('emptyState').classList.add('d-none');
    document.getElementById('article-list-container').classList.remove('d-none');
}

function showEmptyState(isAuthenticated) {
    document.getElementById('loadingState').classList.add('d-none');
    document.getElementById('article-list-container').classList.add('d-none');
    document.getElementById('paginationContainer').classList.add('d-none');

    const emptyState = document.getElementById('emptyState');
    const title = document.getElementById('emptyStateTitle');
    const message = document.getElementById('emptyStateMessage');

    if (isAuthenticated) {
        title.textContent = '아직 좋아요한 글이 없습니다';
        message.textContent = '마음에 드는 글에 하트를 눌러보세요!';
    } else {
        title.textContent = '좋아요한 글이 없습니다';
        message.textContent = '마음에 드는 글에 하트를 눌러보세요!';
    }

    emptyState.classList.remove('d-none');
}

function showError(message) {
    document.getElementById('loadingState').classList.add('d-none');
    document.getElementById('article-list-container').classList.add('d-none');
    document.getElementById('paginationContainer').classList.add('d-none');

    const emptyState = document.getElementById('emptyState');
    const title = document.getElementById('emptyStateTitle');
    const messageEl = document.getElementById('emptyStateMessage');

    title.textContent = '오류가 발생했습니다';
    messageEl.textContent = message;

    emptyState.classList.remove('d-none');
}

// HTML 이스케이프
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// 페이지 로드 시 초기화
document.addEventListener('DOMContentLoaded', function() {
    initLikedArticlesPage();
});
