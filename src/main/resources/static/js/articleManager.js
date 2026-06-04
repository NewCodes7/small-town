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
        this.currentContentTypes = [];
        this.currentView = 'grouped'; // 'list' or 'grouped'
        this.isLoading = false;
        this.debounceTimer = null;
        this.userExplicitViewChange = false; // 사용자가 명시적으로 뷰를 변경했는지 추적
        this.userExplicitSortChange = false; // 사용자가 명시적으로 정렬을 변경했는지 추적
        this.isAdmin = false;
        this.init();
    }

    init() {
        this.loadStateFromURL();
        this.bindEvents();
        // 초기 로드는 서버에서 렌더링된 상태이므로 생략
        this.bindArticleEvents();
        // URL에 keyword가 있으면 aiSummaryManager 초기화 후 AI 요약 시작
        if (this.currentKeyword) {
            setTimeout(() => window.aiSummaryManager?.start(this.currentKeyword), 0);
        }
    }

    loadStateFromURL() {
        const params = new URLSearchParams(window.location.search);
        const urlPage = parseInt(params.get('page'));
        this.currentPage = isNaN(urlPage) ? 0 : Math.max(0, urlPage - 1);
        if (this.currentPage < 0) this.currentPage = 0;
        const sortFromURL = params.get('sort');
        this.currentSort = sortFromURL || ((params.get('keyword') || '').trim() !== '' ? 'relevance' : 'latest');
        this.currentKeyword = params.get('keyword') || '';
        this.currentRegions = params.getAll('regions') || [];
        this.currentContentTypes = params.getAll('contentTypes') || [];

        // URL에서 뷰 파라미터 읽기
        const urlView = params.get('view') || 'grouped';

        // 키워드나 카테고리가 있으면 자동으로 리스트 뷰로 설정
        // 단, URL에 명시적으로 view 파라미터가 있으면 그것을 우선
        const hasKeyword = this.currentKeyword && this.currentKeyword.trim() !== '';
        const hasCategories = params.getAll('category').length > 0;

        if (params.has('view')) {
            // URL에 view 파라미터가 명시되어 있으면 그것을 사용 (사용자가 명시적으로 선택한 것)
            this.currentView = urlView;
        } else if (hasKeyword || hasCategories) {
            // view 파라미터가 없고 키워드/카테고리가 있으면 list로
            this.currentView = 'list';
        } else {
            // 둘 다 없으면 grouped
            this.currentView = 'grouped';
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

        // 콘텐츠 타입 필터 상태 업데이트
        this.currentContentTypes.forEach(contentType => {
            const checkbox = document.getElementById(contentType + 'Filter');
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
            const pageLink = e.target.closest('.page-link');
            if (pageLink && pageLink.dataset.page !== undefined) {
                e.preventDefault();
                const page = parseInt(pageLink.dataset.page);
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
        const listViewBtn = document.getElementById('listViewBtn');

        if (groupedViewBtn) {
            groupedViewBtn.addEventListener('click', () => {
                if (this.currentView !== 'grouped') {
                    this.currentView = 'grouped';
                    this.userExplicitViewChange = true;
                    this.currentPage = 0;
                    this.loadArticles();
                }
            });
        }

        if (listViewBtn) {
            listViewBtn.addEventListener('click', () => {
                if (this.currentView !== 'list') {
                    this.currentView = 'list';
                    this.userExplicitViewChange = true;
                    this.currentPage = 0;
                    this.loadArticles();
                }
            });
        }
    }

    initRegionFilters() {
        // 지역 필터 체크박스 이벤트
        document.querySelectorAll('.filter-checkbox').forEach(checkbox => {
            checkbox.addEventListener('change', () => {
                this.handleRegionChange();
            });
        });

        // 지역 토글 버튼 이벤트
        const regionAllBtn = document.getElementById('regionAllBtn');
        const regionDomesticBtn = document.getElementById('regionDomesticBtn');
        const regionOverseasBtn = document.getElementById('regionOverseasBtn');

        if (regionAllBtn) {
            regionAllBtn.addEventListener('click', () => {
                this.currentRegions = [];
                this.currentPage = 0;
                this.loadArticles();
            });
        }

        if (regionDomesticBtn) {
            regionDomesticBtn.addEventListener('click', () => {
                this.currentRegions = ['domestic'];
                this.currentPage = 0;
                this.loadArticles();
            });
        }

        if (regionOverseasBtn) {
            regionOverseasBtn.addEventListener('click', () => {
                this.currentRegions = ['overseas'];
                this.currentPage = 0;
                this.loadArticles();
            });
        }
    }

    debounceSearch(callback, delay = 500) {
        clearTimeout(this.debounceTimer);
        this.debounceTimer = setTimeout(callback, delay);
    }

    handleSearch() {
        const searchInput = document.getElementById('searchInput');
        if (searchInput) {
            const newKeyword = searchInput.value.trim();

            // 사용자가 명시적으로 sort를 변경하지 않았다면, keyword 유무에 따라 서버 기본값 적용
            if (!this.userExplicitSortChange) {
                this.currentSort = newKeyword ? 'relevance' : 'latest';
            }

            this.currentKeyword = newKeyword;
            this.currentPage = 0;

            // 키워드 검색 시 리스트 뷰로 자동 전환
            // 단, 사용자가 명시적으로 뷰를 변경한 경우는 예외
            if (this.currentKeyword && !this.userExplicitViewChange) {
                this.currentView = 'list';
            }

            // 검색어가 비어있으면 명시적 변경 플래그 초기화
            if (!this.currentKeyword || this.currentKeyword.length === 0) {
                this.userExplicitViewChange = false;
                this.userExplicitSortChange = false;
            }

            this.loadArticles();

            if (this.currentKeyword) {
                window.aiSummaryManager?.start(this.currentKeyword);
            } else {
                window.aiSummaryManager?.hide();
            }
        }
    }

    handleSortChange(sort) {
        if (this.currentSort !== sort) {
            this.currentSort = sort;
            this.userExplicitSortChange = true;
            this.currentPage = 0;
            this.loadArticles();
        } else {
            // 같은 정렬이라도 버튼 상태는 업데이트
            this.updateSortButtons();
        }
    }

    handleRegionChange() {
        const checkedRegions = [];
        const checkedContentTypes = [];

        document.querySelectorAll('.filter-checkbox:checked').forEach(checkbox => {
            if (checkbox.name === 'regions') {
                checkedRegions.push(checkbox.value);
            } else if (checkbox.name === 'contentTypes') {
                checkedContentTypes.push(checkbox.value);
            }
        });

        this.currentRegions = checkedRegions;
        this.currentContentTypes = checkedContentTypes;
        this.currentPage = 0;
        this.loadArticles();
    }

    // 게시글을 로드하는 핵심 함수 (CSR: fetch API로 JSON 데이터 로드)
    async loadArticles() {
        if (this.isLoading) return;

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

            // sort 파라미터 추가 (항상 명시적으로 전송하여 서버 기본값과 불일치 방지)
            params.set('sort', this.currentSort);

            this.currentRegions.forEach(region => {
                params.append('regions', region);
            });

            this.currentContentTypes.forEach(contentType => {
                params.append('contentTypes', contentType);
            });

            // 현재 선택된 카테고리 유지
            const currentUrl = new URL(window.location);
            const currentCategories = currentUrl.searchParams.getAll('category');
            currentCategories.forEach(category => {
                params.append('category', category);
            });

            // 뷰 파라미터 설정
            if (this.userExplicitViewChange) {
                params.set('view', this.currentView);
            } else if (this.currentKeyword || currentCategories.length > 0) {
                this.currentView = 'list';
                params.set('view', 'list');
            } else {
                params.set('view', this.currentView);
            }

            // CSR: fetch API로 JSON 데이터 로드
            const response = await fetch(`/api/search/articles?${params.toString()}`);
            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }
            const data = await response.json();

            // admin 여부 갱신
            this.isAdmin = data.isAdmin === true;

            // UI 업데이트
            if (data.view === 'grouped') {
                this.renderGroupedArticles(data.content);
            } else {
                this.renderArticles(data.content);
            }
            this.renderPagination(data);
            this.updateEmptyState(data);
            this.updateURL();

            // 뷰 토글 버튼 상태 업데이트
            const groupedViewBtn = document.getElementById('groupedViewBtn');
            const listViewBtn = document.getElementById('listViewBtn');
            if (groupedViewBtn) {
                groupedViewBtn.classList.toggle('active', this.currentView === 'grouped');
            }
            if (listViewBtn) {
                listViewBtn.classList.toggle('active', this.currentView !== 'grouped');
            }

            // 정렬 드롭다운 표시/숨김 (검색어가 있을 때만 표시)
            const sortControls = document.querySelector('.sort-controls');
            if (sortControls) {
                sortControls.style.display = (this.currentKeyword && this.currentKeyword.trim() !== '') ? '' : 'none';
            }

        } catch (error) {
            console.error('Error loading articles:', error);
            this.showErrorState();
        } finally {
            this.isLoading = false;
            this.hideLoadingState();
        }
    }

    renderGroupedArticles(groups) {
        // Switch containers: show grouped, hide list
        let groupedContainer = document.querySelector('#article-grouped-container');
        const listContainer = document.querySelector('#article-list-container');
        if (listContainer) listContainer.style.display = 'none';

        if (!groupedContainer) {
            // Create grouped container if it doesn't exist
            groupedContainer = document.createElement('div');
            groupedContainer.id = 'article-grouped-container';
            groupedContainer.className = 'grouped-view';
            const paginationContainer = document.getElementById('paginationContainer');
            if (paginationContainer) {
                paginationContainer.parentNode.insertBefore(groupedContainer, paginationContainer);
            }
        }
        groupedContainer.style.display = '';

        if (groups.length === 0) {
            groupedContainer.innerHTML = '';
            return;
        }

        groupedContainer.innerHTML = '<div class="articles-container">' +
            groups.map(group => {
                const firstArticle = group.articles[0];
                const childArticles = group.articles.slice(1);
                return this.generateGroupedArticleHTML(group.corporation, firstArticle, childArticles);
            }).join('') + '</div>';

        this.bindArticleEvents();
        this.updateRelativeTimes();
        this.loadLikeStatuses();
    }

    renderArticles(articles) {
        // Switch containers: show list, hide grouped
        const groupedContainer = document.querySelector('#article-grouped-container');
        if (groupedContainer) groupedContainer.style.display = 'none';

        let listContainer = document.querySelector('#article-list-container');
        if (!listContainer) {
            // Create list container if it doesn't exist
            listContainer = document.createElement('div');
            listContainer.id = 'article-list-container';
            const paginationContainer = document.getElementById('paginationContainer');
            if (paginationContainer) {
                paginationContainer.parentNode.insertBefore(listContainer, paginationContainer);
            }
        }
        listContainer.style.display = '';

        if (articles.length === 0) {
            listContainer.innerHTML = '';
            return;
        }

        listContainer.innerHTML = '<div class="articles-list-container">' +
            articles.map(article => this.generateListArticleHTML(article)).join('') + '</div>';

        this.bindArticleEvents();
        this.updateRelativeTimes();
        this.loadLikeStatuses();
    }

    generateAdminScorePanelHTML(article) {
        if (!this.isAdmin || article.finalScore == null) return '';
        const fmt4 = v => v != null ? v.toFixed(4) : '-';
        const fmt2 = v => v != null ? v.toFixed(2) : '-';
        const fmt1 = v => v != null ? v.toFixed(1) : '-';
        const bmNorm = fmt2(article.normalizedBm25Score);
        const vecNorm = fmt2(article.normalizedVectorScore);
        return `<div class="admin-score-panel" onclick="event.stopPropagation()">
            <div class="admin-score-final"><i class="fas fa-star"></i> Final ${fmt4(article.finalScore)}</div>
            <table class="admin-score-table">
                <thead><tr><th></th><th>raw</th><th>norm</th><th>w</th><th>#</th></tr></thead>
                <tbody>
                    <tr>
                        <td class="admin-label-bm25">BM25</td>
                        <td>${fmt1(article.bm25Score)}</td>
                        <td>${bmNorm}</td>
                        <td>0.5</td>
                        <td>${article.bm25Rank != null ? article.bm25Rank : '-'}</td>
                    </tr>
                    <tr>
                        <td class="admin-label-vec">Vector</td>
                        <td>${fmt2(article.vectorScore)}</td>
                        <td>${vecNorm}</td>
                        <td>0.5</td>
                        <td>${article.vectorRank != null ? article.vectorRank : '-'}</td>
                    </tr>
                </tbody>
            </table>
        </div>`;
    }

    generateListArticleHTML(article) {
        const title = article.translatedTitle && article.translatedTitle.length > 0
            ? article.translatedTitle : article.title;
        const thumbnailHTML = article.thumbnailImage
            ? `<img src="${this.escapeHtml(article.thumbnailImage)}" alt="${this.escapeHtml(article.title)}" width="300" height="155" class="article-thumbnail">`
            : `<div class="article-thumbnail default-thumbnail"><div class="default-thumbnail-content"><i class="fas fa-code"></i><div class="thumbnail-pattern"></div></div></div>`;
        const logoHTML = article.corporation && article.corporation.effectiveLogoUrl
            ? `<img width="20px;" src="${this.escapeHtml(article.corporation.effectiveLogoUrl)}" alt="${this.escapeHtml(article.corporation.name)}" class="company-logo me-2" style="border-radius: 4px;">`
            : '';
        const corpName = article.corporation ? article.corporation.name : '';
        const corpId = article.corporation ? article.corporation.id : '';
        const isLiked = article.isLiked === true;
        const adminScoreHTML = this.generateAdminScorePanelHTML(article);

        return `<div class="article-card-wrapper">
            <div class="article-card" data-article-id="${article.id}" data-detail-url="${this.escapeHtml(article.detailUrl || '')}"
                 style="cursor: pointer;">
                <div class="article-content">
                    <h5 class="mb-3 lh-base" style="color: var(--text-dark); font-weight: 700; font-size: 1.15rem;">
                        <span class="title-link">${this.escapeHtml(title)}</span>
                    </h5>
                    <div class="d-flex align-items-center flex-wrap">
                        <div class="d-flex align-items-center me-4">
                            <a href="/corporations/${corpId}" class="d-flex align-items-center text-decoration-none company-link" onclick="event.stopPropagation()">
                                ${logoHTML}
                                <span class="fw-bold me-2" style="color: var(--primary-green); font-size: 0.9rem;">${this.escapeHtml(corpName)}</span>
                            </a>
                        </div>
                        <div class="d-flex align-items-center gap-2">
                            <small class="text-muted relative-time" data-date="${article.publishedAt || ''}" title="${article.publishedAt || ''}" style="white-space: nowrap;">${article.publishedAt || ''}</small>
                            <button class="like-button" data-article-id="${article.id}" data-liked="${isLiked}">
                                <i class="fas fa-heart like-icon"></i>
                            </button>
                        </div>
                    </div>
                </div>
                <div class="article-thumbnail-container">${thumbnailHTML}</div>
                ${adminScoreHTML}
            </div>
        </div>`;
    }

    generateGroupedArticleHTML(corporation, firstArticle, childArticles) {
        const title = firstArticle.translatedTitle && firstArticle.translatedTitle.length > 0
            ? firstArticle.translatedTitle : firstArticle.title;
        const thumbnailHTML = firstArticle.thumbnailImage
            ? `<img src="${this.escapeHtml(firstArticle.thumbnailImage)}" alt="${this.escapeHtml(firstArticle.title)}" width="300" height="155" class="article-thumbnail">`
            : `<div class="article-thumbnail default-thumbnail"><div class="default-thumbnail-content"><i class="fas fa-code"></i><div class="thumbnail-pattern"></div></div></div>`;
        const logoHTML = corporation && corporation.effectiveLogoUrl
            ? `<img width="20px;" src="${this.escapeHtml(corporation.effectiveLogoUrl)}" alt="${this.escapeHtml(corporation.name)}" class="company-logo me-2" style="border-radius: 4px;">`
            : '';
        const corpName = corporation ? corporation.name : '';
        const corpId = corporation ? corporation.id : '';
        const isLiked = firstArticle.isLiked === true;

        const adminScoreHTML = this.generateAdminScorePanelHTML(firstArticle);

        let childHTML = '';
        if (childArticles && childArticles.length > 0) {
            const childItems = childArticles.map(child => {
                const childTitle = child.translatedTitle && child.translatedTitle.length > 0
                    ? child.translatedTitle : child.title;
                const childIsLiked = child.isLiked === true;
                const childAdminScoreHTML = (this.isAdmin && child.finalScore != null)
                    ? `<div class="admin-score-panel compact" onclick="event.stopPropagation()">
                        <span class="admin-score-final"><i class="fas fa-star"></i> ${child.finalScore.toFixed(4)}</span>
                        <span class="admin-score-inline">
                            <span class="admin-label-bm25">BM</span>${child.bm25Score != null ? child.bm25Score.toFixed(1) : '-'}
                            <span class="admin-label-vec">Vec</span>${child.vectorScore != null ? child.vectorScore.toFixed(2) : '-'}
                        </span>
                    </div>`
                    : '';
                return `<div class="child" data-article-id="${child.id}" data-detail-url="${this.escapeHtml(child.detailUrl || '')}"
                             onclick="if(!event.target.closest('.like-button')) { event.stopPropagation(); window.location.href=this.getAttribute('data-detail-url'); }" style="cursor: pointer;">
                    <h6 class="fw-bold d-flex align-items-center" style="margin: 0;">
                        <span class="title-link">${this.escapeHtml(childTitle)}</span>
                    </h6>
                    <span class="child-date d-flex align-items-center flex-wrap">
                        <span class="d-flex align-items-center gap-2">
                            <small class="text-muted relative-time" data-date="${child.publishedAt || ''}" title="${child.publishedAt || ''}" style="white-space: nowrap;">${child.publishedAt || ''}</small>
                            ${childAdminScoreHTML}
                            <button class="like-button" data-article-id="${child.id}" data-liked="${childIsLiked}">
                                <i class="fas fa-heart like-icon"></i>
                                <span class="like-count">${child.likeCount || 0}</span>
                            </button>
                        </span>
                    </span>
                </div>`;
            }).join('');

            childHTML = `<div class="child-container">
                ${childItems}
                <div class="child more-corporation-articles" onclick="event.stopPropagation()">
                    <a href="/corporations/${corpId}" class="me-2 d-flex align-items-center text-decoration-none">
                        <span class="fw-bold me-2" style="font-size: 1rem;">더보기</span>
                    </a>
                </div>
            </div>`;
        }

        return `<div class="article-card-wrapper">
            <div class="article-card" data-article-id="${firstArticle.id}" data-detail-url="${this.escapeHtml(firstArticle.detailUrl || '')}"
                 style="cursor: pointer;">
                <div class="article-content">
                    <h5 class="mb-3 lh-base" style="color: var(--text-dark); font-weight: 700; font-size: 1.15rem;">
                        <span class="title-link">${this.escapeHtml(title)}</span>
                    </h5>
                    <div class="d-flex align-items-center flex-wrap">
                        <div class="d-flex align-items-center me-4">
                            <a href="/corporations/${corpId}" class="d-flex align-items-center text-decoration-none company-link" onclick="event.stopPropagation()">
                                ${logoHTML}
                                <span class="fw-bold me-2" style="color: var(--primary-green); font-size: 0.9rem;">${this.escapeHtml(corpName)}</span>
                            </a>
                        </div>
                        <div class="d-flex align-items-center gap-2">
                            <small class="text-muted relative-time" data-date="${firstArticle.publishedAt || ''}" title="${firstArticle.publishedAt || ''}" style="white-space: nowrap;">${firstArticle.publishedAt || ''}</small>
                            <button class="like-button" data-article-id="${firstArticle.id}" data-liked="${isLiked}">
                                <i class="fas fa-heart like-icon"></i>
                                <span class="like-count">${firstArticle.likeCount || 0}</span>
                            </button>
                        </div>
                    </div>
                </div>
                <div class="article-thumbnail-container">${thumbnailHTML}</div>
                ${childHTML}
                ${adminScoreHTML}
            </div>
        </div>`;
    }

    updateEmptyState(data) {
        // Remove any existing empty state
        const existingEmptyState = document.querySelector('.hero-section');
        if (existingEmptyState && existingEmptyState.closest('#article-list-container, #article-grouped-container')) {
            existingEmptyState.closest('.text-center').remove();
        }

        if (data.content.length === 0) {
            const isSearch = this.currentKeyword && this.currentKeyword.trim() !== '';
            const emptyHTML = `<div class="text-center py-5">
                <div class="hero-section">
                    <i class="fas fa-${isSearch ? 'search' : 'seedling'} fa-4x mb-4" style="color: var(--primary-green); opacity: 0.5;"></i>
                    <h4 class="mb-3" style="color: var(--text-dark); font-weight: 700;">
                        ${isSearch ? "'" + this.escapeHtml(this.currentKeyword) + "' 검색 결과가 없어요" : '아직 등록된 글이 없어요'}
                    </h4>
                    <p class="text-muted mb-4">
                        ${isSearch ? '다른 키워드로 검색하거나 <a href="/articles" class="text-decoration-none">전체 글 목록</a>을 확인해보세요.' : '새로운 기술 블로그 글이 곧 업데이트될 예정입니다.'}
                    </p>
                </div>
            </div>`;

            // Insert empty state in the appropriate container
            const activeContainer = this.currentView === 'grouped'
                ? document.querySelector('#article-grouped-container')
                : document.querySelector('#article-list-container');
            if (activeContainer) {
                activeContainer.innerHTML = emptyHTML;
            }
        }
    }

    escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.appendChild(document.createTextNode(text));
        return div.innerHTML;
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
        const defaultSort = this.currentKeyword ? 'relevance' : 'latest';
        if (this.currentSort !== defaultSort) params.set('sort', this.currentSort);
        if (this.currentKeyword) params.set('keyword', this.currentKeyword);
        this.currentRegions.forEach(region => params.append('regions', region));
        this.currentContentTypes.forEach(contentType => params.append('contentTypes', contentType));

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
        const listContainer = document.querySelector('#article-list-container');
        const groupedContainer = document.querySelector('#article-grouped-container');
        
        if (loadingState) {
            loadingState.classList.remove('d-none');
        }
        if (listContainer) {
            listContainer.style.opacity = '0.5';
        }
        if (groupedContainer) {
            groupedContainer.style.opacity = '0.5';
        }
    }

    hideLoadingState() {
        const loadingState = document.getElementById('loadingState');
        const listContainer = document.querySelector('#article-list-container');
        const groupedContainer = document.querySelector('#article-grouped-container');
        
        if (loadingState) {
            loadingState.classList.add('d-none');
        }
        if (listContainer) {
            listContainer.style.opacity = '1';
        }
        if (groupedContainer) {
            groupedContainer.style.opacity = '1';
        }
    }

    showErrorState() {
        const activeContainer = this.currentView === 'grouped'
            ? document.querySelector('#article-grouped-container')
            : document.querySelector('#article-list-container');
        if (activeContainer) {
            activeContainer.innerHTML = `
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
        const activeContainer = this.currentView === 'grouped'
            ? document.querySelector('#article-grouped-container')
            : document.querySelector('#article-list-container');
        if (activeContainer) {
            const isSearch = this.currentKeyword && this.currentKeyword.trim() !== '';
            
            activeContainer.innerHTML = `
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
                        const detailUrl = parent.getAttribute('data-detail-url');
                        const articleId = parent.getAttribute('data-article-id');
                        if (detailUrl) {
                            window.open(detailUrl, '_blank');
                        }
                        if (articleId) {
                            fetch(`/api/articles/${articleId}/view`, {
                                method: 'POST',
                                headers: {
                                    'Content-Type': 'application/json',
                                },
                                credentials: 'same-origin',
                                redirect: 'manual'
                            });
                        }
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

    loadLikeStatuses() {
        const likeButtons = document.querySelectorAll('.like-button');

        likeButtons.forEach(btn => {
            const isLiked = btn.getAttribute('data-liked') === 'true';

            if (isLiked) {
                btn.classList.add('liked');
            }
        });
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