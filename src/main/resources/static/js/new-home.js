function searchKeyword(keyword) {
    document.getElementById('searchInput').value = keyword;
    document.getElementById('searchForm').submit();
}

// localStorage 유틸리티 (비인증 사용자 좋아요)
const LIKED_ARTICLES_KEY = 'likedArticles';

function getLikedArticleIds() {
    try { return JSON.parse(localStorage.getItem(LIKED_ARTICLES_KEY)) || []; } catch { return []; }
}
function addToLikedArticles(articleId) {
    const ids = getLikedArticleIds();
    if (!ids.includes(articleId)) { ids.push(articleId); localStorage.setItem(LIKED_ARTICLES_KEY, JSON.stringify(ids)); }
}
function removeFromLikedArticles(articleId) {
    const ids = getLikedArticleIds().filter(id => id !== articleId);
    localStorage.setItem(LIKED_ARTICLES_KEY, JSON.stringify(ids));
}

// 인증 상태 캐시
let _cachedAuthState = null;

async function checkAuthenticated() {
    if (_cachedAuthState !== null) return _cachedAuthState;
    try {
        const res = await fetch('/api/user-info', { credentials: 'include' });
        const data = await res.json();
        _cachedAuthState = data.authenticated || false;
    } catch {
        _cachedAuthState = false;
    }
    return _cachedAuthState;
}

// 이번 주 인기글 캐러셀
let popularCurrentPage = 0;

function getPopularItemsPerPage() {
    const width = window.innerWidth;
    if (width <= 768) return 2;
    if (width <= 1024) return 3;
    return 4;
}

function slidePopular(direction) {
    if (window.innerWidth <= 768) return;
    const track = document.querySelector('.popular-track');
    if (!track) return;

    const totalItems = track.children.length;
    if (totalItems === 0) return;

    const itemsPerPage = getPopularItemsPerPage();
    const maxPage = Math.max(0, Math.ceil(totalItems / itemsPerPage) - 1);

    popularCurrentPage += direction;
    if (popularCurrentPage < 0) popularCurrentPage = 0;
    if (popularCurrentPage > maxPage) popularCurrentPage = maxPage;

    const style = window.getComputedStyle(track);
    const gap = parseFloat(style.gap) || 0;
    const cardWithGap = track.children[0].offsetWidth + gap;
    track.style.transform = `translateX(-${popularCurrentPage * itemsPerPage * cardWithGap}px)`;

    // Update button states
    const prevBtn = document.querySelector('.popular-prev');
    const nextBtn = document.querySelector('.popular-next');
    if (prevBtn) prevBtn.disabled = (popularCurrentPage === 0);
    if (nextBtn) nextBtn.disabled = (popularCurrentPage >= maxPage);
}

// 인기글 기간(주간/월간) 토글
let currentPopularPeriod = 'weekly';
let initialPopularTrackHtml = null;
const popularArticlesCache = {};

function buildPopularCardElement(article, rank) {
    const card = document.createElement('div');
    card.className = 'popular-card';
    card.setAttribute('data-detail-url', article.detailUrl || '');
    card.setAttribute('data-article-id', article.id);
    card.setAttribute('data-source', 'POPULAR_HOME');

    const rankSpan = document.createElement('span');
    rankSpan.className = 'popular-rank' + (rank < 3 ? ' top-rank' : '');
    rankSpan.textContent = String(rank + 1);
    card.appendChild(rankSpan);

    if (article.thumbnailImage) {
        const thumbDiv = document.createElement('div');
        thumbDiv.className = 'popular-card-thumbnail';
        const img = document.createElement('img');
        img.src = article.thumbnailImage;
        img.alt = '';
        img.loading = 'lazy';
        img.className = 'popular-card-thumbnail-image';
        img.setAttribute('onerror', 'handleThumbnailError(this)');
        thumbDiv.appendChild(img);
        card.appendChild(thumbDiv);
    } else {
        const thumbDiv = document.createElement('div');
        thumbDiv.className = 'popular-card-thumbnail default-thumbnail';
        thumbDiv.innerHTML = '<div class="default-thumbnail-content"><i class="fas fa-code"></i><div class="thumbnail-pattern"></div></div>';
        card.appendChild(thumbDiv);
    }

    const contentDiv = document.createElement('div');
    contentDiv.className = 'popular-card-content';

    const companyDiv = document.createElement('div');
    companyDiv.className = 'popular-card-company';
    const companyLink = document.createElement('a');
    companyLink.className = 'popular-card-company-link';
    companyLink.href = `/corporations/${article.corporation.id}`;
    if (article.corporation.effectiveLogoUrl) {
        const logoImg = document.createElement('img');
        logoImg.src = article.corporation.effectiveLogoUrl;
        logoImg.alt = article.corporation.name;
        logoImg.loading = 'lazy';
        logoImg.className = 'popular-card-company-logo';
        companyLink.appendChild(logoImg);
    }
    const companyName = document.createElement('span');
    companyName.className = 'popular-card-company-name';
    companyName.textContent = article.corporation.name;
    companyLink.appendChild(companyName);
    companyDiv.appendChild(companyLink);
    contentDiv.appendChild(companyDiv);

    const titleH3 = document.createElement('h3');
    titleH3.className = 'popular-card-title';
    const titleLink = document.createElement('a');
    titleLink.className = 'popular-card-title-link';
    titleLink.href = article.link;
    titleLink.target = '_blank';
    titleLink.rel = 'noopener noreferrer';
    titleLink.textContent = article.translatedTitle || article.title;
    titleLink.addEventListener('click', (e) => e.stopPropagation());
    titleH3.appendChild(titleLink);
    contentDiv.appendChild(titleH3);

    const metaDiv = document.createElement('div');
    metaDiv.className = 'popular-card-meta';

    const dateSpan = document.createElement('span');
    dateSpan.className = 'popular-card-date';
    dateSpan.innerHTML = '<i class="far fa-clock"></i>';
    const relativeTimeSpan = document.createElement('span');
    relativeTimeSpan.className = 'relative-time';
    relativeTimeSpan.setAttribute('data-date', article.publishedAt);
    relativeTimeSpan.textContent = article.publishedAt;
    dateSpan.appendChild(relativeTimeSpan);
    metaDiv.appendChild(dateSpan);

    const likeButton = document.createElement('button');
    likeButton.className = 'like-button';
    likeButton.setAttribute('data-article-id', article.id);
    likeButton.setAttribute('data-liked', article.isLiked === true);
    likeButton.innerHTML = '<i class="far fa-heart like-icon"></i><span class="like-count"></span>';
    likeButton.querySelector('.like-count').textContent = article.likeCount;
    likeButton.addEventListener('click', function(e) {
        e.stopPropagation();
        toggleArticleLike(likeButton, e);
    });
    metaDiv.appendChild(likeButton);

    contentDiv.appendChild(metaDiv);
    card.appendChild(contentDiv);

    return card;
}

function applyRelativeTimes(scope) {
    scope.querySelectorAll('.relative-time').forEach(element => {
        const dateString = element.getAttribute('data-date');
        if (dateString) {
            element.textContent = getRelativeTime(dateString);
            element.title = formatDate(dateString);
        }
    });
}

function renderPopularTrack(articles) {
    const track = document.querySelector('.popular-track');
    if (!track) return;

    track.innerHTML = '';

    if (!articles || articles.length === 0) {
        const emptyMessage = document.createElement('p');
        emptyMessage.className = 'popular-empty-message';
        emptyMessage.textContent = '인기글이 없습니다.';
        track.appendChild(emptyMessage);
    } else {
        articles.forEach((article, index) => {
            track.appendChild(buildPopularCardElement(article, index));
        });
    }

    popularCurrentPage = 0;
    track.style.transform = 'translateX(0)';
    slidePopular(0);
    applyRelativeTimes(track);
    loadLikeStatus();
}

async function loadPopularArticles(period) {
    if (popularArticlesCache[period]) {
        renderPopularTrack(popularArticlesCache[period]);
        return;
    }

    try {
        const response = await fetch(`/api/articles/popular?period=${period}&limit=8`);
        if (!response.ok) return;
        const articles = await response.json();
        popularArticlesCache[period] = articles;
        renderPopularTrack(articles);
    } catch (error) {
        console.error('인기글 조회 실패:', error);
    }
}

// 최신 기술 블로그 페이지네이션 (이전/다음 화살표)
let blogArticlesOffset = 0;
const BLOG_PAGE_SIZE = 8;

function buildBlogCardElement(article) {
    const card = document.createElement('div');
    card.className = 'blog-card';
    card.setAttribute('data-detail-url', article.detailUrl || '');
    card.setAttribute('data-link', article.link || '');
    card.setAttribute('data-article-id', article.id);
    card.setAttribute('data-source', 'LATEST_HOME');
    card.style.cursor = 'pointer';
    card.addEventListener('click', function() {
        const url = card.getAttribute('data-detail-url');
        if (url) window.location.href = url;
    });

    if (article.thumbnailImage) {
        const thumbDiv = document.createElement('div');
        thumbDiv.className = 'blog-card-thumbnail';
        thumbDiv.style.backgroundImage = `url(${article.thumbnailImage})`;
        thumbDiv.style.backgroundSize = 'cover';
        thumbDiv.style.backgroundPosition = 'center';
        card.appendChild(thumbDiv);
    } else {
        const thumbDiv = document.createElement('div');
        thumbDiv.className = 'blog-card-thumbnail default-thumbnail';
        thumbDiv.innerHTML = '<div class="default-thumbnail-content"><i class="fas fa-code"></i><div class="thumbnail-pattern"></div></div>';
        card.appendChild(thumbDiv);
    }

    const contentDiv = document.createElement('div');
    contentDiv.className = 'blog-card-content';

    const companyDiv = document.createElement('div');
    companyDiv.className = 'blog-card-company';
    const companyLink = document.createElement('a');
    companyLink.className = 'blog-card-company-link';
    companyLink.href = `/corporations/${article.corporation.id}`;
    companyLink.addEventListener('click', (e) => e.stopPropagation());
    if (article.corporation.effectiveLogoUrl) {
        const logoImg = document.createElement('img');
        logoImg.src = article.corporation.effectiveLogoUrl;
        logoImg.alt = article.corporation.name;
        logoImg.className = 'blog-card-company-logo';
        companyLink.appendChild(logoImg);
    }
    const companyName = document.createElement('span');
    companyName.className = 'blog-card-company-name';
    companyName.textContent = article.corporation.name;
    companyLink.appendChild(companyName);
    companyDiv.appendChild(companyLink);
    contentDiv.appendChild(companyDiv);

    const titleH3 = document.createElement('h3');
    titleH3.className = 'blog-card-title';
    const titleLink = document.createElement('a');
    titleLink.className = 'blog-card-title-link';
    titleLink.href = article.link;
    titleLink.target = '_blank';
    titleLink.rel = 'noopener noreferrer';
    titleLink.textContent = article.translatedTitle || article.title;
    titleLink.addEventListener('click', (e) => e.stopPropagation());
    titleH3.appendChild(titleLink);
    contentDiv.appendChild(titleH3);

    const metaDiv = document.createElement('div');
    metaDiv.className = 'blog-card-meta';

    const dateSpan = document.createElement('span');
    dateSpan.className = 'blog-card-date';
    dateSpan.innerHTML = '<i class="far fa-clock"></i>';
    const relativeTimeSpan = document.createElement('span');
    relativeTimeSpan.className = 'relative-time';
    relativeTimeSpan.setAttribute('data-date', article.publishedAt);
    relativeTimeSpan.textContent = article.publishedAt;
    dateSpan.appendChild(relativeTimeSpan);
    metaDiv.appendChild(dateSpan);

    const likeButton = document.createElement('button');
    likeButton.className = 'like-button';
    likeButton.setAttribute('data-article-id', article.id);
    likeButton.setAttribute('data-liked', article.isLiked === true);
    likeButton.innerHTML = '<i class="far fa-heart like-icon"></i><span class="like-count"></span>';
    likeButton.querySelector('.like-count').textContent = article.likeCount;
    likeButton.addEventListener('click', function(e) {
        e.stopPropagation();
        toggleArticleLike(likeButton, e);
    });
    metaDiv.appendChild(likeButton);

    contentDiv.appendChild(metaDiv);
    card.appendChild(contentDiv);

    return card;
}

function renderBlogGrid(articles) {
    const grid = document.getElementById('blogGrid');
    if (!grid) return;

    grid.innerHTML = '';
    (articles || []).forEach(article => {
        grid.appendChild(buildBlogCardElement(article));
    });

    applyRelativeTimes(grid);
    loadLikeStatus();
}

function updateBlogNavButtons(hasPrevious, hasNext, offset) {
    const prevBtn = document.querySelector('.blog-prev');
    const nextBtn = document.querySelector('.blog-next');
    const pageNumber = document.getElementById('blogPageNumber');
    if (prevBtn) prevBtn.disabled = !hasPrevious;
    if (nextBtn) nextBtn.disabled = !hasNext;
    if (pageNumber) pageNumber.textContent = Math.floor(offset / BLOG_PAGE_SIZE) + 1;
}

async function loadBlogArticles(offset) {
    try {
        const response = await fetch(`/api/articles/home-latest?offset=${offset}&limit=${BLOG_PAGE_SIZE}`);
        if (!response.ok) return;
        const data = await response.json();
        renderBlogGrid(data.articles);
        updateBlogNavButtons(data.hasPrevious, data.hasNext, offset);
        blogArticlesOffset = offset;
    } catch (error) {
        console.error('최신 블로그 글 조회 실패:', error);
    }
}

function positionPopularToggleIndicator(instant) {
    const indicator = document.querySelector('.popular-toggle-indicator');
    const activeBtn = document.querySelector('.popular-period-word.active');
    if (!indicator || !activeBtn) return;

    if (instant) indicator.style.transition = 'none';
    indicator.style.width = `${activeBtn.offsetWidth}px`;
    indicator.style.transform = `translate(${activeBtn.offsetLeft}px, -50%)`;
    if (instant) {
        indicator.getBoundingClientRect(); // reflow to flush the transition:none
        indicator.style.transition = '';
    }
}

async function switchPopularPeriod(period) {
    if (currentPopularPeriod === period) return;
    currentPopularPeriod = period;

    const weeklyBtn = document.getElementById('popularWeeklyBtn');
    const monthlyBtn = document.getElementById('popularMonthlyBtn');
    if (weeklyBtn) weeklyBtn.classList.toggle('active', period === 'weekly');
    if (monthlyBtn) monthlyBtn.classList.toggle('active', period === 'monthly');
    positionPopularToggleIndicator(false);

    const track = document.querySelector('.popular-track');
    if (!track) {
        await loadPopularArticles(period);
        return;
    }

    track.classList.add('popular-track-switching');
    await new Promise(resolve => setTimeout(resolve, 200)); // CSS의 0.2s fade-out과 일치

    // 최초 서버 렌더링된 주간 카드가 아직 있다면 재요청 없이 복원
    if (period === 'weekly' && !popularArticlesCache.weekly && initialPopularTrackHtml !== null) {
        track.innerHTML = initialPopularTrackHtml;
        popularCurrentPage = 0;
        track.style.transform = 'translateX(0)';
        slidePopular(0);
        applyRelativeTimes(track);
        loadLikeStatus();
    } else {
        await loadPopularArticles(period);
    }

    track.classList.remove('popular-track-switching');
}

// Reset carousel on window resize
window.addEventListener('resize', function() {
    popularCurrentPage = 0;
    const track = document.querySelector('.popular-track');
    if (track) {
        track.style.transform = 'translateX(0)';
    }
    slidePopular(0); // Update button states
    positionPopularToggleIndicator(true);
});

// 아티클 상세 페이지로 이동 (조회수 증가는 상세 페이지에서 처리)
function goToArticleDetail(articleId) {
    if (articleId) {
        window.location.href = `/articles/${articleId}`;
    }
}

// 아티클 링크 클릭 시 조회수 증가 후 외부 링크 열기 (레거시 호환용)
function openArticleLink(element) {
    const articleId = element.getAttribute('data-article-id');
    const articleLink = element.getAttribute('data-article-link');

    // 조회수 증가 API 호출 (비동기, 결과 대기 안 함)
    if (articleId) {
        fetch(`/api/articles/${articleId}/view`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            credentials: 'same-origin',
            redirect: 'manual'
        }).catch(error => {
            console.log('조회수 증가 요청 실패:', error);
        });
    }

    // 외부 링크 열기
    if (articleLink) {
        window.open(articleLink, '_blank');
    }
}

// localStorage 좋아요 마이그레이션 (블로그)
async function migrateLikesFromLocalStorage() {
    try {
        const likedIds = getLikedArticleIds();
        if (!likedIds || likedIds.length === 0) return null;
        const response = await fetch('/api/articles/migrate-likes', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
            body: JSON.stringify(likedIds)
        });
        if (response.ok) {
            localStorage.removeItem(LIKED_ARTICLES_KEY);
            return await response.json();
        }
        return null;
    } catch (error) {
        console.error('Failed to migrate article likes:', error);
        return null;
    }
}

// 블로그 좋아요 토글
async function toggleArticleLike(button, event) {
    event.preventDefault();
    event.stopPropagation();

    const articleId = button.getAttribute('data-article-id');
    const icon = button.querySelector('.like-icon');
    const countSpan = button.querySelector('.like-count');

    const authenticated = await checkAuthenticated();
    if (!authenticated) {
        const isCurrentlyLiked = button.classList.contains('liked');
        if (isCurrentlyLiked) {
            button.classList.remove('liked');
            icon.classList.replace('fas', 'far');
            removeFromLikedArticles(parseInt(articleId));
        } else {
            button.classList.add('liked');
            icon.classList.replace('far', 'fas');
            addToLikedArticles(parseInt(articleId));
            showLikeLoginModal();
        }
        return;
    }

    try {
        const response = await fetch(`/api/articles/${articleId}/like`, {
            method: 'POST',
            credentials: 'include'
        });

        if (response.ok) {
            const data = await response.json();

            // Update UI
            countSpan.textContent = data.likeCount;

            if (data.isLiked) {
                button.classList.add('liked');
                icon.classList.remove('far');
                icon.classList.add('fas');
            } else {
                button.classList.remove('liked');
                icon.classList.remove('fas');
                icon.classList.add('far');
            }
        } else if (response.status === 401) {
            alert('좋아요를 하려면 로그인이 필요합니다.');
        }
    } catch (error) {
        console.error('좋아요 처리 실패:', error);
    }
}

// 좋아요 상태 로드 (배치 API 호출)
async function loadLikeStatus() {
    const authenticated = await checkAuthenticated();

    // Load article like status
    const articleButtons = document.querySelectorAll('.like-button[data-article-id]');

    if (articleButtons.length > 0) {
        if (!authenticated) {
            const likedIds = getLikedArticleIds();
            articleButtons.forEach(button => {
                const articleId = parseInt(button.getAttribute('data-article-id'));
                if (likedIds.includes(articleId)) {
                    button.classList.add('liked');
                    const icon = button.querySelector('.like-icon');
                    if (icon) { icon.classList.remove('far'); icon.classList.add('fas'); }
                }
            });
        } else {
            const articleIds = Array.from(articleButtons).map(button =>
                parseInt(button.getAttribute('data-article-id'))
            );
            try {
                const response = await fetch('/api/articles/like-status/batch', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    credentials: 'include',
                    body: JSON.stringify({ articleIds })
                });

                if (response.ok) {
                    const data = await response.json();
                    const likeStatus = data.likeStatus;

                    articleButtons.forEach(button => {
                        const articleId = parseInt(button.getAttribute('data-article-id'));
                        const isLiked = likeStatus[articleId] || false;
                        const icon = button.querySelector('.like-icon');

                        if (isLiked) {
                            button.classList.add('liked');
                            if (icon) { icon.classList.remove('far'); icon.classList.add('fas'); }
                        }
                    });
                }
            } catch (error) {
                console.error('좋아요 상태 로드 실패:', error);
            }
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

// 테마 클릭 시 조회수 증가
function incrementThemeViewCount(themeId, element) {
    fetch(`/api/themes/${themeId}/view`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        credentials: 'same-origin',
        redirect: 'manual'
    }).then(response => {
        if (response.ok) {
            return response.json();
        }
    }).then(data => {
        if (data && data.incremented) {
            console.log(`테마 ${themeId} 조회수 증가:`, data.viewCount);
        }
    }).catch(error => {
        console.log('테마 조회수 증가 요청 실패:', error);
    });
}

// 페이지 로드 시 상대 시간 적용 및 좋아요 상태 로드
document.addEventListener('DOMContentLoaded', function() {
    // 인기글 캐러셀 초기화
    const prevBtn = document.querySelector('.popular-prev');
    const nextBtn = document.querySelector('.popular-next');
    if (prevBtn) prevBtn.addEventListener('click', function() { slidePopular(-1); });
    if (nextBtn) nextBtn.addEventListener('click', function() { slidePopular(1); });

    const popularTrack = document.querySelector('.popular-track');
    if (popularTrack) {
        initialPopularTrackHtml = popularTrack.innerHTML;
        popularTrack.addEventListener('click', function(e) {
            if (e.target.closest('.popular-card-company-link')) return;
            const card = e.target.closest('.popular-card');
            if (card) {
                const url = card.getAttribute('data-detail-url');
                if (url) window.location.href = url;
            }
        });
    }
    slidePopular(0);

    // 최신 기술 블로그 페이지네이션 (이전/다음 화살표)
    const blogPrevBtn = document.querySelector('.blog-prev');
    const blogNextBtn = document.querySelector('.blog-next');
    if (blogPrevBtn) blogPrevBtn.addEventListener('click', function() {
        loadBlogArticles(Math.max(0, blogArticlesOffset - BLOG_PAGE_SIZE));
    });
    if (blogNextBtn) blogNextBtn.addEventListener('click', function() {
        loadBlogArticles(blogArticlesOffset + BLOG_PAGE_SIZE);
    });

    // 인기글 주간/월간 토글
    const popularWeeklyBtn = document.getElementById('popularWeeklyBtn');
    const popularMonthlyBtn = document.getElementById('popularMonthlyBtn');
    if (popularWeeklyBtn) popularWeeklyBtn.addEventListener('click', function() { switchPopularPeriod('weekly'); });
    if (popularMonthlyBtn) popularMonthlyBtn.addEventListener('click', function() { switchPopularPeriod('monthly'); });
    positionPopularToggleIndicator(true);

    // 상대 시간 표시
    applyRelativeTimes(document);

    // 좋아요 상태 로드 + OAuth 로그인 후 localStorage 마이그레이션
    loadLikeStatus();
    (async () => {
        const authenticated = await checkAuthenticated();
        if (authenticated) {
            if (getLikedArticleIds().length > 0) await migrateLikesFromLocalStorage();
        }
    })();

    // 검색 자동완성 초기화 (searchAutocomplete.js 모듈 사용)
    if (typeof SearchAutocomplete !== 'undefined') {
        const autocomplete = new SearchAutocomplete({
            searchInputId: 'searchInput',
            dropdownId: 'autocompleteDropdown',
            formId: 'searchForm',
            autocompleteApiUrl: '/api/autocomplete',
            searchHistoryKey: 'small_town_search_history',
            supportTheme: true,
            corporationUrlPattern: '/corporations/{id}',
            debounceDelay: 100
        });
        autocomplete.init();
    }

    // 테마 클릭 이벤트 리스너 추가
    document.querySelectorAll('.theme-collection').forEach(element => {
        element.addEventListener('click', function(e) {
            const themeId = this.getAttribute('href').split('/').pop().split('?')[0];
            if (themeId) {
                incrementThemeViewCount(themeId, this);
            }
        });
    });
});

// ===== 검색 자동완성 기능 =====
// searchAutocomplete.js 모듈로 이전됨 (SearchAutocomplete 클래스 사용)

// ===== 관리자 추천 검색어 편집 모달 =====
(function () {
    const overlay = document.getElementById('keywordEditModal');
    if (!overlay) return; // 관리자가 아닐 때는 요소 자체가 없음

    const openBtn = document.getElementById('openKeywordModal');
    const closeBtn = document.getElementById('closeKeywordModal');
    const cancelBtn = document.getElementById('cancelKeywordEdit');
    const saveBtn = document.getElementById('saveKeywordEdit');
    const textarea = document.getElementById('keywordTextarea');

    function openModal() {
        fetch('/api/admin/suggested-search-terms', { credentials: 'include' })
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    textarea.value = (data.keywords || []).join('\n');
                }
                overlay.style.display = 'flex';
                document.body.style.overflow = 'hidden';
            })
            .catch(() => {
                textarea.value = '';
                overlay.style.display = 'flex';
                document.body.style.overflow = 'hidden';
            });
    }

    function closeModal() {
        overlay.style.display = 'none';
        document.body.style.overflow = '';
    }

    function saveKeywords() {
        const keywords = textarea.value
            .split('\n')
            .map(k => k.trim())
            .filter(k => k.length > 0)
            .slice(0, 8);

        saveBtn.disabled = true;
        saveBtn.textContent = '저장 중...';

        fetch('/api/admin/suggested-search-terms', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
            body: JSON.stringify({ keywords })
        })
        .then(res => res.json())
        .then(data => {
            if (data.success) {
                // DOM에서 keyword-chip 영역 즉시 교체
                const container = document.querySelector('.suggested-keywords');
                const existingChips = container.querySelectorAll('.keyword-chip');
                existingChips.forEach(el => el.remove());

                (data.keywords || []).forEach(kw => {
                    const btn = document.createElement('button');
                    btn.type = 'button';
                    btn.className = 'keyword-chip';
                    btn.textContent = kw;
                    btn.onclick = () => searchKeyword(kw);
                    container.appendChild(btn);
                });
                closeModal();
            } else {
                alert('저장 실패: ' + (data.message || '알 수 없는 오류'));
            }
        })
        .catch(() => alert('저장 중 오류가 발생했습니다.'))
        .finally(() => {
            saveBtn.disabled = false;
            saveBtn.textContent = '저장';
        });
    }

    openBtn.addEventListener('click', openModal);
    closeBtn.addEventListener('click', closeModal);
    cancelBtn.addEventListener('click', closeModal);
    saveBtn.addEventListener('click', saveKeywords);

    overlay.addEventListener('click', function (e) {
        if (e.target === overlay) closeModal();
    });

    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' && overlay.style.display !== 'none') closeModal();
    });
}());
