/**
 * SearchAutocomplete - 검색 자동완성 모듈
 *
 * home.html, video.html, new-home.js에서 공통으로 사용하는 검색 자동완성 기능을 모듈화
 *
 * @example
 * // 기본 사용 (아티클 검색)
 * const autocomplete = new SearchAutocomplete({
 *     searchInputId: 'searchInput',
 *     dropdownId: 'autocompleteDropdown',
 *     formId: 'searchForm'
 * });
 * autocomplete.init();
 *
 * @example
 * // 비디오 검색용
 * const autocomplete = new SearchAutocomplete({
 *     searchInputId: 'searchInput',
 *     dropdownId: 'autocompleteDropdown',
 *     formId: 'searchForm',
 *     autocompleteApiUrl: '/video/api/autocomplete',
 *     searchHistoryKey: 'small_town_video_search_history',
 *     supportTheme: false,
 *     corporationUrlPattern: '/corporations/{id}/video'
 * });
 * autocomplete.init();
 */
class SearchAutocomplete {
    constructor(options = {}) {
        // 기본 옵션
        this.options = {
            searchInputId: 'searchInput',
            dropdownId: 'autocompleteDropdown',
            formId: 'searchForm',
            autocompleteApiUrl: '/api/autocomplete',
            searchHistoryKey: 'small_town_search_history',
            searchHistoryApiUrl: '/api/search-history',
            supportTheme: true,
            supportCorporation: true,
            corporationUrlPattern: '/corporations/{id}',
            debounceDelay: 100,
            maxSuggestions: 6,
            maxHistoryInSuggestions: 2,
            useAuthenticatedHistory: true,
            onItemSelect: null, // 커스텀 선택 핸들러
            ...options
        };

        // DOM 요소
        this.searchInput = null;
        this.autocompleteDropdown = null;
        this.searchForm = null;

        // 상태
        this.debounceTimer = null;
        this.abortController = null;
        this.selectedIndex = -1;
        this.suggestions = [];
        this.lastInputValue = '';
        this.programmaticChange = false;
        this.isAuthenticated = false;

        // SearchHistory 인스턴스
        this.searchHistory = null;
    }

    /**
     * 초기화
     */
    init() {
        this.searchInput = document.getElementById(this.options.searchInputId);
        this.autocompleteDropdown = document.getElementById(this.options.dropdownId);
        this.searchForm = document.getElementById(this.options.formId);

        if (!this.searchInput || !this.autocompleteDropdown) {
            console.warn('SearchAutocomplete: Required elements not found');
            return;
        }

        // SearchHistory 인스턴스 생성
        if (typeof SearchHistory !== 'undefined') {
            this.searchHistory = new SearchHistory(this.options.searchHistoryKey);
        }

        // 로그인 상태 확인
        if (this.options.useAuthenticatedHistory) {
            this._checkAuthStatus();
        }

        // 이벤트 리스너 등록
        this._bindEvents();
    }

    /**
     * 인증 상태 확인
     */
    _checkAuthStatus() {
        fetch('/api/user-info', { credentials: 'include' })
            .then(response => response.json())
            .then(data => {
                this.isAuthenticated = data.authenticated || false;
            })
            .catch(() => {
                this.isAuthenticated = false;
            });
    }

    /**
     * 이벤트 바인딩
     */
    _bindEvents() {
        // 입력 이벤트
        this.searchInput.addEventListener('input', this._handleInput.bind(this));

        // 키보드 이벤트
        this.searchInput.addEventListener('keydown', this._handleKeydown.bind(this));

        // 포커스 이벤트
        this.searchInput.addEventListener('blur', this._handleBlur.bind(this));
        this.searchInput.addEventListener('focus', this._handleFocus.bind(this));

        // 폼 제출 이벤트
        if (this.searchForm) {
            this.searchForm.addEventListener('submit', this._handleSubmit.bind(this));
        }
    }

    /**
     * 입력 핸들러
     */
    _handleInput(e) {
        if (this.programmaticChange) {
            this.programmaticChange = false;
            clearTimeout(this.debounceTimer);
            return;
        }

        const query = e.target.value.trim();
        this.lastInputValue = e.target.value;
        clearTimeout(this.debounceTimer);
        this.selectedIndex = -1;

        if (query.length < 1) {
            // 진행 중인 API 요청 취소 (이전 결과가 검색 기록을 덮어쓰는 것 방지)
            if (this.abortController) {
                this.abortController.abort();
                this.abortController = null;
            }
            this.suggestions = [];
            this._displaySearchHistory();
            return;
        }

        this.debounceTimer = setTimeout(() => {
            this._fetchSuggestions(query);
        }, this.options.debounceDelay);
    }

    /**
     * 키보드 핸들러
     */
    _handleKeydown(e) {
        const items = this.autocompleteDropdown.querySelectorAll('.autocomplete-item');

        if (this.autocompleteDropdown.style.display === 'none' || items.length === 0) {
            return;
        }

        switch (e.key) {
            case 'ArrowDown':
                e.preventDefault();
                this.selectedIndex = this.selectedIndex < items.length - 1 ? this.selectedIndex + 1 : 0;
                this._updateSelection(items);
                break;
            case 'ArrowUp':
                e.preventDefault();
                this.selectedIndex = this.selectedIndex > 0 ? this.selectedIndex - 1 : items.length - 1;
                this._updateSelection(items);
                break;
            case 'Enter':
                if (this.selectedIndex >= 0 && this.selectedIndex < items.length) {
                    e.preventDefault();
                    items[this.selectedIndex].click();
                }
                break;
            case 'Escape':
                this.autocompleteDropdown.style.display = 'none';
                this.selectedIndex = -1;
                break;
        }
    }

    /**
     * 블러 핸들러
     */
    _handleBlur() {
        setTimeout(() => {
            this.autocompleteDropdown.style.display = 'none';
            this.selectedIndex = -1;
        }, 200);
    }

    /**
     * 포커스 핸들러
     */
    _handleFocus() {
        const query = this.searchInput.value.trim();
        if (query.length >= 1 && this.suggestions.length > 0) {
            this.autocompleteDropdown.style.display = 'block';
        } else if (query.length === 0) {
            this._displaySearchHistory();
        }
    }

    /**
     * 폼 제출 핸들러
     */
    _handleSubmit(e) {
        const query = this.searchInput.value.trim();
        if (query.length > 0 && this.searchHistory && !this.isAuthenticated) {
            this.searchHistory.saveHistory(query);
        }
    }

    /**
     * 검색 기록 표시
     */
    _displaySearchHistory() {
        if (this.isAuthenticated && this.options.useAuthenticatedHistory) {
            this._fetchAuthenticatedHistory();
        } else {
            this._displayLocalHistory();
        }
    }

    /**
     * 로그인 사용자 검색 기록 API 호출
     */
    _fetchAuthenticatedHistory() {
        fetch(this.options.searchHistoryApiUrl, { credentials: 'include' })
            .then(response => response.json())
            .then(data => {
                if (data.length === 0) {
                    this.autocompleteDropdown.style.display = 'none';
                    return;
                }

                let html = '';
                data.slice(0, this.options.maxSuggestions).forEach((item, index) => {
                    const icon = this._getHistoryIcon(item);
                    html += `
                        <div class="autocomplete-item history-item"
                             data-type="${item.type || 'article'}"
                             data-term="${item.keyword}"
                             ${item.id ? `data-id="${item.id}"` : ''}
                             data-index="${index}">
                            <i class="fas ${icon}" style="color: #9ca3af; margin-right: 12px;"></i>
                            <span class="autocomplete-term">${item.keyword}</span>
                        </div>
                    `;
                });

                this.autocompleteDropdown.innerHTML = html;
                this.autocompleteDropdown.style.display = 'block';
                this._attachHistoryClickHandlers();
            })
            .catch(() => {
                this.autocompleteDropdown.style.display = 'none';
            });
    }

    /**
     * 검색 기록 아이콘 결정
     */
    _getHistoryIcon(item) {
        if (item.type === 'article' && !item.id) return 'fa-clock-rotate-left';
        if (item.type === 'video') return 'fa-video';
        if (item.type === 'corporation') return 'fa-building';
        if (item.type === 'theme') return 'fa-layer-group';
        return 'fa-clock-rotate-left';
    }

    /**
     * 로컬 검색 기록 표시
     */
    _displayLocalHistory() {
        if (!this.searchHistory) {
            this.autocompleteDropdown.style.display = 'none';
            return;
        }

        const history = this.searchHistory.getHistory().slice(0, this.options.maxSuggestions);
        if (history.length === 0) {
            this.autocompleteDropdown.style.display = 'none';
            return;
        }

        let html = '';
        history.forEach((item, index) => {
            html += `
                <div class="autocomplete-item history-item" data-type="history" data-term="${item}" data-index="${index}">
                    <i class="fas fa-clock-rotate-left" style="color: #9ca3af; margin-right: 12px;"></i>
                    <span class="autocomplete-term">${item}</span>
                </div>
            `;
        });

        this.autocompleteDropdown.innerHTML = html;
        this.autocompleteDropdown.style.display = 'block';
        this._attachHistoryClickHandlers();
    }

    /**
     * 검색 기록 클릭 핸들러 등록
     */
    _attachHistoryClickHandlers() {
        const items = this.autocompleteDropdown.querySelectorAll('.autocomplete-item');
        items.forEach(item => {
            item.addEventListener('click', () => {
                const type = item.dataset.type;
                const term = item.dataset.term;
                const id = item.dataset.id;

                if (this.options.onItemSelect) {
                    this.options.onItemSelect({ type, term, id });
                    return;
                }

                this._handleItemClick(type, term, id);
            });
        });

        if (this.selectedIndex >= 0 && this.selectedIndex < items.length) {
            this._updateSelection(items);
        }
    }

    /**
     * 자동완성 API 호출
     */
    _fetchSuggestions(query) {
        // 이전 요청 취소
        if (this.abortController) {
            this.abortController.abort();
        }
        this.abortController = new AbortController();

        fetch(`${this.options.autocompleteApiUrl}?q=${encodeURIComponent(query)}`, {
            signal: this.abortController.signal
        })
            .then(response => response.json())
            .then(data => {
                this.suggestions = data.slice(0, this.options.maxSuggestions);
                this._displaySuggestions(query, this.suggestions);
            })
            .catch(error => {
                if (error.name === 'AbortError') return;
                console.error('자동완성 조회 오류:', error);
            });
    }

    /**
     * 자동완성 결과 표시
     */
    _displaySuggestions(query, data) {
        let html = '';
        let currentIndex = 0;

        // 검색 기록 필터링 (로컬 기록만)
        if (this.searchHistory && !this.isAuthenticated) {
            const matchedHistory = this.searchHistory.filterHistory(query, this.options.maxHistoryInSuggestions);
            matchedHistory.forEach((item) => {
                html += `
                    <div class="autocomplete-item history-item" data-type="history" data-term="${item}" data-index="${currentIndex}">
                        <i class="fas fa-clock-rotate-left" style="color: #9ca3af; margin-right: 12px;"></i>
                        <span class="autocomplete-term">${item}</span>
                    </div>
                `;
                currentIndex++;
            });
        }

        // 자동완성 결과 표시
        if (data && data.length > 0) {
            data.forEach((item) => {
                html += this._renderSuggestionItem(item, currentIndex);
                currentIndex++;
            });
        }

        if (html === '') {
            this.autocompleteDropdown.style.display = 'none';
            return;
        }

        this.autocompleteDropdown.innerHTML = html;
        this.autocompleteDropdown.style.display = 'block';
        this._attachSuggestionClickHandlers();
    }

    /**
     * 자동완성 아이템 렌더링
     */
    _renderSuggestionItem(item, index) {
        // Corporation: [0, name, id, logoUrl]
        if (Array.isArray(item) && item[0] === 0 && this.options.supportCorporation) {
            const [, name, id, logoUrl] = item;
            return `
                <div class="autocomplete-item corporation-item" data-type="corporation" data-id="${id}" data-name="${name}" data-index="${index}">
                    <div style="display: flex; align-items: center; gap: 12px;">
                        ${logoUrl
                            ? `<img src="${logoUrl}" alt="${name}" style="width: 24px; height: 24px; border-radius: 4px; object-fit: contain;">`
                            : `<i class="fas fa-building" style="width: 24px; height: 24px; display: flex; align-items: center; justify-content: center; color: var(--primary-green); font-size: 18px;"></i>`
                        }
                        <span class="autocomplete-term">${name}</span>
                    </div>
                </div>
            `;
        }

        // Theme: [1, id, name]
        if (Array.isArray(item) && item[0] === 1 && this.options.supportTheme) {
            const [, id, name] = item;
            return `
                <div class="autocomplete-item theme-item" data-type="theme" data-id="${id}" data-name="${name}" data-index="${index}">
                    <div style="display: flex; align-items: center; gap: 12px;">
                        <i class="fas fa-layer-group" style="width: 24px; height: 24px; display: flex; align-items: center; justify-content: center; color: var(--primary-green); font-size: 16px;"></i>
                        <span class="autocomplete-term" style="font-weight: 600;">${name}</span>
                    </div>
                </div>
            `;
        }

        // Term: string
        if (typeof item === 'string') {
            return `
                <div class="autocomplete-item term-item" data-type="term" data-term="${item}" data-index="${index}">
                    <span class="autocomplete-term">${item}</span>
                </div>
            `;
        }

        return '';
    }

    /**
     * 자동완성 클릭 핸들러 등록
     */
    _attachSuggestionClickHandlers() {
        const items = this.autocompleteDropdown.querySelectorAll('.autocomplete-item');
        items.forEach(item => {
            item.addEventListener('click', () => {
                const type = item.dataset.type;
                const term = item.dataset.term || item.dataset.name;
                const id = item.dataset.id;

                if (this.options.onItemSelect) {
                    this.options.onItemSelect({ type, term, id });
                    return;
                }

                this._handleItemClick(type, term, id);
            });
        });

        if (this.selectedIndex >= 0 && this.selectedIndex < items.length) {
            this._updateSelection(items);
        }
    }

    /**
     * 아이템 클릭 처리
     */
    _handleItemClick(type, term, id) {
        if (type === 'corporation' && id) {
            const url = this.options.corporationUrlPattern.replace('{id}', id);
            window.location.href = `${url}?keyword=${encodeURIComponent(term)}`;
        } else if (type === 'theme' && id) {
            window.location.href = `/themes/${id}?keyword=${encodeURIComponent(term)}`;
        } else if (type === 'video') {
            this.searchInput.value = term;
            if (this.searchHistory && !this.isAuthenticated) {
                this.searchHistory.saveHistory(term);
            }
            this.autocompleteDropdown.style.display = 'none';
            window.location.href = `/videos?keyword=${encodeURIComponent(term)}`;
        } else {
            // term, history, article
            this.searchInput.value = term;
            if (this.searchHistory && !this.isAuthenticated) {
                this.searchHistory.saveHistory(term);
            }
            this.autocompleteDropdown.style.display = 'none';
            if (this.searchForm) {
                this.searchForm.submit();
            }
        }
    }

    /**
     * 선택 상태 업데이트
     */
    _updateSelection(items) {
        items.forEach((item, index) => {
            if (index === this.selectedIndex) {
                item.classList.add('selected');
                item.scrollIntoView({ block: 'nearest' });
            } else {
                item.classList.remove('selected');
            }
        });

        if (this.selectedIndex >= 0 && this.selectedIndex < items.length) {
            const selectedItem = items[this.selectedIndex];
            const type = selectedItem.dataset.type;

            this.programmaticChange = true;
            if (type === 'corporation' || type === 'theme') {
                this.searchInput.value = selectedItem.dataset.name;
            } else {
                this.searchInput.value = selectedItem.dataset.term;
            }
        } else {
            this.programmaticChange = true;
            this.searchInput.value = this.lastInputValue;
        }
    }

    /**
     * 외부에서 검색 기록 저장
     */
    saveHistory(term) {
        if (this.searchHistory && !this.isAuthenticated) {
            this.searchHistory.saveHistory(term);
        }
    }

    /**
     * 정리
     */
    destroy() {
        if (this.abortController) {
            this.abortController.abort();
        }
        clearTimeout(this.debounceTimer);
    }
}

// 글로벌 노출 (기존 코드 호환성)
window.SearchAutocomplete = SearchAutocomplete;
