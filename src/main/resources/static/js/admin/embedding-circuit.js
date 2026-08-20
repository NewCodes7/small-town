/**
 * Clova 임베딩 서킷 브레이커 상태 조회 / 임계치 변경 / 수동 리셋.
 * 값의 배경은 EmbeddingCircuitBreaker Javadoc 참고.
 */
(function () {
    'use strict';

    const FIELDS = [
        'failureRateThreshold', 'slowCallRateThreshold', 'slowCallDurationMs',
        'waitDurationOpenMs', 'slidingWindowSize', 'minimumNumberOfCalls', 'permittedCallsHalfOpen'
    ];
    const STATE_BADGE = {
        CLOSED: 'bg-success',
        OPEN: 'bg-danger',
        HALF_OPEN: 'bg-warning text-dark'
    };

    const enabledInput = document.getElementById('circuitEnabled');
    const stateBadge = document.getElementById('circuitState');
    const ratesLabel = document.getElementById('circuitRates');
    const saveBtn = document.getElementById('saveCircuitBtn');
    const resetBtn = document.getElementById('resetCircuitBtn');

    if (!enabledInput || !saveBtn) {
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

    function renderState(data) {
        stateBadge.className = 'badge ' + (STATE_BADGE[data.state] || 'bg-light text-dark');
        stateBadge.textContent = data.state;
        // 호출 수가 최소치 미만이면 resilience4j가 -1을 돌려준다 — 그대로 노출하면 오해를 부른다
        const fmt = v => (v < 0 ? '집계 전' : v.toFixed(1) + '%');
        ratesLabel.textContent = `현재 실패율 ${fmt(data.failureRate)} · 느린 호출 ${fmt(data.slowCallRate)}`;
    }

    function load() {
        fetch('/admin/embedding/circuit')
            .then(res => res.json())
            .then(data => {
                if (!data.success) {
                    showAlert('danger', data.message || '차단기 상태 조회 실패');
                    return;
                }
                enabledInput.checked = data.enabled;
                FIELDS.forEach(f => {
                    document.getElementById(f).value = data[f];
                });
                renderState(data);
            })
            .catch(() => showAlert('danger', '차단기 상태 조회 중 오류가 발생했습니다.'));
    }

    saveBtn.addEventListener('click', function () {
        const payload = { enabled: enabledInput.checked };
        for (const f of FIELDS) {
            const value = Number(document.getElementById(f).value);
            if (!Number.isFinite(value) || value <= 0) {
                showAlert('warning', `${f}는 0보다 큰 값이어야 합니다.`);
                return;
            }
            payload[f] = value;
        }

        fetch('/admin/embedding/circuit', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    // 설정을 바꾸면 차단기 인스턴스가 새로 만들어져 슬라이딩 윈도우가 초기화된다
                    showAlert('success', '차단기 설정을 변경했습니다. (슬라이딩 윈도우는 초기화됩니다)');
                    load();
                } else {
                    showAlert('danger', data.message || '변경 실패');
                }
            })
            .catch(() => showAlert('danger', '변경 중 오류가 발생했습니다.'));
    });

    resetBtn.addEventListener('click', function () {
        fetch('/admin/embedding/circuit/reset', { method: 'POST' })
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    showAlert('success', `차단기를 리셋했습니다. 현재 상태: ${data.state}`);
                    load();
                } else {
                    showAlert('danger', data.message || '리셋 실패');
                }
            })
            .catch(() => showAlert('danger', '리셋 중 오류가 발생했습니다.'));
    });

    const refreshBtn = document.getElementById('refreshBtn');
    if (refreshBtn) {
        refreshBtn.addEventListener('click', load);
    }

    load();
})();
