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
        div.className = 'form-check mb-2';
        div.innerHTML = `
            <input class="form-check-input" type="checkbox" value="${industry.id}"
                   id="industry-${industry.id}" ${isChecked ? 'checked' : ''}>
            <label class="form-check-label" for="industry-${industry.id}">
                ${industry.name}
            </label>
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