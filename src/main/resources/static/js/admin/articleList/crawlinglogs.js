// ============================================
// Crawling Logs Tab
// ============================================

(function () {
    const runRows = document.querySelectorAll('.crawling-run-row');
    if (!runRows.length) {
        return;
    }

    const summaryEl = document.getElementById('crawlingRunSummary');
    const detailTitleEl = document.getElementById('crawlingRunDetailTitle');
    const tableBody = document.getElementById('crawlingLogTableBody');

    const statusClassMap = {
        SUCCESS: 'bg-success',
        FAILURE: 'bg-danger',
        SKIPPED: 'bg-secondary'
    };

    function renderStatusBadge(status, error) {
        const badge = document.createElement('span');
        badge.className = `badge ${statusClassMap[status] || 'bg-secondary'}`;
        badge.textContent = status || '-';

        const wrapper = document.createElement('div');
        wrapper.appendChild(badge);

        if (error) {
            const errorLine = document.createElement('div');
            errorLine.className = 'text-muted small';
            errorLine.textContent = error;
            wrapper.appendChild(errorLine);
        }

        return wrapper;
    }

    async function loadRunDetail(runId) {
        if (!runId) {
            return;
        }

        runRows.forEach(row => row.classList.remove('table-active'));
        const activeRow = document.querySelector(`.crawling-run-row[data-run-id="${runId}"]`);
        if (activeRow) {
            activeRow.classList.add('table-active');
        }

        tableBody.innerHTML = '<tr><td colspan="6" class="text-center text-muted py-4">불러오는 중...</td></tr>';

        try {
            const [runRes, logsRes] = await Promise.all([
                fetch(`/api/admin/crawling/runs/${runId}`),
                fetch(`/api/admin/crawling/runs/${runId}/logs?page=0&size=20`)
            ]);

            if (!runRes.ok || !logsRes.ok) {
                throw new Error('로그 정보를 불러오지 못했습니다.');
            }

            const runData = await runRes.json();
            const logsData = await logsRes.json();

            detailTitleEl.textContent = `#${runData.id} (${runData.jobType})`;
            const errorText = runData.errorMessage ? ` | 오류: ${runData.errorMessage}` : '';
            summaryEl.textContent = `상태: ${runData.status} | 성공: ${runData.successCount} | 실패: ${runData.failureCount} | 신규: ${runData.newItemsCount}${errorText}`;

            if (!logsData.logs || logsData.logs.length === 0) {
                tableBody.innerHTML = '<tr><td colspan="6" class="text-center text-muted py-4">상세 로그가 없습니다.</td></tr>';
                return;
            }

            tableBody.innerHTML = '';
            logsData.logs.forEach(log => {
                const row = document.createElement('tr');

                const idCell = document.createElement('td');
                idCell.textContent = log.articleId || '-';
                row.appendChild(idCell);

                const titleCell = document.createElement('td');
                const title = document.createElement('div');
                title.textContent = log.articleTitle || '-';
                titleCell.appendChild(title);
                if (log.articleLink) {
                    const link = document.createElement('a');
                    link.href = log.articleLink;
                    link.target = '_blank';
                    link.className = 'small text-decoration-none';
                    link.textContent = '원문';
                    titleCell.appendChild(link);
                }
                row.appendChild(titleCell);

                const contentCell = document.createElement('td');
                contentCell.appendChild(renderStatusBadge(log.contentExtractionStatus, log.contentExtractionError));
                row.appendChild(contentCell);

                const termCell = document.createElement('td');
                termCell.appendChild(renderStatusBadge(log.termAnalysisStatus, log.termAnalysisError));
                row.appendChild(termCell);

                const embeddingCell = document.createElement('td');
                embeddingCell.appendChild(renderStatusBadge(log.embeddingStatus, log.embeddingError));
                row.appendChild(embeddingCell);

                const repCell = document.createElement('td');
                repCell.appendChild(renderStatusBadge(log.representativeChunkStatus, log.representativeChunkError));
                repCell.appendChild(renderStatusBadge(log.categoryStatus, log.categoryError));
                row.appendChild(repCell);

                tableBody.appendChild(row);
            });

        } catch (error) {
            summaryEl.textContent = '로그 정보를 불러오지 못했습니다.';
            tableBody.innerHTML = '<tr><td colspan="6" class="text-center text-danger py-4">오류가 발생했습니다.</td></tr>';
            console.error(error);
        }
    }

    runRows.forEach(row => {
        row.addEventListener('click', () => {
            loadRunDetail(row.getAttribute('data-run-id'));
        });
    });
})();
