// ===== Term 관련 이벤트 핸들러 =====

// Article Term 추출 실행 버튼
const extractArticleTermsBtn = document.getElementById('extractArticleTermsBtn');
if (extractArticleTermsBtn) {
    extractArticleTermsBtn.addEventListener('click', function() {
        if (confirm('모든 article의 term을 추출합니다. 이미 분석된 article은 건너뜁니다. 계속하시겠습니까?')) {
            const btn = this;
            const originalText = btn.innerHTML;

            // 버튼 비활성화 및 로딩 표시
            btn.disabled = true;
            btn.innerHTML = '<i class="fas fa-spinner fa-spin me-2"></i>Term 추출 중...';

            fetch('/admin/articles/extract-terms', {
                method: 'GET'
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    alert(data.message);
                    // 3분 후 버튼 재활성화 및 페이지 새로고침
                    setTimeout(() => {
                        btn.disabled = false;
                        btn.innerHTML = originalText;
                        location.reload();
                    }, 180000);
                } else {
                    alert('Term 추출 실패: ' + data.message);
                    btn.disabled = false;
                    btn.innerHTML = originalText;
                }
            })
            .catch(error => {
                console.error('Error:', error);
                alert('Term 추출 중 오류가 발생했습니다.');
                btn.disabled = false;
                btn.innerHTML = originalText;
            });
        }
    });
}

// Article Term 강제 재분석 버튼
const reextractArticleTermsBtn = document.getElementById('reextractArticleTermsBtn');
if (reextractArticleTermsBtn) {
    reextractArticleTermsBtn.addEventListener('click', function() {
        if (confirm('⚠️ 모든 article의 term을 강제로 재분석합니다.\n이미 분석된 article도 모두 다시 분석됩니다.\n\n시간이 오래 걸릴 수 있습니다. 계속하시겠습니까?')) {
            const btn = this;
            const originalText = btn.innerHTML;

            // 버튼 비활성화 및 로딩 표시
            btn.disabled = true;
            btn.innerHTML = '<i class="fas fa-spinner fa-spin me-2"></i>강제 재분석 중...';

            fetch('/admin/articles/reextract-all-terms', {
                method: 'GET'
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    alert('✅ ' + data.message);
                } else {
                    alert('❌ ' + data.message);
                }
                btn.disabled = false;
                btn.innerHTML = originalText;
            })
            .catch(error => {
                console.error('Error:', error);
                alert('❌ 오류가 발생했습니다: ' + error);
                btn.disabled = false;
                btn.innerHTML = originalText;
            });
        }
    });
}

// Video Term 추출 실행 버튼
const extractVideoTermsBtn = document.getElementById('extractVideoTermsBtn');
if (extractVideoTermsBtn) {
    extractVideoTermsBtn.addEventListener('click', function() {
        if (confirm('모든 video의 term을 추출합니다. 이미 분석된 video는 건너뜁니다. 계속하시겠습니까?')) {
            const btn = this;
            const originalText = btn.innerHTML;

            // 버튼 비활성화 및 로딩 표시
            btn.disabled = true;
            btn.innerHTML = '<i class="fas fa-spinner fa-spin me-2"></i>Term 추출 중...';

            fetch('/admin/videos/extract-terms', {
                method: 'GET'
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    alert(data.message);
                    // 3분 후 버튼 재활성화 및 페이지 새로고침
                    setTimeout(() => {
                        btn.disabled = false;
                        btn.innerHTML = originalText;
                        location.reload();
                    }, 180000);
                } else {
                    alert('Term 추출 실패: ' + data.message);
                    btn.disabled = false;
                    btn.innerHTML = originalText;
                }
            })
            .catch(error => {
                console.error('Error:', error);
                alert('Term 추출 중 오류가 발생했습니다.');
                btn.disabled = false;
                btn.innerHTML = originalText;
            });
        }
    });
}

// Video Term 강제 재분석 버튼
const reextractVideoTermsBtn = document.getElementById('reextractVideoTermsBtn');
if (reextractVideoTermsBtn) {
    reextractVideoTermsBtn.addEventListener('click', function() {
        if (confirm('⚠️ 모든 video의 term을 강제로 재분석합니다.\n이미 분석된 video도 모두 다시 분석됩니다.\n\n시간이 오래 걸릴 수 있습니다. 계속하시겠습니까?')) {
            const btn = this;
            const originalText = btn.innerHTML;

            // 버튼 비활성화 및 로딩 표시
            btn.disabled = true;
            btn.innerHTML = '<i class="fas fa-spinner fa-spin me-2"></i>강제 재분석 중...';

            fetch('/admin/videos/reextract-all-terms', {
                method: 'GET'
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    alert('✅ ' + data.message);
                } else {
                    alert('❌ ' + data.message);
                }
                btn.disabled = false;
                btn.innerHTML = originalText;
            })
            .catch(error => {
                console.error('Error:', error);
                alert('❌ 오류가 발생했습니다: ' + error);
                btn.disabled = false;
                btn.innerHTML = originalText;
            });
        }
    });
}

// Term 삭제 버튼 클릭 (불용어 등록)
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('delete-term-btn') || e.target.closest('.delete-term-btn')) {
        const btn = e.target.classList.contains('delete-term-btn') ? e.target : e.target.closest('.delete-term-btn');
        const termId = btn.dataset.termId;
        const term = btn.dataset.term;
        const termType = btn.dataset.termType;

        if (confirm(`"${term}" (${termType})를 불용어로 등록하시겠습니까?\n\n이 term은 모든 article에서 삭제되며, 향후 추출되지 않습니다.`)) {
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

// Term 로드 및 표시 (페이지 로드 시)
document.addEventListener('DOMContentLoaded', function() {
    // Article Term 로드
    const termsContainers = document.querySelectorAll('.terms-container');
    termsContainers.forEach(container => {
        const articleId = container.dataset.articleId;

        fetch(`/admin/articles/${articleId}/terms`)
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    if (data.terms && data.terms.length > 0) {
                        // Term을 score 기준으로 정렬
                        const sortedTerms = data.terms.sort((a, b) => (b.score || 0) - (a.score || 0));

                        // Term을 뱃지로 표시 (클릭 가능)
                        let html = '<div class="d-flex flex-wrap gap-1">';
                        sortedTerms.forEach((term, index) => {
                            const scorePercent = ((term.score || 0) * 100).toFixed(0);
                            const badgeClass = (term.score || 0) > 0.7 ? 'bg-danger' : (term.score || 0) > 0.4 ? 'bg-warning text-dark' : 'bg-info';
                            html += `
                                <span class="badge ${badgeClass} term-badge-editable"
                                        style="cursor: pointer;"
                                        data-article-id="${articleId}"
                                        data-article-term-id="${term.id}"
                                        data-term-id="${term.termId}"
                                        data-term="${term.term}"
                                        data-score="${term.score || 0}"
                                        data-frequency="${term.frequency}"
                                        title="클릭하여 수정/삭제 (${term.termType})">
                                    ${term.term} <small>score: ${scorePercent}%</small>
                                </span>
                            `;
                        });
                        html += `<button class="btn btn-sm btn-success add-term-btn" data-article-id="${articleId}">
                                    <i class="fas fa-plus"></i> Term 추가
                                    </button>`;
                        html += '</div>';
                        html += `<small class="text-muted mt-1 d-block">총 ${sortedTerms.length}개 term</small>`;

                        container.innerHTML = html;
                    } else {
                        container.innerHTML = '<span class="badge bg-secondary">Term 없음</span>';
                    }
                } else {
                    container.innerHTML = '<span class="badge bg-secondary">-</span>';
                }
            })
            .catch(error => {
                console.error('Error loading terms:', error);
                container.innerHTML = '<span class="badge bg-danger"><i class="fas fa-exclamation"></i> 오류</span>';
            });
    });

    // Video Term 로드
    const videoTermsContainers = document.querySelectorAll('.video-terms-container');
    videoTermsContainers.forEach(container => {
        const videoId = container.dataset.videoId;

        fetch(`/admin/videos/${videoId}/terms`)
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    if (data.terms && data.terms.length > 0) {
                        const sortedTerms = data.terms.sort((a, b) => b.frequency - a.frequency);

                        let html = '<div class="d-flex flex-wrap gap-1">';
                        sortedTerms.forEach((term, index) => {
                            const badgeClass = term.frequency > 2 ? 'bg-danger' : term.frequency > 1 ? 'bg-warning text-dark' : 'bg-info';
                            html += `
                                <span class="badge ${badgeClass}" title="${term.termType}">
                                    ${term.term} <small>x${term.frequency}</small>
                                </span>
                            `;
                        });
                        html += '</div>';
                        html += `<small class="text-muted mt-1 d-block">총 ${sortedTerms.length}개 term</small>`;

                        container.innerHTML = html;
                    } else {
                        container.innerHTML = '<span class="badge bg-secondary">Term 없음</span>';
                    }
                } else {
                    container.innerHTML = '<span class="badge bg-secondary">-</span>';
                }
            })
            .catch(error => {
                console.error('Error loading video terms:', error);
                container.innerHTML = '<span class="badge bg-danger"><i class="fas fa-exclamation"></i> 오류</span>';
            });
    });
});

// ===== Term 재분석 기능 =====

// Article Term 재분석 버튼 클릭
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('reanalyze-terms-btn') || e.target.closest('.reanalyze-terms-btn')) {
        const btn = e.target.classList.contains('reanalyze-terms-btn') ? e.target : e.target.closest('.reanalyze-terms-btn');
        const articleId = btn.dataset.articleId;
        const articleTitle = btn.dataset.articleTitle;

        if (confirm(`"${articleTitle}"\n\n이 게시글의 term을 재분석하시겠습니까?`)) {
            const originalHtml = btn.innerHTML;
            btn.disabled = true;
            btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i>';

            fetch(`/admin/articles/${articleId}/reanalyze-terms`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                }
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    alert(data.message);
                    // Term 목록 새로고침
                    loadTermsForArticle(articleId);
                } else {
                    alert('Term 재분석 실패: ' + data.message);
                }
                btn.disabled = false;
                btn.innerHTML = originalHtml;
            })
            .catch(error => {
                console.error('Error:', error);
                alert('Term 재분석 중 오류가 발생했습니다.');
                btn.disabled = false;
                btn.innerHTML = originalHtml;
            });
        }
    }
});

// Video Term 재분석 버튼 클릭
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('reanalyze-video-terms-btn') || e.target.closest('.reanalyze-video-terms-btn')) {
        const btn = e.target.classList.contains('reanalyze-video-terms-btn') ? e.target : e.target.closest('.reanalyze-video-terms-btn');
        const videoId = btn.dataset.videoId;
        const videoTitle = btn.dataset.videoTitle;

        if (confirm(`"${videoTitle}"\n\n이 영상의 term을 재분석하시겠습니까?`)) {
            const originalHtml = btn.innerHTML;
            btn.disabled = true;
            btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i>';

            fetch(`/admin/videos/${videoId}/reanalyze-terms`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                }
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    alert(data.message);
                    // Term 목록 새로고침
                    loadTermsForVideo(videoId);
                } else {
                    alert('Term 재분석 실패: ' + data.message);
                }
                btn.disabled = false;
                btn.innerHTML = originalHtml;
            })
            .catch(error => {
                console.error('Error:', error);
                alert('Term 재분석 중 오류가 발생했습니다.');
                btn.disabled = false;
                btn.innerHTML = originalHtml;
            });
        }
    }
});

// 특정 Article의 Term 로드
function loadTermsForArticle(articleId) {
    const container = document.querySelector(`.terms-container[data-article-id="${articleId}"]`);
    if (!container) return;

    container.innerHTML = '<div class="text-muted"><i class="fas fa-spinner fa-spin me-2"></i>로딩 중...</div>';

    fetch(`/admin/articles/${articleId}/terms`)
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                if (data.terms && data.terms.length > 0) {
                    const sortedTerms = data.terms.sort((a, b) => (b.score || 0) - (a.score || 0));

                    let html = '<div class="d-flex flex-wrap gap-1">';
                    sortedTerms.forEach((term, index) => {
                        const scorePercent = ((term.score || 0) * 100).toFixed(0);
                        const badgeClass = (term.score || 0) > 0.7 ? 'bg-danger' : (term.score || 0) > 0.4 ? 'bg-warning text-dark' : 'bg-info';
                        html += `
                            <span class="badge ${badgeClass} term-badge-editable"
                                    style="cursor: pointer;"
                                    data-article-id="${articleId}"
                                    data-article-term-id="${term.id}"
                                    data-term-id="${term.termId}"
                                    data-term="${term.term}"
                                    data-score="${term.score || 0}"
                                    data-frequency="${term.frequency}"
                                    title="클릭하여 수정/삭제 (${term.termType})">
                                ${term.term} <small>score: ${scorePercent}%</small>
                            </span>
                        `;
                    });
                    html += `<button class="btn btn-sm btn-success add-term-btn" data-article-id="${articleId}">
                                <i class="fas fa-plus"></i> Term 추가
                                </button>`;
                    html += '</div>';
                    html += `<small class="text-muted mt-1 d-block">총 ${sortedTerms.length}개 term</small>`;

                    container.innerHTML = html;
                } else {
                    container.innerHTML = '<span class="badge bg-secondary">Term 없음</span>';
                }
            } else {
                container.innerHTML = '<span class="badge bg-secondary">-</span>';
            }
        })
        .catch(error => {
            console.error('Error loading terms:', error);
            container.innerHTML = '<span class="badge bg-danger"><i class="fas fa-exclamation"></i> 오류</span>';
        });
}

// 특정 Video의 Term 로드
function loadTermsForVideo(videoId) {
    const container = document.querySelector(`.video-terms-container[data-video-id="${videoId}"]`);
    if (!container) return;

    container.innerHTML = '<div class="text-muted"><i class="fas fa-spinner fa-spin me-2"></i>로딩 중...</div>';

    fetch(`/admin/videos/${videoId}/terms`)
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                if (data.terms && data.terms.length > 0) {
                    const sortedTerms = data.terms.sort((a, b) => b.frequency - a.frequency);

                    let html = '<div class="d-flex flex-wrap gap-1">';
                    sortedTerms.forEach((term, index) => {
                        const badgeClass = term.frequency > 2 ? 'bg-danger' : term.frequency > 1 ? 'bg-warning text-dark' : 'bg-info';
                        html += `
                            <span class="badge ${badgeClass}" title="${term.termType}">
                                ${term.term} <small>x${term.frequency}</small>
                            </span>
                        `;
                    });
                    html += '</div>';
                    html += `<small class="text-muted mt-1 d-block">총 ${sortedTerms.length}개 term</small>`;

                    container.innerHTML = html;
                } else {
                    container.innerHTML = '<span class="badge bg-secondary">Term 없음</span>';
                }
            } else {
                container.innerHTML = '<span class="badge bg-secondary">-</span>';
            }
        })
        .catch(error => {
            console.error('Error loading video terms:', error);
            container.innerHTML = '<span class="badge bg-danger"><i class="fas fa-exclamation"></i> 오류</span>';
        });
}

// ===== 사용자 사전 관리 기능 =====

// 사용자 사전 관리 버튼 클릭
const manageUserDictionaryBtn = document.getElementById('manageUserDictionaryBtn');
if (manageUserDictionaryBtn) {
    manageUserDictionaryBtn.addEventListener('click', function() {
        loadUserDictionary();
        const modal = new bootstrap.Modal(document.getElementById('userDictionaryModal'));
        modal.show();
    });
}

// 사용자 사전 로드
function loadUserDictionary() {
    const listContainer = document.getElementById('userDictionaryList');
    listContainer.innerHTML = '<div class="text-center text-muted py-3"><i class="fas fa-spinner fa-spin me-2"></i>로딩 중...</div>';

    fetch('/admin/user-dictionary')
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                document.getElementById('userDictCount').textContent = data.totalCount;

                if (data.userDictionaries && data.userDictionaries.length > 0) {
                    let html = '<table class="table table-sm table-hover">';
                    html += '<thead class="table-light">';
                    html += '<tr><th>단어</th><th>품사</th><th>사유</th><th>등록일</th><th>액션</th></tr>';
                    html += '</thead><tbody>';

                    data.userDictionaries.forEach(dict => {
                        const createdAt = new Date(dict.createdAt).toLocaleString('ko-KR');
                        html += `
                            <tr>
                                <td><strong>${dict.word}</strong></td>
                                <td><span class="badge bg-secondary">${dict.posTag}</span></td>
                                <td>${dict.reason || '-'}</td>
                                <td><small class="text-muted">${createdAt}</small></td>
                                <td>
                                    <button class="btn btn-sm btn-danger delete-user-word-btn"
                                            data-word-id="${dict.id}"
                                            data-word="${dict.word}">
                                        <i class="fas fa-trash"></i>
                                    </button>
                                </td>
                            </tr>
                        `;
                    });

                    html += '</tbody></table>';
                    listContainer.innerHTML = html;
                } else {
                    listContainer.innerHTML = '<div class="text-center text-muted py-3">등록된 단어가 없습니다.</div>';
                }
            } else {
                listContainer.innerHTML = '<div class="alert alert-danger">오류: ' + data.message + '</div>';
            }
        })
        .catch(error => {
            console.error('Error:', error);
            listContainer.innerHTML = '<div class="alert alert-danger">사용자 사전 로드 중 오류가 발생했습니다.</div>';
        });
}

// 사용자 단어 추가 폼 제출
document.getElementById('addUserWordForm').addEventListener('submit', function(e) {
    e.preventDefault();

    const word = document.getElementById('userWord').value.trim();
    const posTag = document.getElementById('userPosTag').value;
    const reason = document.getElementById('userReason').value.trim();

    if (!word) {
        alert('단어를 입력해주세요.');
        return;
    }

    fetch('/admin/user-dictionary', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({
            word: word,
            posTag: posTag,
            reason: reason || null
        })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert(data.message);
            // 폼 초기화
            document.getElementById('userWord').value = '';
            document.getElementById('userPosTag').value = 'NNG';
            document.getElementById('userReason').value = '';
            // 목록 새로고침
            loadUserDictionary();
        } else {
            alert('단어 추가 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('Error:', error);
        alert('단어 추가 중 오류가 발생했습니다.');
    });
});

// 사용자 단어 삭제 버튼 클릭 (이벤트 위임)
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('delete-user-word-btn') || e.target.closest('.delete-user-word-btn')) {
        const btn = e.target.classList.contains('delete-user-word-btn') ? e.target : e.target.closest('.delete-user-word-btn');
        const wordId = btn.dataset.wordId;
        const word = btn.dataset.word;

        if (confirm(`"${word}" 단어를 삭제하시겠습니까?\n\n애플리케이션을 재시작하면 형태소 분석에서 제외됩니다.`)) {
            fetch(`/admin/user-dictionary/${wordId}`, {
                method: 'DELETE',
                headers: {
                    'Content-Type': 'application/json',
                }
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    alert(data.message);
                    loadUserDictionary();
                } else {
                    alert('단어 삭제 실패: ' + data.message);
                }
            })
            .catch(error => {
                console.error('Error:', error);
                alert('단어 삭제 중 오류가 발생했습니다.');
            });
        }
    }
});

