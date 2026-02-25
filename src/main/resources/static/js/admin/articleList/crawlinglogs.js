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
    const errorWrapEl = document.getElementById('crawlingRunErrorWrap');
    const errorMessageEl = document.getElementById('crawlingRunErrorMessage');
    const failedOnlyToggle = document.getElementById('crawlingFailedOnlyToggle');
    const errorSummaryWrapEl = document.getElementById('crawlingRunErrorSummaryWrap');
    const errorSummaryListEl = document.getElementById('crawlingRunErrorSummaryList');
    const loadMoreBtn = document.getElementById('crawlingLogsLoadMoreBtn');

    let currentRunId = null;
    let currentRunStatus = null;
    let currentPage = 0;
    let totalPages = 0;
    let isLoading = false;

    const statusClassMap = {
        SUCCESS: 'bg-success',
        WARNING: 'bg-warning text-dark',
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

    function shouldDefaultFailedOnly(status) {
        return status === 'PARTIAL_SUCCESS' || status === 'FAILURE';
    }

    function resetSummary() {
        if (errorSummaryListEl) {
            errorSummaryListEl.innerHTML = '';
        }
        if (errorSummaryWrapEl) {
            errorSummaryWrapEl.classList.add('d-none');
        }
    }

    function renderErrorSummary(summary) {
        if (!errorSummaryListEl || !errorSummaryWrapEl) {
            return;
        }
        errorSummaryListEl.innerHTML = '';
        if (!summary || summary.length === 0) {
            errorSummaryWrapEl.classList.add('d-none');
            return;
        }
        summary.forEach(item => {
            const li = document.createElement('li');
            li.textContent = `${item.message} (${item.count})`;
            errorSummaryListEl.appendChild(li);
        });
        errorSummaryWrapEl.classList.remove('d-none');
    }

    function setLoadMoreVisibility() {
        if (!loadMoreBtn) {
            return;
        }
        if (currentPage + 1 < totalPages) {
            loadMoreBtn.classList.remove('d-none');
        } else {
            loadMoreBtn.classList.add('d-none');
        }
    }

    async function loadLogs(runId, failedOnly, page, append) {
        if (isLoading) {
            return;
        }
        isLoading = true;
        const logsRes = await fetch(`/api/admin/crawling/runs/${runId}/logs?page=${page}&size=20&failedOnly=${failedOnly}`);
        if (!logsRes.ok) {
            isLoading = false;
            throw new Error('로그 정보를 불러오지 못했습니다.');
        }
        const logsData = await logsRes.json();
        currentPage = logsData.page || 0;
        totalPages = logsData.totalPages || 0;
        setLoadMoreVisibility();

        if (page === 0) {
            renderErrorSummary(logsData.errorSummary);
        }

        if (!logsData.logs || logsData.logs.length === 0) {
            const message = failedOnly ? '실패한 상세 로그가 없습니다.' : '상세 로그가 없습니다.';
            if (!append) {
                tableBody.innerHTML = `<tr><td colspan="6" class="text-center text-muted py-4">${message}</td></tr>`;
            }
            isLoading = false;
            return;
        }

        if (!append) {
            tableBody.innerHTML = '';
        }
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
        isLoading = false;
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
            const runRes = await fetch(`/api/admin/crawling/runs/${runId}`);
            if (!runRes.ok) {
                throw new Error('로그 정보를 불러오지 못했습니다.');
            }
            const runData = await runRes.json();
            currentRunId = runId;
            currentRunStatus = runData.status;
            currentPage = 0;
            totalPages = 0;
            resetSummary();
            if (loadMoreBtn) {
                loadMoreBtn.classList.add('d-none');
            }

            detailTitleEl.textContent = `#${runData.id} (${runData.jobType})`;
            const errorText = runData.errorMessage ? ` | 오류: ${runData.errorMessage}` : '';
            summaryEl.textContent = `상태: ${runData.status} | 성공: ${runData.successCount} | 실패: ${runData.failureCount} | 신규: ${runData.newItemsCount}${errorText}`;

            if (failedOnlyToggle) {
                failedOnlyToggle.checked = shouldDefaultFailedOnly(runData.status);
                failedOnlyToggle.disabled = false;
            }

            if (runData.errorMessage) {
                errorMessageEl.textContent = runData.errorMessage;
                errorWrapEl.classList.remove('d-none');
            } else {
                errorMessageEl.textContent = '';
                errorWrapEl.classList.add('d-none');
            }

            const failedOnly = failedOnlyToggle ? failedOnlyToggle.checked : false;
            await loadLogs(runId, failedOnly, 0, false);

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

    if (failedOnlyToggle) {
        failedOnlyToggle.addEventListener('change', () => {
            if (!currentRunId) {
                return;
            }
            tableBody.innerHTML = '<tr><td colspan="6" class="text-center text-muted py-4">불러오는 중...</td></tr>';
            currentPage = 0;
            totalPages = 0;
            resetSummary();
            loadLogs(currentRunId, failedOnlyToggle.checked, 0, false).catch(error => {
                summaryEl.textContent = '로그 정보를 불러오지 못했습니다.';
                tableBody.innerHTML = '<tr><td colspan="6" class="text-center text-danger py-4">오류가 발생했습니다.</td></tr>';
                console.error(error);
            });
        });
    }

    if (loadMoreBtn) {
        loadMoreBtn.addEventListener('click', () => {
            if (!currentRunId) {
                return;
            }
            if (currentPage + 1 >= totalPages) {
                return;
            }
            loadMoreBtn.disabled = true;
            const failedOnly = failedOnlyToggle ? failedOnlyToggle.checked : false;
            loadLogs(currentRunId, failedOnly, currentPage + 1, true)
                .finally(() => {
                    loadMoreBtn.disabled = false;
                });
        });
    }
})();
