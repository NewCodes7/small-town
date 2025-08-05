// 배너 슬라이드 관련 변수
let currentSlide = 0;
let totalSlides = 6; // 1 hero + 5 popular articles
let slideInterval;
let isHovered = false;
let manualControl = false;

// UserInfo 관리
let currentUser = null;

async function loadUserInfo() {
    try {
        const response = await fetch('/api/user-info', {
            credentials: 'same-origin'
        });
        if (response.ok) {
            currentUser = await response.json();
            console.log('Current user:', currentUser);
        }
    } catch (error) {
        console.error('사용자 정보 로드 실패:', error);
    }
}

class ArticleManager {
    constructor() {
        this.currentPage = 0;
        this.currentSort = 'latest';
        this.currentKeyword = '';
        this.currentRegions = [];
        this.currentView = 'list'; // 'list' or 'grouped'
        this.isLoading = false;
        this.cache = new Map();
        this.debounceTimer = null;
        this.init();
    }

    init() {
        // this.loadStateFromURL();
        this.bindEvents();
        // 초기 로드는 서버에서 렌더링된 상태이므로 생략
        this.bindArticleEvents();
    }

    loadStateFromURL() {
        const params = new URLSearchParams(window.location.search);
        this.currentPage = (parseInt(params.get('page')) - 1) || 0;
        if (this.currentPage < 0) this.currentPage = 0;
        this.currentSort = params.get('sort') || 'latest';
        this.currentKeyword = params.get('keyword') || '';
        this.currentRegions = params.getAll('regions') || [];
        
        // UI 상태 동기화
        this.updateUIFromState();
    }

    updateUIFromState() {
        // 검색 입력란 업데이트
        const searchInput = document.getElementById('searchInput');
        if (searchInput) {
            searchInput.value = this.currentKeyword;
        }

        // 정렬 버튼 상태 업데이트
        // setTimeout(() => {
        //     this.updateSortButtons();
        // }, 100);

        // 지역 필터 상태 업데이트
        this.currentRegions.forEach(region => {
            const checkbox = document.getElementById(region);
            if (checkbox) {
                checkbox.checked = true;
            }
        });
    }

    bindEvents() {
        // 검색 폼 이벤트
        const searchForm = document.getElementById('searchForm');
        if (searchForm) {
            searchForm.addEventListener('submit', (e) => {
                e.preventDefault();
                this.handleSearch();
            });
        }

        // 검색 입력 디바운싱
        const searchInput = document.getElementById('searchInput');
        if (searchInput) {
            searchInput.addEventListener('input', (e) => {
                this.debounceSearch(() => {
                    this.currentKeyword = e.target.value.trim();
                    this.currentPage = 0;
                    this.loadArticles();
                });
            });
        }

        // 정렬 버튼 이벤트 (이벤트 위임 방식)
        const sortButtons = document.querySelectorAll('.sort-btn');
        console.log('Found sort buttons:', sortButtons.length);
        // 직접 바인딩 시도
        sortButtons.forEach(btn => {
            console.log('Binding sort button:', btn.dataset.sort, btn);
            btn.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                console.log('Sort button clicked:', btn.dataset.sort);
                this.handleSortChange(btn.dataset.sort);
            });
        });
        // 이벤트 위임으로도 처리
        document.addEventListener('click', (e) => {
            if (e.target.classList.contains('sort-btn') || e.target.closest('.sort-btn')) {
                e.preventDefault();
                const sortBtn = e.target.classList.contains('sort-btn') ? e.target : e.target.closest('.sort-btn');
                const sort = sortBtn.dataset.sort;
                console.log('Sort button clicked via delegation:', sort);
                this.handleSortChange(sort);
            }
        });

        // 지역 필터 이벤트 (기존 함수 오버라이드)
        document.querySelectorAll('.region-checkbox').forEach(checkbox => {
            checkbox.addEventListener('change', () => {
                this.handleRegionChange();
            });
        });

        // 페이지네이션 이벤트 (동적 바인딩)
        document.addEventListener('click', (e) => {
            if (e.target.classList.contains('page-link') && e.target.dataset.page !== undefined) {
                e.preventDefault();
                const page = parseInt(e.target.dataset.page);
                if (!isNaN(page)) {
                    this.currentPage = page;
                    smoothScrollToElement('searchForm', -70);
                    this.loadArticles();
                }
            }
        });

        // 브라우저 뒤로가기/앞으로가기 처리
        window.addEventListener('popstate', () => {
            this.loadStateFromURL();
            this.loadArticles();
        });

        this.initViewBtnToggle();
    }

    initViewBtnToggle() {
        const groupedViewBtn = document.getElementById('groupedViewBtn');
        const articleListContainer = document.getElementById('article-list-container');
        const articleGroupedContainer = document.getElementById('article-grouped-container');

        // latestViewBtn.addEventListener('click', () => {
        //     this.currentView = 'list';

        //     latestViewBtn.classList.add('btn-primary');
        //     latestViewBtn.classList.remove('btn-secondary');
        //     groupedViewBtn.classList.add('btn-secondary');
        //     groupedViewBtn.classList.remove('btn-primary');

        //     articleListContainer.classList.remove('d-none');
        //     articleGroupedContainer.classList.add('d-none');

        //     this.loadArticles();
        // });

        groupedViewBtn.addEventListener('click', () => {
            const isGrouped = this.currentView === 'grouped';

            // 상태 토글
            this.currentView = isGrouped ? 'list' : 'grouped';

            // 버튼 스타일 토글
            groupedViewBtn.classList.toggle('btn-primary', !isGrouped);
            groupedViewBtn.classList.toggle('btn-secondary', isGrouped);

            // 컨테이너 토글
            articleListContainer.classList.toggle('d-none', !isGrouped);
            articleGroupedContainer.classList.toggle('d-none', isGrouped);

            this.loadArticles();
        });
    }

    debounceSearch(callback, delay = 500) {
        clearTimeout(this.debounceTimer);
        this.debounceTimer = setTimeout(callback, delay);
    }

    handleSearch() {
        const searchInput = document.getElementById('searchInput');
        if (searchInput) {
            this.currentKeyword = searchInput.value.trim();
            this.currentPage = 0;
            this.loadArticles();
        }
    }

    handleSortChange(sort) {
        console.log('handleSortChange called with:', sort, 'current:', this.currentSort);
        if (this.currentSort !== sort) {
            this.currentSort = sort;
            this.currentPage = 0;
            this.loadArticles();
        } else {
            // 같은 정렬이라도 버튼 상태는 업데이트
            this.updateSortButtons();
        }
    }

    handleRegionChange() {
        const checkedRegions = [];
        document.querySelectorAll('.region-checkbox:checked').forEach(checkbox => {
            checkedRegions.push(checkbox.value);
        });
        this.currentRegions = checkedRegions;
        this.currentPage = 0;
        this.loadArticles();
    }

    getCacheKey() {
        return `${this.currentView}-${this.currentPage}-${this.currentSort}-${this.currentKeyword}-${this.currentRegions.join(',')}`;
    }

    // 게시글을 로드하는 핵심 함수 
    async loadArticles() {
        if (this.isLoading) return;

        const cacheKey = this.getCacheKey();
        const cachedResult = this.cache.get(cacheKey);
        if (cachedResult) {
            if (this.currentView === 'grouped') {
                this.renderGroupedArticles(cachedResult.content);
            } else {
                this.renderArticles(cachedResult.content);
            }
            this.renderPagination(cachedResult);
            // this.updateURL();
            // this.updateSortButtons();
            return;
        }

        this.isLoading = true;
        this.showLoadingState();

        try {
            const params = new URLSearchParams({
                page: this.currentPage,
                sort: this.currentSort
            });

            if (this.currentKeyword) {
                params.set('keyword', this.currentKeyword);
            }

            this.currentRegions.forEach(region => {
                params.append('regions', region);
            });

            if (this.currentView === 'grouped') {
                params.set('view', 'grouped');
            }

            const response = await fetch(`/api/articles?${params}`);
            if (!response.ok) {
                throw new Error('Network response was not ok');
            }

            const data = await response.json();

            // 캐시 저장
            this.cache.set(cacheKey, data);

            if (this.currentView === 'grouped') {
                this.renderGroupedArticles(data.content);
            } else {
                this.renderArticles(data.content);
            }
            this.renderPagination(data);
            // this.updateURL();
            // this.updateSortButtons();

        } catch (error) {
            console.error('Error loading articles:', error);
            this.showErrorState();
        } finally {
            this.isLoading = false;
            this.hideLoadingState();
        }
    }

    renderGroupedArticles(groups) {
        const container = document.querySelector('#article-grouped-container');
        if (!container) return;

        if (groups.length === 0) {
            this.showEmptyState();
            return;
        }

        container.innerHTML = groups.map(group => {
            const firstArticle = group.articles[0];
            const childArticles = this.generateChildArticleHTML(group.articles.slice(1));
            return this.generateArticleHTML(firstArticle, childArticles);
        }).join('');

        this.bindArticleEvents();

        // 상대 시간 업데이트
        this.updateRelativeTimes();
    }

    renderArticles(articles) {
        const container = document.querySelector('.articles-container');
        if (!container) return;

        if (articles.length === 0) {
            this.showEmptyState();
            return;
        }

        container.innerHTML = articles.map(article => this.generateArticleHTML(article)).join('');
        this.bindArticleEvents();
        
        // 상대 시간 업데이트
        this.updateRelativeTimes();
        
        // 좋아요 상태 로드
        // this.loadLikeStatuses();
    }

    generateChildArticleHTML(childArticles) {
        return childArticles.map(child => {
            return `
                <div class="child">
                    <h6 style="margin: 0;">
                        <a href=${child.link} target="_blank" class="text-decoration-none" style="color: inherit;">
                            ${child.title}
                        </a>
                    </h6>
                    <!-- Company Info & Stats -->
                    <span class="child-date d-flex align-items-center flex-wrap">
                        <span class="d-flex align-items-center gap-3">
                            <small class="text-muted relative-time" data-date="${child.publishedAt}" title="${child.publishedAt}">${child.publishedAt}</small>
                        </span>
                    </span>
                </div>
            `
        }).join('');
    }

    generateArticleHTML(article, childArticles = "") {
        const thumbnailHTML = article.thumbnailImage ? 
            `<img src="${article.thumbnailImage}" alt="${article.title}" class="article-thumbnail">` :
            `<div class="article-thumbnail default-thumbnail">
                <div class="default-thumbnail-content">
                    <i class="fas fa-code"></i>
                    <span class="thumbnail-text">NewCodes</span>
                    <div class="thumbnail-pattern"></div>
                </div>
            </div>`;

        const tagsHTML = article.tags && article.tags.length > 0 ?
            `<div class="mt-auto">
                ${article.tags.map(tag => `<span class="badge tag-badge me-2">${tag.keyword}</span>`).join('')}
            </div>` : '';

        const adminDeleteButton = currentUser && currentUser.isAdmin ?
            `<button class="admin-delete-btn" onclick="deleteArticle(${article.id})" title="글 삭제">
                <i class="fas fa-trash"></i>
            </button>` : '';

        return `
            <div class="article-card" style="position: relative;" data-article-id=${article.id}>
                ${adminDeleteButton}
                <!-- Article Content (Left Side) -->
                <div class="article-content">
                    <!-- Title -->
                    <h5 class="mb-3 lh-base" style="color: var(--text-dark); font-weight: 700; font-size: 1.15rem;">
                        <a href="${article.link}" target="_blank" class="text-decoration-none" style="color: inherit;">
                            ${article.title}
                        </a>
                    </h5>
                    
                    <!-- Company Info & Stats -->
                    <div class="d-flex align-items-center flex-wrap">
                        <div class="d-flex align-items-center me-4">
                            <a href="/corporations/${article.corporation.id}" class="d-flex align-items-center text-decoration-none company-link" style="transition: all 0.2s ease;" onmouseover="this.style.opacity='0.7'" onmouseout="this.style.opacity='1'">
                                ${article.corporation.effectiveLogoUrl ? 
                                    `<img width="20px;" src="${article.corporation.effectiveLogoUrl}" alt="${article.corporation.name}" class="company-logo me-2" style="border-radius: 4px;">` : 
                                    ''}
                                <span class="fw-bold me-2" style="color: var(--primary-green); font-size: 0.9rem;">${article.corporation.name}</span>
                            </a>
                        </div>
                        <div class="d-flex align-items-center gap-3">
                            <small class="text-muted relative-time" data-date="${article.publishedAt}" title="${article.publishedAt}">${article.publishedAt}</small>
                        </div>
                    </div>
                    
                    <!-- Tags -->
                    ${tagsHTML}
                </div>
                
                <!-- Thumbnail Container (Right Side) -->
                <div class="article-thumbnail-container">
                    ${thumbnailHTML}
                </div>

                <!-- Child Container (Bottom Side) -->
                ${childArticles === "" ? "" : `<div class="child-container">
                    ${childArticles}
                </div>`}
            </div>
        `;
    }

    renderPagination(data) {
        const container = document.getElementById('paginationContainer');
        if (!container || data.totalPages <= 1) {
            if (container) container.innerHTML = '';
            return;
        }

        const currentPage = data.currentPage;
        const totalPages = data.totalPages;
        const maxPagesToShow = 5;
        let startPage, endPage;

        if (totalPages <= maxPagesToShow) {
            // 전체 페이지 수가 5개 이하일 경우
            startPage = 0;
            endPage = totalPages - 1;
        } else {
            // 전체 페이지 수가 5개 초과일 경우
            const maxPagesBeforeCurrent = Math.floor(maxPagesToShow / 2);
            const maxPagesAfterCurrent = Math.ceil(maxPagesToShow / 2) - 1;

            if (currentPage <= maxPagesBeforeCurrent) {
                startPage = 0;
                endPage = maxPagesToShow - 1;
            } else if (currentPage + maxPagesAfterCurrent >= totalPages) {
                startPage = totalPages - maxPagesToShow;
                endPage = totalPages - 1;
            } else {
                startPage = currentPage - maxPagesBeforeCurrent;
                endPage = currentPage + maxPagesAfterCurrent;
            }
        }

        let paginationHTML = `
            <ul class="pagination justify-content-center">
                <li class="page-item ${!data.hasPrevious ? 'disabled' : ''}">
                    <a class="page-link" href="#" data-page="${currentPage - 1}">
                        <i class="fas fa-chevron-left me-1"></i>이전
                    </a>
                </li>
        `;

        for (let i = startPage; i <= endPage; i++) {
            const isActive = i === currentPage;
            paginationHTML += `
                <li class="page-item ${isActive ? 'active' : ''}">
                    <a class="page-link" href="#" data-page="${i}">${i + 1}</a>
                </li>
            `;
        }

        paginationHTML += `
                <li class="page-item ${!data.hasNext ? 'disabled' : ''}">
                    <a class="page-link" href="#" data-page="${currentPage + 1}">
                        다음<i class="fas fa-chevron-right ms-1"></i>
                    </a>
                </li>
            </ul>
        `;

        container.innerHTML = `<nav>${paginationHTML}</nav>`;
    }

    updateURL() {
        const params = new URLSearchParams();
        if (this.currentPage > 0) params.set('page', this.currentPage + 1);
        if (this.currentSort !== 'latest') params.set('sort', this.currentSort);
        if (this.currentKeyword) params.set('keyword', this.currentKeyword);
        this.currentRegions.forEach(region => params.append('regions', region));
        params.set('view', this.currentView);

        const newURL = `${window.location.pathname}?${params.toString()}`;
        history.pushState(null, '', newURL);
    }

    updateSortButtons() {
        console.log('Updating sort buttons, currentSort:', this.currentSort);
        
        // data-sort 속성으로 버튼 찾기 (클래스가 동적으로 변경되므로)
        const latestBtn = document.querySelector('[data-sort="latest"]');
        const popularBtn = document.querySelector('[data-sort="popular"]');
        
        if (latestBtn) {
            const isLatestActive = this.currentSort === 'latest';
            // 기존 클래스 제거 후 새로 설정
            latestBtn.classList.remove('btn-primary', 'btn-secondary');
            latestBtn.classList.add(isLatestActive ? 'btn-primary' : 'btn-secondary');
            console.log('Latest button updated:', isLatestActive);
        }
        
        if (popularBtn) {
            const isPopularActive = this.currentSort === 'popular';
            // 기존 클래스 제거 후 새로 설정
            popularBtn.classList.remove('btn-primary', 'btn-secondary');
            popularBtn.classList.add(isPopularActive ? 'btn-primary' : 'btn-secondary');
            console.log('Popular button updated:', isPopularActive);
        }
    }

    showLoadingState() {
        const loadingState = document.getElementById('loadingState');
        const articlesContainer = document.querySelector('.articles-container');
        
        if (loadingState) {
            loadingState.classList.remove('d-none');
        }
        if (articlesContainer) {
            articlesContainer.style.opacity = '0.5';
        }
    }

    hideLoadingState() {
        const loadingState = document.getElementById('loadingState');
        const articlesContainer = document.querySelector('.articles-container');
        
        if (loadingState) {
            loadingState.classList.add('d-none');
        }
        if (articlesContainer) {
            articlesContainer.style.opacity = '1';
        }
    }

    showErrorState() {
        const container = document.querySelector('.articles-container');
        if (container) {
            container.innerHTML = `
                <div class="alert alert-warning text-center">
                    <i class="fas fa-exclamation-triangle mb-2"></i>
                    <p>글을 불러오는 중 오류가 발생했습니다.</p>
                    <button class="btn btn-primary" onclick="articleManager.loadArticles()">
                        다시 시도
                    </button>
                </div>
            `;
        }
    }

    showEmptyState() {
        const container = document.querySelector('.articles-container');
        if (container) {
            const isSearch = this.currentKeyword && this.currentKeyword.trim() !== '';
            
            container.innerHTML = `
                <div class="text-center py-5">
                    <div class="hero-section">
                        <i class="fas fa-${isSearch ? 'search' : 'seedling'} fa-4x mb-4" style="color: var(--primary-green); opacity: 0.5;"></i>
                        <h4 class="mb-3" style="color: var(--text-dark); font-weight: 700;">
                            ${isSearch ? `'${this.currentKeyword}' 검색 결과가 없어요` : '아직 등록된 글이 없어요'}
                        </h4>
                        <p class="text-muted mb-4">
                            ${isSearch ? '다른 키워드로 검색하거나 전체 글 목록을 확인해보세요.' : '새로운 기술 블로그 글이 곧 업데이트될 예정입니다.'}
                        </p>
                        ${isSearch ? '' : '<a href="/admin/corporations" class="btn btn-primary"><i class="fas fa-plus me-2"></i>기업 추가하기</a>'}
                    </div>
                </div>
            `;
        }
    }

    bindArticleEvents() {
        // 기존 좋아요 버튼 이벤트 바인딩
        // this.bindLikeButtons();
        
        // 기존 카드 클릭 이벤트 바인딩
        this.bindCardEvents('.article-card');
        this.bindCardEvents('.child');
    }

    bindLikeButtons() {
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
                        showLoginPopup();
                        return;
                    }
                    
                    if (response.ok) {
                        const data = await response.json();
                        likeCount.textContent = data.likeCount;
                        
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

    bindCardEvents(cardSelector) {
        document.querySelectorAll(cardSelector).forEach(card => {
            card.addEventListener('click', async function(e) {
                // 태그나 다른 링크, 좋아요 버튼, 회사 링크, 삭제 버튼을 클릭한 경우가 아니라면
                if (e.target.tagName !== 'A' && !e.target.closest('a') && !e.target.closest('.badge') && !e.target.closest('.like-button') && !e.target.closest('.company-link') && !e.target.closest('.admin-delete-btn')) {
                    const titleLink = this.querySelector('h5 a') || this.querySelector('h6 a');
                    const articleId = this.getAttribute('data-article-id');
                    if (titleLink) {
                        window.open(titleLink.href, '_blank');

                        // const response = await fetch(`/api/articles/${articleId}/view`, {
                        //     method: 'POST',
                        //     headers: {
                        //         'Content-Type': 'application/json',
                        //     },
                        //     credentials: 'same-origin',
                        //     redirect: 'manual'
                        // });

                        // if (response.ok) {
                        //     const data = await response.json();

                        //     if (data.incremented) {
                        //         const viewCount = this.querySelector('.view-count');
                        //         viewCount.textContent = data.viewCount;
                        //     }
                        // }
                    }
                }
            });
            
            card.style.cursor = 'pointer';
        });
    }

    updateRelativeTimes() {
        document.querySelectorAll('.relative-time').forEach(element => {
            const dateString = element.getAttribute('data-date');
            if (dateString) {
                element.textContent = getRelativeTime(dateString);
                element.title = formatDate(dateString);
            }
        });
    }

    async loadLikeStatuses() {
        const likeButtons = document.querySelectorAll('.like-button');
        
        for (const btn of likeButtons) {
            const articleId = btn.getAttribute('data-article-id');
            
            try {
                const response = await fetch(`/api/articles/${articleId}/like-status`, {
                    credentials: 'same-origin'
                });
                if (response.ok) {
                    const data = await response.json();
                    
                    if (data.authenticated && data.hasLiked) {
                        btn.classList.add('liked');
                    }
                    
                    const likeCount = btn.querySelector('.like-count');
                    likeCount.textContent = data.likeCount;
                }
            } catch (error) {
                console.error('좋아요 상태 로드 중 오류 발생:', error);
            }
        }
    }
}

// 전역 변수로 ArticleManager 인스턴스 저장
let articleManager;

// 글로벌 함수들
function handleSortClick(sort) {
    console.log('Global sort click:', sort);
    if (articleManager) {
        articleManager.handleSortChange(sort);
    } else {
        console.error('ArticleManager not initialized');
    }
}

// 글 삭제 함수
async function deleteArticle(articleId) {
    if (!currentUser || !currentUser.isAdmin) {
        alert('관리자 권한이 필요합니다.');
        return;
    }
    
    if (!confirm('정말로 이 글을 삭제하시겠습니까?')) {
        return;
    }
    
    try {
        const response = await fetch(`/api/admin/articles/${articleId}`, {
            method: 'DELETE',
            credentials: 'same-origin'
        });
        
        const data = await response.json();
        
        if (response.ok) {
            alert('글이 성공적으로 삭제되었습니다.');
            // 목록 새로고침
            if (articleManager) {
                articleManager.loadArticles();
            } else {
                window.location.reload();
            }
        } else {
            alert(data.message || '삭제 중 오류가 발생했습니다.');
        }
    } catch (error) {
        console.error('삭제 요청 중 오류:', error);
        alert('삭제 요청 중 오류가 발생했습니다.');
    }
}

function smoothScrollToElement(id, offset = 0) {
  const element = document.getElementById(id);
  if (!element) return;

  const y = element.getBoundingClientRect().top + window.pageYOffset + offset;

  window.scrollTo({
    top: y,
    behavior: 'smooth'
  });
}