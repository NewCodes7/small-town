// 카테고리 필터링 관리 클래스
class CategoryFilterManager {
    constructor() {
        this.selectedCategories = new Set();
        this.allCategories = [];
        this.categoryGroups = this.initCategoryGroups();
        this.init();
    }

    // 카테고리 그룹 정의
    initCategoryGroups() {
        return {
            frontend: {
                name: 'Frontend',
                color: '#3B82F6', // 파란색
                icon: 'fas fa-laptop-code',
                categories: ['frontend', 'mobile', 'ui/ux']
            },
            backend: {
                name: 'Backend & Infra',
                color: '#10B981', // 초록색
                icon: 'fas fa-server',
                categories: ['backend', 'database', 'infra', 'devops', 'testing']
            },
            data: {
                name: 'Data & AI',
                color: '#8B5CF6', // 보라색
                icon: 'fas fa-brain',
                categories: ['ai', 'data', 'security']
            },
            culture: {
                name: 'Culture & Tools',
                color: '#F59E0B', // 주황색
                icon: 'fas fa-users',
                categories: ['culture', 'career', 'tools']
            }
        };
    }

    // 카테고리가 속한 그룹 찾기
    getCategoryGroup(categoryName) {
        for (const [groupKey, group] of Object.entries(this.categoryGroups)) {
            if (group.categories.some(cat => categoryName.toLowerCase().includes(cat.toLowerCase()))) {
                return { key: groupKey, ...group };
            }
        }
        // 기본 그룹
        return {
            key: 'default',
            name: 'Other',
            color: '#6B7280',
            icon: 'fas fa-tag',
            categories: []
        };
    }

    init() {
        this.loadCategoriesFromDOM();
        this.bindEvents();
        this.loadFromUrlParams();
    }

    // DOM에서 카테고리 목록 로드 (Thymeleaf로 렌더링된 카테고리 사용)
    loadCategoriesFromDOM() {
        const dropdownMenu = document.getElementById('categoryDropdownMenu');
        if (!dropdownMenu) return;

        // DOM에서 카테고리 정보 추출
        const categoryOptions = dropdownMenu.querySelectorAll('.category-option');
        this.allCategories = Array.from(categoryOptions).map(option => ({
            id: option.dataset.categoryId,
            name: option.dataset.categoryName
        }));

        // 카테고리를 그룹별로 분류하고 정렬
        const groupedItems = this.groupAndSortCategories(categoryOptions);

        // 드롭다운 메뉴 다시 렌더링
        this.renderSortedCategories(dropdownMenu, groupedItems);
    }

    // 카테고리를 그룹별로 분류하고 정렬
    groupAndSortCategories(categoryOptions) {
        const grouped = {
            frontend: [],
            backend: [],
            data: [],
            culture: [],
            default: []
        };

        // 카테고리를 그룹별로 분류
        Array.from(categoryOptions).forEach(option => {
            const li = option.closest('li');
            const categoryName = option.dataset.categoryName;
            const group = this.getCategoryGroup(categoryName);

            grouped[group.key].push(li);
        });

        // 각 그룹 내에서 정의된 순서대로 정렬
        Object.keys(grouped).forEach(groupKey => {
            const group = this.categoryGroups[groupKey] || { categories: [] };
            const definedOrder = group.categories;

            grouped[groupKey].sort((a, b) => {
                const aName = a.querySelector('.category-option').dataset.categoryName;
                const bName = b.querySelector('.category-option').dataset.categoryName;

                const aIndex = definedOrder.findIndex(cat =>
                    aName.toLowerCase().includes(cat.toLowerCase()));
                const bIndex = definedOrder.findIndex(cat =>
                    bName.toLowerCase().includes(cat.toLowerCase()));

                // 정의된 순서가 있으면 그 순서를 따르고, 없으면 알파벳 순
                if (aIndex !== -1 && bIndex !== -1) {
                    return aIndex - bIndex;
                } else if (aIndex !== -1) {
                    return -1;
                } else if (bIndex !== -1) {
                    return 1;
                } else {
                    return aName.localeCompare(bName);
                }
            });
        });

        return grouped;
    }

    // 정렬된 카테고리로 드롭다운 다시 렌더링
    renderSortedCategories(dropdownMenu, groupedItems) {
        // 기존 내용 제거
        dropdownMenu.innerHTML = '';

        // 그룹 순서 정의
        const groupOrder = ['frontend', 'backend', 'data', 'culture', 'default'];

        groupOrder.forEach((groupKey, groupIndex) => {
            const items = groupedItems[groupKey];
            if (!items || items.length === 0) return;

            // 그룹 구분선 (첫 번째 그룹이 아닌 경우)
            if (groupIndex > 0) {
                const divider = document.createElement('li');
                divider.innerHTML = '<hr class="dropdown-divider">';
                dropdownMenu.appendChild(divider);
            }

            // 그룹 내 카테고리들 추가
            items.forEach(li => {
                const option = li.querySelector('.category-option');
                const categoryName = option.dataset.categoryName;
                const group = this.getCategoryGroup(categoryName);
                const icon = option.querySelector('i');

                // data-group-color 속성 설정 (기존 코드와 호환성 유지)
                option.dataset.groupColor = group.color;

                // 아이콘 클래스 설정
                if (icon) {
                    icon.className = `${group.icon} me-2`;
                    icon.style.color = group.color;
                }

                dropdownMenu.appendChild(li);
            });
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

    // 카테고리 블록 생성 (그룹 색상 적용)
    createCategoryBlock(categoryId, categoryName) {
        const group = this.getCategoryGroup(categoryName);
        const block = document.createElement('span');
        block.className = 'category-block';
        block.style.backgroundColor = group.color;
        block.style.color = 'white';

        block.innerHTML = `
            <i class="${group.icon}"></i>
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
            const groupColor = option.dataset.groupColor;

            if (this.selectedCategories.has(categoryId)) {
                option.classList.add('active');
                option.style.backgroundColor = groupColor;
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

            // 카테고리가 선택되면 리스트 뷰로 강제 전환
            url.searchParams.set('view', 'list');
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