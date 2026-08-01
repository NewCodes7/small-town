(function () {
    const GRAFANA_DASHBOARD_PATH = '/admin/monitor/d/small-town-load-test/';

    function toEpochMs(datetimeLocalValue) {
        if (!datetimeLocalValue) {
            return null;
        }
        const d = new Date(datetimeLocalValue);
        return Number.isNaN(d.getTime()) ? null : d.getTime();
    }

    function toDatetimeLocalValue(isoString) {
        if (!isoString) {
            return '';
        }
        // Jackson이 내려주는 "2026-08-01T10:15:30" 형태를 datetime-local input 포맷(초 단위 절삭)으로 자른다.
        return isoString.substring(0, 16);
    }

    function buildGrafanaUrlDraft(testRunId, startedAtLocal, finishedAtLocal) {
        if (!testRunId) {
            return '';
        }
        const from = toEpochMs(startedAtLocal) ?? (Date.now() - 60 * 60 * 1000);
        const to = toEpochMs(finishedAtLocal) ?? Date.now();
        const params = new URLSearchParams({
            orgId: '1',
            'var-testid': testRunId,
            from: String(from),
            to: String(to)
        });
        return `${GRAFANA_DASHBOARD_PATH}?${params.toString()}`;
    }

    async function copyToClipboard(text, button) {
        if (!text) {
            return;
        }
        try {
            await navigator.clipboard.writeText(text);
            const original = button.textContent;
            button.textContent = '복사됨';
            setTimeout(() => { button.textContent = original; }, 1500);
        } catch (error) {
            console.error('클립보드 복사 실패', error);
        }
    }

    // ---- 새 실행 기록 생성 폼 ----
    (function initCreateForm() {
        const form = document.getElementById('ltCreateForm');
        if (!form) {
            return;
        }

        const executionTypeEl = document.getElementById('ltExecutionType');
        const targetEnvEl = document.getElementById('ltTargetEnv');
        const fargateFieldsEl = document.getElementById('ltFargateFields');
        const productionWarningEl = document.getElementById('ltProductionWarning');
        const resultEl = document.getElementById('ltCommandResult');
        const commandEl = document.getElementById('ltGeneratedCommand');
        const testRunIdNoteEl = document.getElementById('ltTestRunIdNote');
        const copyBtn = document.getElementById('ltCopyCommand');

        function syncExecutionTypeVisibility() {
            fargateFieldsEl.classList.toggle('d-none', executionTypeEl.value !== 'FARGATE');
        }

        function syncProductionWarning() {
            productionWarningEl.classList.toggle('d-none', targetEnvEl.value !== 'PRODUCTION');
        }

        executionTypeEl.addEventListener('change', syncExecutionTypeVisibility);
        targetEnvEl.addEventListener('change', syncProductionWarning);
        syncExecutionTypeVisibility();
        syncProductionWarning();

        form.addEventListener('submit', async (e) => {
            e.preventDefault();

            const body = {
                scenario: document.getElementById('ltScenario').value,
                executionType: executionTypeEl.value,
                targetEnv: targetEnvEl.value,
                triggeredBy: document.getElementById('ltTriggeredBy').value || null,
                extraEnv: document.getElementById('ltExtraEnv').value || null
            };

            if (executionTypeEl.value === 'FARGATE') {
                const taskCount = document.getElementById('ltTaskCount').value;
                const totalRate = document.getElementById('ltTotalRate').value;
                const totalVus = document.getElementById('ltTotalVus').value;
                const duration = document.getElementById('ltDuration').value;
                body.taskCount = taskCount ? Number(taskCount) : null;
                body.totalRate = totalRate ? Number(totalRate) : null;
                body.totalVus = totalVus ? Number(totalVus) : null;
                body.duration = duration || null;
                body.publicMode = document.getElementById('ltPublicMode').checked;
            }

            try {
                const res = await fetch('/api/admin/load-test/runs', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                if (!res.ok) {
                    const err = await res.json().catch(() => ({}));
                    throw new Error(err.message || `실행 기록 생성 실패 (${res.status})`);
                }
                const data = await res.json();

                commandEl.textContent = data.generatedCommand;
                testRunIdNoteEl.textContent = data.testRunId
                    ? `testid: ${data.testRunId} (자동 생성됨 — 그대로 기록됩니다)`
                    : 'Fargate 실행은 testid가 실행 후 터미널에 출력됩니다. 실행 후 아래 목록에서 해당 기록을 열어 testid를 입력해주세요.';
                resultEl.classList.remove('d-none');
            } catch (error) {
                alert(error.message || '실행 기록 생성 중 오류가 발생했습니다.');
                console.error(error);
            }
        });

        copyBtn.addEventListener('click', () => copyToClipboard(commandEl.textContent, copyBtn));
    })();

    // ---- 상세/수정 모달 ----
    (function initDetailModal() {
        const rows = document.querySelectorAll('.lt-run-row');
        const modalEl = document.getElementById('ltDetailModal');
        if (!rows.length || !modalEl) {
            return;
        }

        const modal = new bootstrap.Modal(modalEl);
        const loadingEl = document.getElementById('ltDetailLoading');
        const bodyEl = document.getElementById('ltDetailBody');
        const idEl = document.getElementById('ltDetailId');
        const scenarioEl = document.getElementById('ltDScenario');
        const execTargetEl = document.getElementById('ltDExecTarget');
        const paramsEl = document.getElementById('ltDParams');
        const commandEl = document.getElementById('ltDCommand');
        const copyBtn = document.getElementById('ltDCopyCommand');
        const editForm = document.getElementById('ltEditForm');
        const testRunIdEl = document.getElementById('ltEditTestRunId');
        const statusEl = document.getElementById('ltEditStatus');
        const passFailEl = document.getElementById('ltEditPassFail');
        const startedAtEl = document.getElementById('ltEditStartedAt');
        const finishedAtEl = document.getElementById('ltEditFinishedAt');
        const grafanaUrlEl = document.getElementById('ltEditGrafanaUrl');
        const metricsEl = document.getElementById('ltEditRecordedMetrics');
        const notesEl = document.getElementById('ltEditNotes');
        const triggeredByEl = document.getElementById('ltEditTriggeredBy');
        const fillGrafanaBtn = document.getElementById('ltFillGrafana');
        const deleteBtn = document.getElementById('ltDeleteRun');

        let currentId = null;

        function fill(el, value, fallback) {
            el.textContent = (value === null || value === undefined || value === '') ? (fallback || '-') : value;
        }

        async function openDetail(id) {
            currentId = id;
            loadingEl.classList.remove('d-none');
            bodyEl.classList.add('d-none');
            modal.show();

            try {
                const res = await fetch(`/api/admin/load-test/runs/${id}`);
                if (!res.ok) {
                    throw new Error('상세 정보를 불러오지 못했습니다.');
                }
                const data = await res.json();

                fill(idEl, `#${data.id}`);
                fill(scenarioEl, data.scenario);
                execTargetEl.textContent = `${data.executionType} / ${data.targetEnv}`;
                const paramParts = [];
                if (data.taskCount != null) paramParts.push(`task=${data.taskCount}`);
                if (data.totalRate != null) paramParts.push(`RPS=${data.totalRate}`);
                if (data.totalVus != null) paramParts.push(`VUS=${data.totalVus}`);
                if (data.duration) paramParts.push(`duration=${data.duration}`);
                if (data.publicMode) paramParts.push('--public');
                if (data.extraEnv) paramParts.push(`env: ${data.extraEnv.replace(/\n/g, ', ')}`);
                fill(paramsEl, paramParts.join(' · '));
                commandEl.textContent = data.generatedCommand || '';

                testRunIdEl.value = data.testRunId || '';
                statusEl.value = data.status || 'PLANNED';
                passFailEl.value = data.passFail === null || data.passFail === undefined ? '' : String(data.passFail);
                startedAtEl.value = toDatetimeLocalValue(data.startedAt);
                finishedAtEl.value = toDatetimeLocalValue(data.finishedAt);
                grafanaUrlEl.value = data.grafanaUrl || '';
                metricsEl.value = data.recordedMetrics || '';
                notesEl.value = data.notes || '';
                triggeredByEl.value = data.triggeredBy || '';

                loadingEl.classList.add('d-none');
                bodyEl.classList.remove('d-none');
            } catch (error) {
                loadingEl.textContent = '상세 정보를 불러오지 못했습니다.';
                console.error(error);
            }
        }

        rows.forEach(row => {
            row.addEventListener('click', () => openDetail(row.getAttribute('data-run-id')));
        });

        copyBtn.addEventListener('click', () => copyToClipboard(commandEl.textContent, copyBtn));

        fillGrafanaBtn.addEventListener('click', () => {
            grafanaUrlEl.value = buildGrafanaUrlDraft(testRunIdEl.value.trim(), startedAtEl.value, finishedAtEl.value);
        });

        editForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            if (!currentId) {
                return;
            }

            const body = {
                testRunId: testRunIdEl.value,
                status: statusEl.value,
                triggeredBy: triggeredByEl.value,
                startedAt: startedAtEl.value ? `${startedAtEl.value}:00` : null,
                finishedAt: finishedAtEl.value ? `${finishedAtEl.value}:00` : null,
                passFail: passFailEl.value === '' ? null : passFailEl.value === 'true',
                grafanaUrl: grafanaUrlEl.value,
                recordedMetrics: metricsEl.value,
                notes: notesEl.value
            };

            try {
                const res = await fetch(`/api/admin/load-test/runs/${currentId}`, {
                    method: 'PATCH',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                if (!res.ok) {
                    const err = await res.json().catch(() => ({}));
                    throw new Error(err.message || `저장 실패 (${res.status})`);
                }
                window.location.reload();
            } catch (error) {
                alert(error.message || '저장 중 오류가 발생했습니다.');
                console.error(error);
            }
        });

        deleteBtn.addEventListener('click', async () => {
            if (!currentId || !confirm('이 실행 기록을 삭제할까요?')) {
                return;
            }
            try {
                const res = await fetch(`/api/admin/load-test/runs/${currentId}`, { method: 'DELETE' });
                if (!res.ok) {
                    throw new Error('삭제 실패');
                }
                window.location.reload();
            } catch (error) {
                alert(error.message || '삭제 중 오류가 발생했습니다.');
                console.error(error);
            }
        });
    })();
})();
