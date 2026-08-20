/**
 * 검색 동시 실행 상한 조회/변경 (admin/search-weights 페이지의 동시성 섹션).
 * 값의 배경은 SearchConcurrencyLimiter Javadoc 참고.
 */
(function () {
    'use strict';

    const maxConcurrentInput = document.getElementById('maxConcurrent');
    const acquireTimeoutInput = document.getElementById('acquireTimeoutMs');
    const inUseLabel = document.getElementById('inUse');
    const saveBtn = document.getElementById('saveConcurrencyBtn');

    if (!maxConcurrentInput || !saveBtn) {
        return;
    }

    function showAlert(type, message) {
        const area = document.getElementById('alertArea');
        if (!area) {
            return;
        }
        area.innerHTML = `<div class="alert alert-${type} alert-dismissible fade show">${message}`
            + '<button type="button" class="btn-close" data-bs-dismiss="alert"></button></div>';
        area.style.display = 'block';
    }

    function load() {
        fetch('/admin/search/concurrency')
            .then(res => res.json())
            .then(data => {
                if (!data.success) {
                    showAlert('danger', data.message || '동시성 설정 조회 실패');
                    return;
                }
                maxConcurrentInput.value = data.maxConcurrent;
                acquireTimeoutInput.value = data.acquireTimeoutMs;
                inUseLabel.textContent = `${data.inUse} / ${data.maxConcurrent}`;
            })
            .catch(() => showAlert('danger', '동시성 설정 조회 중 오류가 발생했습니다.'));
    }

    saveBtn.addEventListener('click', function () {
        const payload = {
            maxConcurrent: parseInt(maxConcurrentInput.value, 10),
            acquireTimeoutMs: parseInt(acquireTimeoutInput.value, 10)
        };
        if (!Number.isInteger(payload.maxConcurrent) || payload.maxConcurrent < 1) {
            showAlert('warning', '최대 동시 실행 수는 1 이상의 정수여야 합니다.');
            return;
        }
        if (!Number.isInteger(payload.acquireTimeoutMs) || payload.acquireTimeoutMs < 0) {
            showAlert('warning', 'permit 대기 상한은 0 이상의 정수여야 합니다.');
            return;
        }

        fetch('/admin/search/concurrency', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    showAlert('success', `동시 실행 상한을 ${data.maxConcurrent}(대기 ${data.acquireTimeoutMs}ms)로 변경했습니다.`);
                    load();
                } else {
                    showAlert('danger', data.message || '변경 실패');
                }
            })
            .catch(() => showAlert('danger', '변경 중 오류가 발생했습니다.'));
    });

    const refreshBtn = document.getElementById('refreshBtn');
    if (refreshBtn) {
        refreshBtn.addEventListener('click', load);
    }

    load();
})();
