// ============================================

let currentKeywordId = null;

// 키워드 탭이 활성화되어 있으면 키워드 목록 로드
if (window.location.search.includes('tab=keywords')) {
    loadKeywords();
}

// 키워드 목록 로드
function loadKeywords() {
    fetch('/admin/related-content-keywords')
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                displayKeywords(data.keywords);
            } else {
                document.getElementById('keywordTableBody').innerHTML = `
                    <tr>
                        <td colspan="4" class="text-center text-danger">
                            <i class="fas fa-exclamation-circle"></i> ${data.message}
                        </td>
                    </tr>`;
            }
        })
        .catch(error => {
            console.error('키워드 로드 오류:', error);
            document.getElementById('keywordTableBody').innerHTML = `
                <tr>
                    <td colspan="4" class="text-center text-danger">
                        <i class="fas fa-exclamation-circle"></i> 키워드를 불러오는 중 오류가 발생했습니다.
                    </td>
                </tr>`;
        });
}

// 키워드 목록 표시
function displayKeywords(keywords) {
    const tbody = document.getElementById('keywordTableBody');

    if (keywords.length === 0) {
        tbody.innerHTML = `
            <tr>
                <td colspan="4" class="text-center text-muted">
                    등록된 키워드가 없습니다. 키워드를 추가해주세요.
                </td>
            </tr>`;
        return;
    }

    tbody.innerHTML = keywords.map(keyword => `
        <tr>
            <td>${keyword.id}</td>
            <td><strong>${escapeHtml(keyword.keyword)}</strong></td>
            <td>${formatDateTime(keyword.createdAt)}</td>
            <td>
                <button class="btn btn-sm btn-primary edit-keyword-btn" data-keyword-id="${keyword.id}" data-keyword="${escapeHtml(keyword.keyword)}">
                    <i class="fas fa-edit"></i>
                </button>
                <button class="btn btn-sm btn-danger delete-keyword-btn" data-keyword-id="${keyword.id}" data-keyword="${escapeHtml(keyword.keyword)}">
                    <i class="fas fa-trash"></i>
                </button>
            </td>
        </tr>
    `).join('');
}

// 키워드 추가 버튼 클릭
const addKeywordBtn = document.getElementById('addKeywordBtn');
if (addKeywordBtn) {
    addKeywordBtn.addEventListener('click', function() {
        currentKeywordId = null;
        document.getElementById('keywordModalTitle').textContent = '키워드 추가';
        document.getElementById('keywordInput').value = '';
        const modal = new bootstrap.Modal(document.getElementById('keywordModal'));
        modal.show();
    });
}

// 키워드 편집 버튼 클릭
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('edit-keyword-btn') || e.target.closest('.edit-keyword-btn')) {
        const btn = e.target.classList.contains('edit-keyword-btn') ? e.target : e.target.closest('.edit-keyword-btn');
        currentKeywordId = btn.dataset.keywordId;
        const keyword = btn.dataset.keyword;

        document.getElementById('keywordModalTitle').textContent = '키워드 수정';
        document.getElementById('keywordInput').value = keyword;

        const modal = new bootstrap.Modal(document.getElementById('keywordModal'));
        modal.show();
    }

    // 키워드 삭제 버튼 클릭
    if (e.target.classList.contains('delete-keyword-btn') || e.target.closest('.delete-keyword-btn')) {
        const btn = e.target.classList.contains('delete-keyword-btn') ? e.target : e.target.closest('.delete-keyword-btn');
        const keywordId = btn.dataset.keywordId;
        const keyword = btn.dataset.keyword;

        if (confirm(`"${keyword}" 키워드를 삭제하시겠습니까?`)) {
            deleteKeyword(keywordId);
        }
    }
});

// 키워드 저장 버튼 클릭
const saveKeywordBtn = document.getElementById('saveKeywordBtn');
if (saveKeywordBtn) {
    saveKeywordBtn.addEventListener('click', function() {
        const keyword = document.getElementById('keywordInput').value.trim();

        if (!keyword) {
            alert('키워드를 입력해주세요.');
            return;
        }

        if (currentKeywordId) {
            // 수정
            updateKeyword(currentKeywordId, keyword);
        } else {
            // 추가
            addKeyword(keyword);
        }
    });
}

// 키워드 추가
function addKeyword(keyword) {
    fetch('/admin/related-content-keywords', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({ keyword: keyword })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert('키워드가 추가되었습니다.');
            bootstrap.Modal.getInstance(document.getElementById('keywordModal')).hide();
            loadKeywords();
        } else {
            alert('키워드 추가 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('키워드 추가 오류:', error);
        alert('키워드 추가 중 오류가 발생했습니다.');
    });
}

// 키워드 수정
function updateKeyword(id, keyword) {
    fetch(`/admin/related-content-keywords/${id}`, {
        method: 'PUT',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({ keyword: keyword })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert('키워드가 수정되었습니다.');
            bootstrap.Modal.getInstance(document.getElementById('keywordModal')).hide();
            loadKeywords();
        } else {
            alert('키워드 수정 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('키워드 수정 오류:', error);
        alert('키워드 수정 중 오류가 발생했습니다.');
    });
}

// 키워드 삭제
function deleteKeyword(id) {
    fetch(`/admin/related-content-keywords/${id}`, {
        method: 'DELETE'
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert('키워드가 삭제되었습니다.');
            loadKeywords();
        } else {
            alert('키워드 삭제 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('키워드 삭제 오류:', error);
        alert('키워드 삭제 중 오류가 발생했습니다.');
    });
}
