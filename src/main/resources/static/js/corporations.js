// Industry 관리 변수
let allIndustries = [];

// 초기화
async function initCorporationsPage() {
    await userAuth.fetchUserInfo();

    if (userAuth.isAdminUser()) {
        await loadIndustries();
        userAuth.showAdminControls('.admin-control-panel');
    }
}

// Industry 목록 가져오기
async function loadIndustries() {
    try {
        const response = await fetch('/admin/industries');
        const data = await response.json();
        if (data.success) {
            allIndustries = data.industries;
        }
    } catch (error) {
        console.error('Industry 목록 조회 실패:', error);
    }
}

// 버튼에서 모달 열기
function openIndustryEditModalFromButton(button) {
    const corporationId = button.dataset.corpId;
    const corporationName = button.dataset.corpName;
    const industriesStr = button.dataset.industries;
    const currentIndustries = industriesStr ? industriesStr.split(',').map(s => s.trim()) : [];

    openIndustryEditModal(corporationId, corporationName, currentIndustries);
}

// Industry 수정 모달 열기
function openIndustryEditModal(corporationId, corporationName, currentIndustries) {
    const modal = document.getElementById('industryEditModal');
    const modalTitle = document.getElementById('modalCorporationName');
    const industryCheckboxes = document.getElementById('industryCheckboxes');

    modalTitle.textContent = corporationName;
    modal.dataset.corporationId = corporationId;

    // 체크박스 생성
    industryCheckboxes.innerHTML = '';
    allIndustries.forEach(industry => {
        const isChecked = currentIndustries.includes(industry.name);
        const div = document.createElement('div');
        div.className = 'form-check mb-2 d-flex align-items-center justify-content-between industry-item';
        div.innerHTML = `
            <div class="d-flex align-items-center">
                <input class="form-check-input" type="checkbox" value="${industry.id}"
                       id="industry-${industry.id}" ${isChecked ? 'checked' : ''}>
                <label class="form-check-label ms-2" for="industry-${industry.id}">
                    ${industry.name}
                </label>
            </div>
            <button class="btn btn-sm btn-outline-danger delete-industry-btn"
                    onclick="deleteIndustryFromModal(${industry.id}, '${industry.name}')"
                    title="Industry 삭제">
                <i class="fas fa-trash-alt"></i>
            </button>
        `;
        industryCheckboxes.appendChild(div);
    });

    // 새 Industry 입력 필드 초기화
    document.getElementById('newIndustryName').value = '';

    const bootstrapModal = new bootstrap.Modal(modal);
    bootstrapModal.show();
}

// 새 Industry 추가
async function addNewIndustry() {
    const newIndustryName = document.getElementById('newIndustryName').value.trim();

    if (!newIndustryName) {
        alert('Industry 이름을 입력해주세요.');
        return;
    }

    // 이미 존재하는지 확인
    const exists = allIndustries.some(ind => ind.name.toLowerCase() === newIndustryName.toLowerCase());
    if (exists) {
        alert('이미 존재하는 Industry입니다.');
        return;
    }

    try {
        const response = await fetch('/admin/industries', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ name: newIndustryName })
        });

        const data = await response.json();

        if (data.success) {
            // 목록 갱신
            await loadIndustries();

            // 모달 닫고 다시 열기
            const modal = document.getElementById('industryEditModal');
            const corporationId = modal.dataset.corporationId;
            const corporationName = document.getElementById('modalCorporationName').textContent;
            const currentChecked = Array.from(document.querySelectorAll('#industryCheckboxes input:checked'))
                .map(cb => {
                    const label = document.querySelector(`label[for="${cb.id}"]`);
                    return label ? label.textContent.trim() : '';
                });

            // 새로 생성된 industry도 체크된 상태로
            currentChecked.push(newIndustryName);

            openIndustryEditModal(corporationId, corporationName, currentChecked);

            alert('새 Industry가 추가되었습니다.');
        } else {
            alert('오류: ' + data.message);
        }
    } catch (error) {
        console.error('Industry 추가 실패:', error);
        alert('Industry 추가 중 오류가 발생했습니다.');
    }
}

// Industry 저장
async function saveIndustries() {
    const modal = document.getElementById('industryEditModal');
    const corporationId = modal.dataset.corporationId;
    const checkedBoxes = document.querySelectorAll('#industryCheckboxes input:checked');
    const industryIds = Array.from(checkedBoxes).map(cb => parseInt(cb.value));

    try {
        const response = await fetch(`/admin/corporations/${corporationId}/industries`, {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ industryIds })
        });

        const data = await response.json();

        if (data.success) {
            alert('Industry가 성공적으로 수정되었습니다.');
            location.reload();
        } else {
            alert('오류: ' + data.message);
        }
    } catch (error) {
        console.error('Industry 수정 실패:', error);
        alert('Industry 수정 중 오류가 발생했습니다.');
    }
}

// 초기화
document.addEventListener('DOMContentLoaded', async function() {
    await initCorporationsPage();
});

// 카드 클릭 시 상세 페이지로 이동
document.querySelectorAll('.corporation-card').forEach(card => {
    card.addEventListener('click', function(e) {
        // 관리자 버튼 클릭 시에는 카드 클릭 이벤트 무시
        if (e.target.closest('.admin-edit-btn')) {
            return;
        }

        const cardLink = this.querySelector('.corp-card-link');
        if (cardLink) {
            window.location.href = cardLink.href;
        }
    });

    // 카드에 커서 스타일 추가
    card.style.cursor = 'pointer';
});

// 검색 폼 개선
const searchKeyword = document.getElementById('searchKeyword');
if (searchKeyword) {
    searchKeyword.addEventListener('keypress', function(e) {
        if (e.key === 'Enter') {
            this.form.submit();
        }
    });
}

// Industry 필터링
let selectedIndustries = new Set();

// 현재 URL에서 선택된 industry 가져오기
function initSelectedIndustries() {
    const urlParams = new URLSearchParams(window.location.search);
    const industries = urlParams.getAll('industries');
    selectedIndustries = new Set(industries.map(id => parseInt(id)));
    updateIndustryCheckboxes();
}

// Checkbox 상태 업데이트
function updateIndustryCheckboxes() {
    document.querySelectorAll('.category-checkbox').forEach(checkbox => {
        const industryId = parseInt(checkbox.value);
        checkbox.checked = selectedIndustries.has(industryId);
    });
}

// Checkbox 클릭 이벤트
document.addEventListener('DOMContentLoaded', function() {
    initSelectedIndustries();

    // Checkbox 이벤트 리스너
    document.querySelectorAll('.category-checkbox').forEach(checkbox => {
        checkbox.addEventListener('change', function() {
            const industryId = parseInt(this.value);
            if (this.checked) {
                selectedIndustries.add(industryId);
            } else {
                selectedIndustries.delete(industryId);
            }
            applyIndustryFilter();
        });
    });

    // Industry 드롭다운 width 동적 조정 (콘텐츠에 맞춰)
    const industryDropdown = document.getElementById('industryDropdown');
    const industryDropdownMenu = document.getElementById('industryDropdownMenu');

    if (industryDropdown && industryDropdownMenu) {
        // 드롭다운이 열릴 때 자동으로 width 조정 (콘텐츠에 맞춤)
        industryDropdown.addEventListener('show.bs.dropdown', function() {
            // 버튼 width를 최소값으로 설정
            const buttonWidth = this.offsetWidth;
            industryDropdownMenu.style.minWidth = buttonWidth + 'px';
            // width는 auto로 설정하여 콘텐츠에 맞춰 확장되도록
            industryDropdownMenu.style.width = 'auto';
        });
    }
});

// Industry 필터 적용
function applyIndustryFilter() {
    const urlParams = new URLSearchParams(window.location.search);

    // 기존 industries 파라미터 제거
    urlParams.delete('industries');

    // 새로운 industries 파라미터 추가
    selectedIndustries.forEach(id => {
        urlParams.append('industries', id);
    });

    // 페이지를 0으로 리셋
    urlParams.set('page', '0');

    // 페이지 리로드
    window.location.href = `${window.location.pathname}?${urlParams.toString()}`;
}

// Industry 제거
function removeIndustry(industryId) {
    selectedIndustries.delete(industryId);
    applyIndustryFilter();
}

// Industry 삭제 (Admin 전용)
async function deleteIndustryFromModal(industryId, industryName) {
    // 확인 대화상자
    const confirmed = confirm(
        `정말로 Industry "${industryName}"을(를) 삭제하시겠습니까?\n\n` +
        `이 Industry를 사용하는 모든 기업에서 자동으로 제거됩니다.\n` +
        `이 작업은 되돌릴 수 없습니다.`
    );

    if (!confirmed) {
        return;
    }

    try {
        const response = await fetch(`/admin/industries/${industryId}`, {
            method: 'DELETE',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        const data = await response.json();

        if (data.success) {
            alert(data.message);

            // Industry 목록 다시 로드
            await loadIndustries();

            // 모달 닫기
            const modal = bootstrap.Modal.getInstance(document.getElementById('industryEditModal'));
            if (modal) {
                modal.hide();
            }

            // 페이지 새로고침
            location.reload();
        } else {
            alert('오류: ' + data.message);
        }
    } catch (error) {
        console.error('Industry 삭제 실패:', error);
        alert('Industry 삭제 중 오류가 발생했습니다.');
    }
}