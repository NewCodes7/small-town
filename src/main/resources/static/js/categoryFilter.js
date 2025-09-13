// 카테고리 필터링 관리 클래스
class CategoryFilterManager {
    constructor() {
        this.selectedCategories = new Set();
        this.allCategories = [];
        this.init();
    }

    async init() {
        await this.loadCategories();
        this.bindEvents();
        this.loadFromUrlParams();
    }

    // 카테고리 목록 로드
    async loadCategories() {
        try {
            const response = await fetch('/api/categories');
            if (response.ok) {
                this.allCategories = await response.json();
                this.renderCategoryDropdown();
            } else {
                console.error('카테고리 로드 실패:', response.status);
            }
        } catch (error) {
            console.error('카테고리 로드 중 오류:', error);
        }
    }

    // 카테고리 드롭다운 렌더링
    renderCategoryDropdown() {
        const dropdownMenu = document.getElementById('categoryDropdownMenu');
        if (!dropdownMenu) return;

        dropdownMenu.innerHTML = '';

        this.allCategories.forEach(category => {
            const li = document.createElement('li');
            li.innerHTML = `
                <a class="dropdown-item category-option" href="#" data-category-id="${category.id}" data-category-name="${category.name}">
                    <i class="fas fa-tag me-2"></i>
                    ${category.name}
                </a>
            `;
            dropdownMenu.appendChild(li);
        });
    }

    // 이벤트 바인딩
    bindEvents() {
        // 카테고리 선택 이벤트
        document.addEventListener('click', (e) => {
            if (e.target.matches('.category-option') || e.target.closest('.category-option')) {
                e.preventDefault();
                const option = e.target.closest('.category-option');
                const categoryId = option.dataset.categoryId;
                const categoryName = option.dataset.categoryName;
                this.toggleCategory(categoryId, categoryName);
            }
        });

        // 카테고리 블록 삭제 이벤트
        document.addEventListener('click', (e) => {
            if (e.target.matches('.remove-category-btn') || e.target.closest('.remove-category-btn')) {
                e.preventDefault();
                const btn = e.target.closest('.remove-category-btn');
                const categoryId = btn.dataset.categoryId;
                this.removeCategory(categoryId);
            }
        });

    }

    // 카테고리 토글 (선택/해제)
    toggleCategory(categoryId, categoryName) {
        if (this.selectedCategories.has(categoryId)) {
            this.removeCategory(categoryId);
        } else {
            this.addCategory(categoryId, categoryName);
        }
    }

    // 카테고리 추가
    addCategory(categoryId, categoryName) {
        this.selectedCategories.add(categoryId);
        this.renderSelectedCategories();
        this.updateDropdownStates();
        this.applyFilter();
    }

    // 카테고리 제거
    removeCategory(categoryId) {
        this.selectedCategories.delete(categoryId);
        this.renderSelectedCategories();
        this.updateDropdownStates();
        this.applyFilter();
    }


    // 선택된 카테고리 블록 렌더링
    renderSelectedCategories() {
        const group = document.getElementById('selectedCategoriesGroup');
        const list = document.getElementById('selectedCategoriesList');
        
        if (!group || !list) return;

        // 선택된 카테고리가 없으면 그룹 숨김
        if (this.selectedCategories.size === 0) {
            group.style.display = 'none';
            list.innerHTML = '';
            return;
        }

        // 그룹 표시
        group.style.display = 'block';

        // 블록 렌더링
        list.innerHTML = '';
        this.selectedCategories.forEach(categoryId => {
            const category = this.allCategories.find(c => c.id.toString() === categoryId);
            if (category) {
                const block = this.createCategoryBlock(category.id, category.name);
                list.appendChild(block);
            }
        });
    }

    // 카테고리 블록 생성
    createCategoryBlock(categoryId, categoryName) {
        const block = document.createElement('span');
        block.className = 'category-block';
        
        block.innerHTML = `
            <i class="fas fa-tag"></i>
            <span>${categoryName}</span>
            <button type="button" class="btn-close remove-category-btn" 
                    data-category-id="${categoryId}"
                    aria-label="카테고리 제거">
            </button>
        `;
        
        return block;
    }

    // 드롭다운 상태 업데이트 (선택된 항목 표시)
    updateDropdownStates() {
        const options = document.querySelectorAll('.category-option');
        options.forEach(option => {
            const categoryId = option.dataset.categoryId;
            if (this.selectedCategories.has(categoryId)) {
                option.classList.add('active');
                option.style.backgroundColor = 'var(--primary-green)';
                option.style.color = 'white';
            } else {
                option.classList.remove('active');
                option.style.backgroundColor = '';
                option.style.color = '';
            }
        });
    }

    // URL 파라미터에서 카테고리 로드
    loadFromUrlParams() {
        const urlParams = new URLSearchParams(window.location.search);
        const categories = urlParams.getAll('category');
        
        categories.forEach(categoryName => {
            const category = this.allCategories.find(c => c.name === categoryName);
            if (category) {
                this.selectedCategories.add(category.id.toString());
            }
        });
        
        this.renderSelectedCategories();
        this.updateDropdownStates();
    }

    // 필터 적용 (페이지 새로고침 방식)
    applyFilter() {
        // URL 업데이트 후 페이지 새로고침
        this.updateUrlParams();
        window.location.href = this.getCurrentUrl();
    }

    // URL 파라미터 업데이트
    updateUrlParams() {
        const url = new URL(window.location);
        
        // 기존 카테고리 파라미터 제거
        url.searchParams.delete('category');
        
        // 선택된 카테고리 추가
        if (this.selectedCategories.size > 0) {
            this.selectedCategories.forEach(categoryId => {
                const category = this.allCategories.find(c => c.id.toString() === categoryId);
                if (category) {
                    url.searchParams.append('category', category.name);
                }
            });
        }
        
        // 페이지는 0으로 리셋
        url.searchParams.set('page', '0');
        
        // URL 저장 (새로고침을 위해 history에는 저장하지 않음)
        this.pendingUrl = url.toString();
    }

    // 현재 URL 가져오기
    getCurrentUrl() {
        return this.pendingUrl || window.location.href;
    }

    // 현재 선택된 카테고리 이름 배열 반환
    getSelectedCategoryNames() {
        return Array.from(this.selectedCategories).map(categoryId => {
            const category = this.allCategories.find(c => c.id.toString() === categoryId);
            return category ? category.name : null;
        }).filter(name => name !== null);
    }
}

// 전역 변수로 카테고리 필터 매니저 인스턴스 생성
let categoryFilterManager;

// DOM 로드 완료 후 초기화
document.addEventListener('DOMContentLoaded', () => {
    categoryFilterManager = new CategoryFilterManager();
});