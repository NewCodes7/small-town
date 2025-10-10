// 배너 슬라이드 관련 변수
let currentSlide = 0;
let totalSlides = 6; // 1 hero + 5 popular articles
let slideInterval;
let isHovered = false;
let manualControl = false;

class ArticleManager {
    constructor() {
        this.currentPage = 0;
        this.currentSort = 'latest';
        this.currentKeyword = '';
        this.currentRegions = [];
        this.currentView = 'grouped'; // 'list' or 'grouped'
        this.isLoading = false;
        this.cache = new Map();
        this.debounceTimer = null;
        this.init();
    }

    init() {
        this.loadStateFromURL();
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

        // 키워드나 카테고리가 있으면 자동으로 리스트 뷰로 설정
        const hasKeyword = this.currentKeyword && this.currentKeyword.trim() !== '';
        const hasCategories = params.getAll('category').length > 0;

        if (hasKeyword || hasCategories) {
            this.currentView = 'list';
        } else {
            this.currentView = params.get('view') || 'grouped';
        }

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

        // 기업별 묶기 버튼 업데이트
        const groupedViewBtn = document.getElementById('groupedViewBtn');
        if (groupedViewBtn) {
            const isGrouped = this.currentView === 'grouped';
            groupedViewBtn.classList.toggle('active', isGrouped);
        }
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
        // const searchInput = document.getElementById('searchInput');
        // if (searchInput) {
        //     searchInput.addEventListener('input', (e) => {
        //         this.debounceSearch(() => {
        //             this.currentKeyword = e.target.value.trim();
        //             this.currentPage = 0;
        //             this.loadArticles();
        //         });
        //     });
        // }

        // 정렬 버튼 이벤트 (이벤트 위임 방식)
        const sortButtons = document.querySelectorAll('.sort-btn');
        // 직접 바인딩 시도
        sortButtons.forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                this.handleSortChange(btn.dataset.sort);
            });
        });
        // 이벤트 위임으로도 처리
        document.addEventListener('click', (e) => {
            if (e.target.classList.contains('sort-btn') || e.target.closest('.sort-btn')) {
                e.preventDefault();
                const sortBtn = e.target.classList.contains('sort-btn') ? e.target : e.target.closest('.sort-btn');
                const sort = sortBtn.dataset.sort;
                this.handleSortChange(sort);
            }
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
        this.initRegionFilters();
    }

    initViewBtnToggle() {
        const groupedViewBtn = document.getElementById('groupedViewBtn');
        if (!groupedViewBtn) return;

        groupedViewBtn.addEventListener('click', () => {
            const isGrouped = this.currentView === 'grouped';
            // 상태 토글
            this.currentView = isGrouped ? 'list' : 'grouped';
            this.loadArticles();
        });
    }

    initRegionFilters() {
        // 지역 필터 체크박스 이벤트
        document.querySelectorAll('.filter-checkbox').forEach(checkbox => {
            checkbox.addEventListener('change', () => {
                this.handleRegionChange();
            });
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

            // 키워드 검색 시 리스트 뷰로 자동 전환
            if (this.currentKeyword && this.currentKeyword.length > 0) {
                this.currentView = 'list';
            }

            this.loadArticles();
        }
    }

    handleSortChange(sort) {
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
        document.querySelectorAll('.filter-checkbox:checked').forEach(checkbox => {
            checkedRegions.push(checkbox.value);
        });
        this.currentRegions = checkedRegions;
        this.currentPage = 0;
        this.loadArticles();
    }

    getCacheKey() {
        const currentUrl = new URL(window.location);
        const currentCategories = currentUrl.searchParams.getAll('category');
        return `${this.currentView}-${this.currentPage}-${this.currentSort}-${this.currentKeyword}-${this.currentRegions.join(',')}-${currentCategories.join(',')}`;
    }

    // 게시글을 로드하는 핵심 함수 
    async loadArticles() {
        if (this.isLoading) return;

        // const cacheKey = this.getCacheKey();
        // const cachedResult = this.cache.get(cacheKey);
        // if (cachedResult) {
        //     if (this.currentView === 'grouped') {
        //         this.renderGroupedArticles(cachedResult.content);
        //     } else {
        //         this.renderArticles(cachedResult.content);
        //     }
        //     this.renderPagination(cachedResult);
        //     // this.updateURL();
        //     // this.updateSortButtons();
        //     return;
        // }

        this.isLoading = true;
        this.showLoadingState();

        try {
            const params = new URLSearchParams();

            if (this.currentPage != 0) {
                params.set('page', this.currentPage);
            }

            if (this.currentKeyword) {
                params.set('keyword', this.currentKeyword);
            }

            this.currentRegions.forEach(region => {
                params.append('regions', region);
            });

            // 현재 선택된 카테고리 유지
            const currentUrl = new URL(window.location);
            const currentCategories = currentUrl.searchParams.getAll('category');
            currentCategories.forEach(category => {
                params.append('category', category);
            });

            // 키워드나 카테고리가 있으면 자동으로 리스트 뷰로 전환
            if (this.currentKeyword || currentCategories.length > 0) {
                this.currentView = 'list';
                params.set('view', 'list');
            } else if (this.currentView === 'grouped') {
                params.set('view', 'grouped');
            } else {
                params.set('view', 'list');
            }

            window.location.href = `?${params}`;

            // const response = await fetch(`/api/articles?${params}`);
            // if (!response.ok) {
            //     throw new Error('Network response was not ok');
            // }

            // const data = await response.json();

            // // 캐시 저장
            // this.cache.set(cacheKey, data);

            // if (this.currentView === 'grouped') {
            //     this.renderGroupedArticles(data.content);
            // } else {
            //     this.renderArticles(data.content);
            // }
            // this.renderPagination(data);
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
        const container = document.querySelector('.articles-list-container');
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

        // 현재 페이지를 중심으로 앞뒤 2개씩 총 5개 페이지 표시 (마지막 페이지 제외)
        const pagesBeforeCurrent = 2;
        const pagesAfterCurrent = 2;

        startPage = Math.max(0, currentPage - pagesBeforeCurrent);
        endPage = totalPages <= 1 ? 0 :
                 totalPages <= maxPagesToShow ? totalPages - 1 :
                 Math.min(totalPages - 2, currentPage + pagesAfterCurrent); // 마지막 페이지 제외

        // 5개 페이지를 유지하되 마지막 페이지는 절대 포함하지 않음
        if (endPage - startPage + 1 < maxPagesToShow && endPage < totalPages - 2) {
            if (startPage === 0) {
                endPage = Math.min(totalPages - 2, startPage + maxPagesToShow - 1);
            } else {
                startPage = Math.max(0, endPage - maxPagesToShow + 1);
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
        
        // 현재 선택된 카테고리 유지
        const currentUrl = new URL(window.location);
        const currentCategories = currentUrl.searchParams.getAll('category');
        currentCategories.forEach(category => {
            params.append('category', category);
        });
        
        params.set('view', this.currentView);

        const newURL = `${window.location.pathname}?${params.toString()}`;
        history.pushState(null, '', newURL);
    }

    updateSortButtons() {
        // data-sort 속성으로 버튼 찾기 (클래스가 동적으로 변경되므로)
        const latestBtn = document.querySelector('[data-sort="latest"]');
        const popularBtn = document.querySelector('[data-sort="popular"]');
        
        if (latestBtn) {
            const isLatestActive = this.currentSort === 'latest';
            // 기존 클래스 제거 후 새로 설정
            latestBtn.classList.remove('btn-primary', 'btn-secondary');
            latestBtn.classList.add(isLatestActive ? 'btn-primary' : 'btn-secondary');
        }
        
        if (popularBtn) {
            const isPopularActive = this.currentSort === 'popular';
            // 기존 클래스 제거 후 새로 설정
            popularBtn.classList.remove('btn-primary', 'btn-secondary');
            popularBtn.classList.add(isPopularActive ? 'btn-primary' : 'btn-secondary');
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
        // this.bindCardEvents('.child');
    }

    bindCardEvents(cardSelector) {
        document.querySelectorAll(cardSelector).forEach(card => {
            card.addEventListener('click', (e) => {
                // 기업 링크, 배지, 좋아요 버튼, 삭제 버튼을 클릭한 경우는 기본 동작 허용
                if (e.target.closest('.badge') || e.target.closest('.like-button')
                    || e.target.closest('.company-link') || e.target.closest('.admin-delete-btn')) {
                    return; // 기본 동작 허용 (링크 이동 등)
                }

                e.preventDefault();

                let parent = e.target;
                while (parent) {
                    if (parent.classList.contains('article-card') 
                        || parent.classList.contains('child')) {
                        const titleLink = parent.querySelector('h5 a') || parent.querySelector('h6 a');
                        const articleId = parent.getAttribute('data-article-id');
                        window.open(titleLink.href, '_blank');
                        fetch(`/api/articles/${articleId}/view`, {
                            method: 'POST',
                            headers: {
                                'Content-Type': 'application/json',
                            },
                            credentials: 'same-origin',
                            redirect: 'manual'
                        });
                        break;
                    }
                    parent = parent.parentElement;
                }
            });
            
            card.style.cursor = 'pointer';
        });
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
    if (articleManager) {
        articleManager.handleSortChange(sort);
    } else {
        console.error('ArticleManager not initialized');
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