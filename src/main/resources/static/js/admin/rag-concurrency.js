/**
 * RAG 동시 실행 상한 조회/변경 (admin/search-weights 페이지의 RAG 동시성 섹션).
 * 값의 배경은 RagConcurrencyLimiter Javadoc과
 * load-test/results/2026-08-27-rag-ladder.md 11장 참고.
 */
(function () {
    'use strict';

    const maxConcurrentInput = document.getElementById('ragMaxConcurrent');
    const acquireTimeoutInput = document.getElementById('ragAcquireTimeoutMs');
    const inUseLabel = document.getElementById('ragInUse');
    const saveBtn = document.getElementById('saveRagConcurrencyBtn');

    // 이 값을 넘기면 Bedrock async 풀(50)이 큐잉을 시작한다 — 근거는 RagConcurrencyLimiter Javadoc.
    const POOL_SAFE_MAX = 45;

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
        fetch('/admin/rag/concurrency')
            .then(res => res.json())
            .then(data => {
                if (!data.success) {
                    showAlert('danger', data.message || 'RAG 동시성 설정 조회 실패');
                    return;
                }
                maxConcurrentInput.value = data.maxConcurrent;
                acquireTimeoutInput.value = data.acquireTimeoutMs;
                inUseLabel.textContent = `${data.inUse} / ${data.maxConcurrent}`;
            })
            .catch(() => showAlert('danger', 'RAG 동시성 설정 조회 중 오류가 발생했습니다.'));
    }

    saveBtn.addEventListener('click', function () {
        const payload = {
            maxConcurrent: parseInt(maxConcurrentInput.value, 10),
            acquireTimeoutMs: parseInt(acquireTimeoutInput.value, 10)
        };
        if (!Number.isInteger(payload.maxConcurrent) || payload.maxConcurrent < 1) {
            showAlert('warning', '최대 동시 스트림 수는 1 이상의 정수여야 합니다.');
            return;
        }
        if (!Number.isInteger(payload.acquireTimeoutMs) || payload.acquireTimeoutMs < 0) {
            showAlert('warning', 'permit 대기 상한은 0 이상의 정수여야 합니다.');
            return;
        }
        // 45를 넘기면 Bedrock async 풀(bedrock.async-max-concurrency=50)에 닿기 시작한다.
        // 막지는 않되(부하테스트에서 의도적으로 올릴 수 있어야 한다) 확인은 받는다 —
        // 풀을 넘긴 초과분은 429가 아니라 조용한 대기가 되고, 힙도 스트림당 1.87MB씩 늘어난다.
        if (payload.maxConcurrent > POOL_SAFE_MAX
            && !window.confirm(
                `상한 ${payload.maxConcurrent}은(는) Bedrock async 풀 50에 근접하거나 넘습니다.\n`
                + '풀을 넘긴 초과분은 429가 아니라 조용한 대기가 되고, 힙도 스트림당 1.87MB씩 늘어납니다.\n'
                + '(힙 예산: 166MB + N x 1.87MB, heap max 512MB)\n\n계속할까요?')) {
            return;
        }

        fetch('/admin/rag/concurrency', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    showAlert('success', `RAG 동시 실행 상한을 ${data.maxConcurrent}(대기 ${data.acquireTimeoutMs}ms)로 변경했습니다.`);
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
