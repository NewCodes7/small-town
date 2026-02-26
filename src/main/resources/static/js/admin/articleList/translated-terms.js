/**
 * DeepL 번역 Term 모니터링 관리 모듈
 */

let allTranslatedTerms = [];

function loadTranslatedTerms() {
    const tbody = document.getElementById('translatedTermsTableBody');
    if (!tbody) return;

    tbody.innerHTML = '<tr><td colspan="7" class="text-center text-muted"><i class="fas fa-spinner fa-spin me-2"></i>로딩 중...</td></tr>';

    fetch('/admin/translated-terms')
        .then(res => res.json())
        .then(data => {
            if (!data.success) {
                tbody.innerHTML = `<tr><td colspan="7" class="text-center text-danger">${data.message || '조회 실패'}</td></tr>`;
                return;
            }

            allTranslatedTerms = data.items || [];
            updateTranslatedTermsStats(allTranslatedTerms);
            renderTranslatedTermsTable(allTranslatedTerms);
        })
        .catch(err => {
            console.error('번역 항목 조회 오류:', err);
            tbody.innerHTML = '<tr><td colspan="7" class="text-center text-danger">조회 중 오류가 발생했습니다.</td></tr>';
        });
}

function updateTranslatedTermsStats(items) {
    const total = items.length;
    const withSynonym = items.filter(i => i.hasSynonym).length;
    const withoutSynonym = total - withSynonym;
    const hasTerm = items.filter(i => i.hasTermEntity).length;

    const el = (id) => document.getElementById(id);
    if (el('statTotalCount')) el('statTotalCount').textContent = total;
    if (el('statSynonymCount')) el('statSynonymCount').textContent = withSynonym;
    if (el('statNoSynonymCount')) el('statNoSynonymCount').textContent = withoutSynonym;
    if (el('statHasTermCount')) el('statHasTermCount').textContent = hasTerm;
}

function renderTranslatedTermsTable(items) {
    const tbody = document.getElementById('translatedTermsTableBody');
    if (!tbody) return;

    if (items.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" class="text-center text-muted">번역 항목이 없습니다.</td></tr>';
        return;
    }

    tbody.innerHTML = items.map(item => {
        const createdAt = item.createdAt ? item.createdAt.substring(0, 10) : '-';
        const termBadge = item.hasTermEntity
            ? '<span class="badge bg-success">✓</span>'
            : '<span class="badge bg-secondary">✗</span>';
        const synonymBadge = item.hasSynonym
            ? '<span class="badge bg-success">✓</span>'
            : '<span class="badge bg-secondary">✗</span>';

        return `<tr data-id="${item.id}">
            <td>${item.id}</td>
            <td>${escapeHtml(item.koreanTerm)}</td>
            <td>${escapeHtml(item.englishTerm)}</td>
            <td>${termBadge}</td>
            <td>${synonymBadge}</td>
            <td><small class="text-muted">${createdAt}</small></td>
            <td>
                <button class="btn btn-sm btn-outline-primary me-1"
                        onclick="openEditTranslatedTermModal(${item.id}, '${escapeJs(item.koreanTerm)}', '${escapeJs(item.englishTerm)}')">
                    <i class="fas fa-edit"></i>
                </button>
                <button class="btn btn-sm btn-outline-danger"
                        onclick="deleteTranslatedTerm(${item.id}, '${escapeJs(item.koreanTerm)}', '${escapeJs(item.englishTerm)}')">
                    <i class="fas fa-trash"></i>
                </button>
            </td>
        </tr>`;
    }).join('');
}

function filterTranslatedTerms() {
    const q = (document.getElementById('translatedTermsSearch')?.value || '').toLowerCase();
    const filtered = q
        ? allTranslatedTerms.filter(i =>
            i.koreanTerm.toLowerCase().includes(q) ||
            i.englishTerm.toLowerCase().includes(q))
        : allTranslatedTerms;
    renderTranslatedTermsTable(filtered);
}

function openEditTranslatedTermModal(id, koreanTerm, englishTerm) {
    document.getElementById('editTranslatedTermId').value = id;
    document.getElementById('editTranslatedTermEnglish').value = englishTerm;
    document.getElementById('editTranslatedTermKorean').value = koreanTerm;

    const modal = new bootstrap.Modal(document.getElementById('editTranslatedTermModal'));
    modal.show();
}

function saveTranslatedTerm() {
    const id = parseInt(document.getElementById('editTranslatedTermId').value);
    const newKoreanTerm = document.getElementById('editTranslatedTermKorean').value.trim();

    if (!newKoreanTerm) {
        alert('한국어 번역을 입력해주세요.');
        return;
    }

    fetch(`/admin/translated-terms/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ newKoreanTerm })
    })
        .then(res => res.json())
        .then(data => {
            if (data.success) {
                bootstrap.Modal.getInstance(document.getElementById('editTranslatedTermModal')).hide();

                const row = document.querySelector(`#translatedTermsTableBody tr[data-id="${id}"]`);
                if (row) {
                    row.cells[1].textContent = newKoreanTerm;
                    // 수정 후 term auto-create + synonym 생성되므로 항상 ✓
                    row.cells[3].innerHTML = '<span class="badge bg-success">✓</span>';
                    row.cells[4].innerHTML = '<span class="badge bg-success">✓</span>';

                    const englishTerm = allTranslatedTerms.find(i => i.id === id)?.englishTerm || '';
                    const editBtn = row.querySelector('.btn-outline-primary');
                    if (editBtn) editBtn.setAttribute('onclick', `openEditTranslatedTermModal(${id}, '${escapeJs(newKoreanTerm)}', '${escapeJs(englishTerm)}')`);
                    const deleteBtn = row.querySelector('.btn-outline-danger');
                    if (deleteBtn) deleteBtn.setAttribute('onclick', `deleteTranslatedTerm(${id}, '${escapeJs(newKoreanTerm)}', '${escapeJs(englishTerm)}')`);
                }

                const item = allTranslatedTerms.find(i => i.id === id);
                if (item) {
                    item.koreanTerm = newKoreanTerm;
                    item.hasTermEntity = true;
                    item.hasSynonym = true;
                }
                updateTranslatedTermsStats(allTranslatedTerms);
            } else {
                alert('수정 실패: ' + (data.message || '오류'));
            }
        })
        .catch(err => {
            console.error('번역 수정 오류:', err);
            alert('수정 중 오류가 발생했습니다.');
        });
}

function deleteTranslatedTerm(id, koreanTerm, englishTerm) {
    if (!confirm(`"${koreanTerm}" (${englishTerm}) 항목을 삭제하시겠습니까?\n\nUserDictionary에서 제거되고, 연결된 TermSynonym도 삭제됩니다.\nTerm 엔티티는 유지됩니다.`)) {
        return;
    }

    fetch(`/admin/translated-terms/${id}`, { method: 'DELETE' })
        .then(res => res.json())
        .then(data => {
            if (data.success) {
                const row = document.querySelector(`#translatedTermsTableBody tr[data-id="${id}"]`);
                if (row) row.remove();

                allTranslatedTerms = allTranslatedTerms.filter(i => i.id !== id);
                updateTranslatedTermsStats(allTranslatedTerms);

                const tbody = document.getElementById('translatedTermsTableBody');
                if (tbody && tbody.children.length === 0) {
                    tbody.innerHTML = '<tr><td colspan="7" class="text-center text-muted">번역 항목이 없습니다.</td></tr>';
                }
            } else {
                alert('삭제 실패: ' + (data.message || '오류'));
            }
        })
        .catch(err => {
            console.error('번역 삭제 오류:', err);
            alert('삭제 중 오류가 발생했습니다.');
        });
}

function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function escapeJs(str) {
    if (!str) return '';
    return str.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/"/g, '\\"');
}

// 탭이 translations일 때 자동 로드
document.addEventListener('DOMContentLoaded', function () {
    const url = new URL(window.location.href);
    if (url.searchParams.get('tab') === 'translations') {
        loadTranslatedTerms();
    }

    const saveBtn = document.getElementById('saveTranslatedTermBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', saveTranslatedTerm);
    }
});
