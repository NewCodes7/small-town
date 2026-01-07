// ========== 유의어 관리 ==========

// 유의어 목록 로드
function loadSynonyms() {
    fetch('/admin/term-synonyms')
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                const tbody = document.getElementById('synonymTableBody');
                if (data.synonyms.length === 0) {
                    tbody.innerHTML = '<tr><td colspan="6" class="text-center text-muted">등록된 유의어가 없습니다.</td></tr>';
                } else {
                    tbody.innerHTML = data.synonyms.map(syn => `
                        <tr>
                            <td>${syn.id}</td>
                            <td><span class="badge bg-primary">${syn.term1}</span> <small class="text-muted">(${syn.term1Type})</small></td>
                            <td class="text-center">↔</td>
                            <td><span class="badge bg-primary">${syn.term2}</span> <small class="text-muted">(${syn.term2Type})</small></td>
                            <td>${new Date(syn.createdAt).toLocaleString()}</td>
                            <td>
                                <button class="btn btn-sm btn-warning edit-synonym-btn me-1"
                                    data-synonym-id="${syn.id}"
                                    data-term1-id="${syn.term1Id}"
                                    data-term2-id="${syn.term2Id}"
                                    data-term1-name="${syn.term1}"
                                    data-term2-name="${syn.term2}">
                                    <i class="fas fa-edit"></i> 수정
                                </button>
                                <button class="btn btn-sm btn-danger delete-synonym-btn" data-synonym-id="${syn.id}">
                                    <i class="fas fa-trash"></i> 삭제
                                </button>
                            </td>
                        </tr>
                    `).join('');
                }
            } else {
                console.error('유의어 목록 로드 실패:', data.message);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            document.getElementById('synonymTableBody').innerHTML =
                '<tr><td colspan="6" class="text-center text-danger">로드 중 오류가 발생했습니다.</td></tr>';
        });
}

// 페이지가 synonyms 탭일 때 유의어 목록 로드
const currentTab = new URLSearchParams(window.location.search).get('tab');
if (currentTab === 'synonyms') {
    loadSynonyms();
}

// Term 검색 함수
function searchTerms(query, selectId) {
    if (query.length < 1) {
        return;
    }

    fetch(`/admin/terms/search?q=${encodeURIComponent(query)}`)
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                const select = document.getElementById(selectId);
                select.innerHTML = data.terms.map(term =>
                    `<option value="${term.id}">${term.term} (${term.termType})</option>`
                ).join('');
            }
        })
        .catch(error => console.error('Error:', error));
}

// Term 검색 입력 이벤트 (추가 모달)
const term1Search = document.getElementById('term1Search');
const term2Search = document.getElementById('term2Search');

if (term1Search) {
    term1Search.addEventListener('input', function() {
        searchTerms(this.value, 'term1Select');
    });
}

if (term2Search) {
    term2Search.addEventListener('input', function() {
        searchTerms(this.value, 'term2Select');
    });
}

// Term 검색 입력 이벤트 (수정 모달)
const editTerm1Search = document.getElementById('editTerm1Search');
const editTerm2Search = document.getElementById('editTerm2Search');

if (editTerm1Search) {
    editTerm1Search.addEventListener('input', function() {
        searchTerms(this.value, 'editTerm1Select');
    });
}

if (editTerm2Search) {
    editTerm2Search.addEventListener('input', function() {
        searchTerms(this.value, 'editTerm2Select');
    });
}

// 유의어 저장
const saveSynonymBtn = document.getElementById('saveSynonymBtn');
if (saveSynonymBtn) {
    saveSynonymBtn.addEventListener('click', function() {
        const term1Id = document.getElementById('term1Select').value;
        const term2Id = document.getElementById('term2Select').value;

        if (!term1Id || !term2Id) {
            alert('두 개의 term을 모두 선택해주세요.');
            return;
        }

        fetch('/admin/term-synonyms', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                termId1: parseInt(term1Id),
                termId2: parseInt(term2Id)
            })
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                alert(data.message);
                bootstrap.Modal.getInstance(document.getElementById('addSynonymModal')).hide();
                loadSynonyms();

                // 입력 초기화
                document.getElementById('term1Search').value = '';
                document.getElementById('term2Search').value = '';
                document.getElementById('term1Select').innerHTML = '<option value="">검색 결과가 여기에 표시됩니다</option>';
                document.getElementById('term2Select').innerHTML = '<option value="">검색 결과가 여기에 표시됩니다</option>';
            } else {
                alert('유의어 추가 실패: ' + data.message);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert('유의어 추가 중 오류가 발생했습니다.');
        });
    });
}

// 유의어 수정
const updateSynonymBtn = document.getElementById('updateSynonymBtn');
if (updateSynonymBtn) {
    updateSynonymBtn.addEventListener('click', function() {
        const synonymId = document.getElementById('editSynonymId').value;
        const term1Id = document.getElementById('editTerm1Select').value;
        const term2Id = document.getElementById('editTerm2Select').value;
        const term1Search = document.getElementById('editTerm1Search').value.trim();
        const term2Search = document.getElementById('editTerm2Search').value.trim();

        // 요청 본문 구성
        let requestBody = {};

        // Term 1 처리: ID가 있으면 ID 사용, 없으면 검색 필드 값 사용
        if (term1Id) {
            requestBody.termId1 = parseInt(term1Id);
        } else if (term1Search) {
            requestBody.termString1 = term1Search;
        } else {
            alert('첫 번째 term을 입력하거나 선택해주세요.');
            return;
        }

        // Term 2 처리: ID가 있으면 ID 사용, 없으면 검색 필드 값 사용
        if (term2Id) {
            requestBody.termId2 = parseInt(term2Id);
        } else if (term2Search) {
            requestBody.termString2 = term2Search;
        } else {
            alert('두 번째 term을 입력하거나 선택해주세요.');
            return;
        }

        console.log('[유의어 수정] synonymId:', synonymId, 'requestBody:', requestBody);

        fetch(`/admin/term-synonyms/${synonymId}`, {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(requestBody)
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                alert(data.message);
                bootstrap.Modal.getInstance(document.getElementById('editSynonymModal')).hide();
                loadSynonyms();

                // 입력 초기화
                document.getElementById('editSynonymId').value = '';
                document.getElementById('editTerm1Search').value = '';
                document.getElementById('editTerm2Search').value = '';
                document.getElementById('editTerm1Select').innerHTML = '<option value="">검색 결과가 여기에 표시됩니다</option>';
                document.getElementById('editTerm2Select').innerHTML = '<option value="">검색 결과가 여기에 표시됩니다</option>';
            } else {
                alert('유의어 수정 실패: ' + data.message);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert('유의어 수정 중 오류가 발생했습니다.');
        });
    });
}

// 유의어 수정 및 삭제 이벤트
document.addEventListener('click', function(e) {
    // 수정 버튼 클릭
    if (e.target.classList.contains('edit-synonym-btn') || e.target.closest('.edit-synonym-btn')) {
        const btn = e.target.classList.contains('edit-synonym-btn') ? e.target : e.target.closest('.edit-synonym-btn');
        const synonymId = btn.dataset.synonymId;
        const term1Id = btn.dataset.term1Id;
        const term2Id = btn.dataset.term2Id;
        const term1Name = btn.dataset.term1Name;
        const term2Name = btn.dataset.term2Name;

        // 모달에 현재 데이터 설정
        document.getElementById('editSynonymId').value = synonymId;

        // 검색 필드 초기화 및 현재 값 표시
        document.getElementById('editTerm1Search').value = term1Name;
        document.getElementById('editTerm2Search').value = term2Name;

        // 선택 필드에 현재 term 설정
        document.getElementById('editTerm1Select').innerHTML = `<option value="${term1Id}" selected>${term1Name}</option>`;
        document.getElementById('editTerm2Select').innerHTML = `<option value="${term2Id}" selected>${term2Name}</option>`;

        // 모달 표시
        const editModal = new bootstrap.Modal(document.getElementById('editSynonymModal'));
        editModal.show();
    }

    // 삭제 버튼 클릭
    if (e.target.classList.contains('delete-synonym-btn') || e.target.closest('.delete-synonym-btn')) {
        const btn = e.target.classList.contains('delete-synonym-btn') ? e.target : e.target.closest('.delete-synonym-btn');
        const synonymId = btn.dataset.synonymId;

        if (confirm('이 유의어 관계를 삭제하시겠습니까?')) {
            fetch(`/admin/term-synonyms/${synonymId}`, {
                method: 'DELETE'
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    alert(data.message);
                    loadSynonyms();
                } else {
                    alert('유의어 삭제 실패: ' + data.message);
                }
            })
            .catch(error => {
                console.error('Error:', error);
                alert('유의어 삭제 중 오류가 발생했습니다.');
            });
        }
    }
});

// ========== Term 통계 탭 - 유의어 표시 ==========
let currentEditTermId = null;
let currentEditTermName = null;

// Term 통계 탭일 때 유의어 로드
if (currentTab === 'stats') {
    // 페이지 로드 후 모든 synonym-cell에 대해 유의어 로드
    document.querySelectorAll('.synonym-cell').forEach(cell => {
        const termId = parseInt(cell.dataset.termId);
        console.log('[Init] Loading synonyms for cell with termId:', termId);
        loadSynonymsForCell(termId, cell);
    });
}

// 각 셀에 대해 유의어 로드
function loadSynonymsForCell(termId, cell) {
    console.log('[loadSynonymsForCell] Loading synonyms for termId:', termId);

    fetch(`/admin/term-synonyms`)
        .then(response => response.json())
        .then(data => {
            console.log('[loadSynonymsForCell] API Response:', data);

            if (data.success) {
                // 현재 termId와 관련된 유의어 필터링
                const synonyms = data.synonyms.filter(syn => {
                    const matches = syn.term1Id == termId || syn.term2Id == termId;
                    if (matches) {
                        console.log('[loadSynonymsForCell] Found synonym for termId', termId, ':', syn);
                    }
                    return matches;
                });

                console.log('[loadSynonymsForCell] Filtered synonyms for termId', termId, ':', synonyms);

                if (synonyms.length === 0) {
                    cell.innerHTML = '<small class="text-muted">-</small>';
                } else {
                    const synonymTerms = synonyms.map(syn => {
                        const synonymTerm = syn.term1Id == termId ? syn.term2 : syn.term1;
                        return `<span class="badge bg-secondary me-1">${synonymTerm}</span>`;
                    }).join('');
                    cell.innerHTML = synonymTerms;
                }
            } else {
                console.error('[loadSynonymsForCell] API returned success:false');
                cell.innerHTML = '<small class="text-danger">로드 실패</small>';
            }
        })
        .catch(error => {
            console.error('[loadSynonymsForCell] Error:', error);
            cell.innerHTML = '<small class="text-danger">오류</small>';
        });
}

// 유의어 편집 버튼 클릭
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('edit-synonyms-btn') || e.target.closest('.edit-synonyms-btn')) {
        const btn = e.target.classList.contains('edit-synonyms-btn') ? e.target : e.target.closest('.edit-synonyms-btn');
        currentEditTermId = btn.dataset.termId;
        currentEditTermName = btn.dataset.term;

        document.getElementById('editSynonymTermName').textContent = currentEditTermName;

        // 기존 유의어 로드
        loadExistingSynonyms(currentEditTermId);

        // 추천 숨기기
        document.getElementById('recommendedSynonyms').style.display = 'none';

        // 모달 표시
        const modal = new bootstrap.Modal(document.getElementById('editSynonymsModal'));
        modal.show();
    }
});

// 기존 유의어 로드
function loadExistingSynonyms(termId) {
    fetch(`/admin/term-synonyms`)
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                const synonyms = data.synonyms.filter(syn =>
                    syn.term1Id == termId || syn.term2Id == termId
                );

                const container = document.getElementById('existingSynonymsList');
                if (synonyms.length === 0) {
                    container.innerHTML = '<small class="text-muted">등록된 유의어가 없습니다.</small>';
                } else {
                    container.innerHTML = synonyms.map(syn => {
                        const synonymTerm = syn.term1Id == termId ? syn.term2 : syn.term1;
                        return `
                            <span class="badge bg-primary me-2 mb-2">
                                ${synonymTerm}
                                <button class="btn-close btn-close-white btn-sm ms-2"
                                        onclick="deleteSynonymFromModal(${syn.id})" style="font-size: 0.6rem;"></button>
                            </span>
                        `;
                    }).join('');
                }
            }
        })
        .catch(error => {
            console.error('Error:', error);
            document.getElementById('existingSynonymsList').innerHTML =
                '<small class="text-danger">로드 중 오류가 발생했습니다.</small>';
        });
}

// 모달에서 유의어 삭제
window.deleteSynonymFromModal = function(synonymId) {
    if (confirm('이 유의어 관계를 삭제하시겠습니까?')) {
        fetch(`/admin/term-synonyms/${synonymId}`, {
            method: 'DELETE'
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                loadExistingSynonyms(currentEditTermId);
                // 테이블도 업데이트
                const cell = document.querySelector(`.synonym-cell[data-term-id="${currentEditTermId}"]`);
                if (cell) {
                    loadSynonymsForCell(currentEditTermId, cell);
                }
            } else {
                alert('삭제 실패: ' + data.message);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert('삭제 중 오류가 발생했습니다.');
        });
    }
};

// 유의어 추가 버튼
const addNewSynonymBtn = document.getElementById('addNewSynonymBtn');
if (addNewSynonymBtn) {
    addNewSynonymBtn.addEventListener('click', function() {
        const newSynonym = document.getElementById('newSynonymInput').value.trim();

        if (!newSynonym) {
            alert('유의어를 입력해주세요.');
            return;
        }

        fetch('/admin/term-synonyms', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                termId1: parseInt(currentEditTermId),
                termString2: newSynonym
            })
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                alert(data.message);
                document.getElementById('newSynonymInput').value = '';
                loadExistingSynonyms(currentEditTermId);

                // 테이블도 업데이트
                const cell = document.querySelector(`.synonym-cell[data-term-id="${currentEditTermId}"]`);
                if (cell) {
                    loadSynonymsForCell(currentEditTermId, cell);
                }
            } else {
                alert('추가 실패: ' + data.message);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert('추가 중 오류가 발생했습니다.');
        });
    });
}

// AI 유의어 추천 버튼
const recommendSynonymsBtn = document.getElementById('recommendSynonymsBtn');
if (recommendSynonymsBtn) {
    recommendSynonymsBtn.addEventListener('click', function() {
        const btn = this;
        const originalText = btn.innerHTML;

        btn.disabled = true;
        btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> 추천 중...';

        fetch('/admin/term-synonyms/recommend', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                term: currentEditTermName
            })
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                const container = document.getElementById('recommendedSynonyms');
                const listDiv = document.getElementById('recommendedSynonymsList');

                if (data.recommendations.length === 0) {
                    listDiv.innerHTML = '<small class="text-muted">추천 결과가 없습니다.</small>';
                } else {
                    listDiv.innerHTML = data.recommendations.map(syn => `
                        <button class="btn btn-sm btn-outline-primary me-2 mb-2 add-recommended-syn-btn"
                                data-synonym="${syn}">
                            <i class="fas fa-plus"></i> ${syn}
                        </button>
                    `).join('');
                }

                container.style.display = 'block';
            } else {
                alert('추천 실패: ' + data.message);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert('추천 중 오류가 발생했습니다.');
        })
        .finally(() => {
            btn.disabled = false;
            btn.innerHTML = originalText;
        });
    });
}

// 추천된 유의어 추가 버튼 클릭
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('add-recommended-syn-btn') || e.target.closest('.add-recommended-syn-btn')) {
        const btn = e.target.classList.contains('add-recommended-syn-btn') ? e.target : e.target.closest('.add-recommended-syn-btn');
        const synonym = btn.dataset.synonym;

        fetch('/admin/term-synonyms', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                termId1: parseInt(currentEditTermId),
                termString2: synonym
            })
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                // 버튼 제거
                btn.remove();

                // 기존 유의어 목록 업데이트
                loadExistingSynonyms(currentEditTermId);

                // 테이블도 업데이트
                const cell = document.querySelector(`.synonym-cell[data-term-id="${currentEditTermId}"]`);
                if (cell) {
                    loadSynonymsForCell(currentEditTermId, cell);
                }

                // 성공 메시지
                alert(`'${synonym}' 유의어가 추가되었습니다.`);
            } else {
                alert('추가 실패: ' + data.message);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert('추가 중 오류가 발생했습니다.');
        });
    }
});

// ========== 일괄 유의어 추천 ==========
const startBatchRecommendBtn = document.getElementById('startBatchRecommendBtn');
if (startBatchRecommendBtn) {
    startBatchRecommendBtn.addEventListener('click', function() {
        const btn = this;
        const originalText = btn.innerHTML;
        const limit = parseInt(document.getElementById('synonymBatchLimit').value);

        if (limit < 1 || limit > 50) {
            alert('1~50 사이의 값을 입력해주세요.');
            return;
        }

        btn.disabled = true;
        btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> 처리 중...';

        const resultsDiv = document.getElementById('batchRecommendResults');
        resultsDiv.innerHTML = '<div class="text-center"><i class="fas fa-spinner fa-spin fa-2x"></i><p class="mt-2">AI가 유의어를 추천하고 있습니다. 잠시만 기다려주세요...</p></div>';

        fetch('/admin/term-synonyms/batch-recommend', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                limit: limit
            })
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                const recommendations = data.recommendations;
                const processedCount = data.processedCount;

                if (processedCount === 0) {
                    resultsDiv.innerHTML = '<div class="alert alert-info">유의어가 없는 term이 없습니다!</div>';
                } else {
                    let html = `<div class="alert alert-success">총 ${processedCount}개 term에 대한 유의어 추천 완료!</div>`;
                    html += '<div class="table-responsive"><table class="table table-sm">';
                    html += '<thead><tr><th>Term</th><th>추천 유의어</th><th>액션</th></tr></thead><tbody>';

                    for (const [term, synonyms] of Object.entries(recommendations)) {
                        if (synonyms.length > 0) {
                            html += `<tr>
                                <td><strong>${term}</strong></td>
                                <td>${synonyms.join(', ')}</td>
                                <td>
                                    ${synonyms.map(syn => `
                                        <button class="btn btn-sm btn-success batch-add-syn-btn"
                                                data-term="${term}" data-synonym="${syn}">
                                            <i class="fas fa-plus"></i>
                                        </button>
                                    `).join('')}
                                </td>
                            </tr>`;
                        } else {
                            html += `<tr>
                                <td><strong>${term}</strong></td>
                                <td colspan="2"><small class="text-muted">추천 결과 없음</small></td>
                            </tr>`;
                        }
                    }

                    html += '</tbody></table></div>';
                    resultsDiv.innerHTML = html;
                }
            } else {
                resultsDiv.innerHTML = `<div class="alert alert-danger">오류: ${data.message}</div>`;
            }
        })
        .catch(error => {
            console.error('Error:', error);
            resultsDiv.innerHTML = '<div class="alert alert-danger">일괄 추천 중 오류가 발생했습니다.</div>';
        })
        .finally(() => {
            btn.disabled = false;
            btn.innerHTML = originalText;
        });
    });
}

// 일괄 추천 결과에서 유의어 추가
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('batch-add-syn-btn') || e.target.closest('.batch-add-syn-btn')) {
        const btn = e.target.classList.contains('batch-add-syn-btn') ? e.target : e.target.closest('.batch-add-syn-btn');
        const term = btn.dataset.term;
        const synonym = btn.dataset.synonym;

        fetch('/admin/term-synonyms', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                termString1: term,
                termString2: synonym
            })
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                btn.innerHTML = '<i class="fas fa-check"></i>';
                btn.classList.remove('btn-success');
                btn.classList.add('btn-secondary');
                btn.disabled = true;

                alert(`'${term}' ↔ '${synonym}' 유의어가 추가되었습니다.`);
            } else {
                alert('추가 실패: ' + data.message);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert('추가 중 오류가 발생했습니다.');
        });
    }
});

// ========== Term 통계 탭 - Term 삭제 (불용어 등록) ==========
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('delete-term-btn') || e.target.closest('.delete-term-btn')) {
        const btn = e.target.classList.contains('delete-term-btn') ? e.target : e.target.closest('.delete-term-btn');
        const termId = btn.dataset.termId;
        const term = btn.dataset.term;

        if (confirm(`"${term}"을(를) 불용어로 등록하시겠습니까?\n\n이 term은 모든 article과 video에서 삭제되며, 향후 추출되지 않습니다.`)) {
            const reason = prompt('불용어 등록 사유를 입력하세요 (선택사항):', '');

            fetch(`/admin/terms/${termId}`, {
                method: 'DELETE',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    reason: reason || null
                })
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    alert(data.message);
                    location.reload();
                } else {
                    alert('Term 삭제 실패: ' + data.message);
                }
            })
            .catch(error => {
                console.error('Error:', error);
                alert('Term 삭제 중 오류가 발생했습니다.');
            });
        }
    }
});

