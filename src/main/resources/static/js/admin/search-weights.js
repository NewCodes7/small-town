const COMPLEXITIES = ['SIMPLE', 'MODERATE', 'COMPLEX'];

function showAlert(message, type) {
    const alertArea = document.getElementById('alertArea');
    alertArea.innerHTML = `
        <div class="alert alert-${type} alert-dismissible fade show" role="alert">
            ${message}
            <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
        </div>`;
    alertArea.style.display = 'block';
    if (type === 'success') {
        setTimeout(() => {
            const alert = alertArea.querySelector('.alert');
            if (alert) alert.click();
        }, 3000);
    }
}

function updateSumHint(complexity) {
    const bm25 = parseFloat(document.getElementById(`${complexity}_bm25NsfWeight`).value) || 0;
    const vec  = parseFloat(document.getElementById(`${complexity}_vectorNsfWeight`).value) || 0;
    const sum  = Math.round((bm25 + vec) * 100) / 100;
    const hint = document.getElementById(`${complexity}_sumHint`);
    if (sum === 0) {
        hint.textContent = '';
        hint.className = 'nsf-sum-hint text-muted small mb-3';
    } else if (Math.abs(sum - 1.0) < 0.001) {
        hint.textContent = `BM25 + Vector = ${sum} ✓`;
        hint.className = 'nsf-sum-hint text-success small mb-3';
    } else {
        hint.textContent = `BM25 + Vector = ${sum} (권장: 합계 1.0)`;
        hint.className = 'nsf-sum-hint text-danger small mb-3';
    }
}

function loadWeights() {
    fetch('/admin/search/weights')
        .then(res => res.json())
        .then(data => {
            if (!data.success) {
                showAlert('가중치 로드 실패: ' + (data.message || '알 수 없는 오류'), 'danger');
                return;
            }
            const weights = data.weights;
            COMPLEXITIES.forEach(c => {
                const w = weights[c];
                if (!w) return;
                document.getElementById(`${c}_titleMultiplier`).value = w.titleMultiplier;
                document.getElementById(`${c}_bm25NsfWeight`).value   = w.bm25NsfWeight;
                document.getElementById(`${c}_vectorNsfWeight`).value  = w.vectorNsfWeight;
                updateSumHint(c);
            });
        })
        .catch(err => showAlert('가중치 로드 중 오류: ' + err.message, 'danger'));
}

function updateWeights(complexity) {
    const titleMultiplier = parseFloat(document.getElementById(`${complexity}_titleMultiplier`).value);
    const bm25NsfWeight   = parseFloat(document.getElementById(`${complexity}_bm25NsfWeight`).value);
    const vectorNsfWeight = parseFloat(document.getElementById(`${complexity}_vectorNsfWeight`).value);

    if (isNaN(titleMultiplier) || isNaN(bm25NsfWeight) || isNaN(vectorNsfWeight)) {
        showAlert('모든 값을 올바르게 입력해주세요.', 'warning');
        return;
    }

    fetch(`/admin/search/weights/${complexity}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ titleMultiplier, bm25NsfWeight, vectorNsfWeight })
    })
        .then(res => res.json())
        .then(data => {
            if (data.success) {
                showAlert(`[${complexity}] 가중치 업데이트 완료 — title: ${titleMultiplier}, BM25: ${bm25NsfWeight}, Vector: ${vectorNsfWeight}`, 'success');
            } else {
                showAlert(`[${complexity}] 업데이트 실패: ` + (data.message || '알 수 없는 오류'), 'danger');
            }
        })
        .catch(err => showAlert(`[${complexity}] 요청 중 오류: ` + err.message, 'danger'));
}

// NSF 합계 실시간 계산
COMPLEXITIES.forEach(c => {
    ['bm25NsfWeight', 'vectorNsfWeight'].forEach(field => {
        document.getElementById(`${c}_${field}`).addEventListener('input', () => updateSumHint(c));
    });
});

document.getElementById('refreshBtn').addEventListener('click', loadWeights);

// 페이지 로드 시 자동으로 현재 값 불러오기
loadWeights();
