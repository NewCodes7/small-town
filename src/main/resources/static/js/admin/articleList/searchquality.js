/**
 * Search Quality 탭 관련 기능
 */

// 전역 변수
let searchTrendChart = null;

document.addEventListener('DOMContentLoaded', function() {
    // searchquality 탭일 때만 초기화
    const urlParams = new URLSearchParams(window.location.search);
    const currentTab = urlParams.get('tab');
    
    if (currentTab === 'searchquality') {
        initSearchQuality();
    }
});

function initSearchQuality() {
    console.log('Search Quality 탭 초기화');
    
    // 차트 초기화
    initSearchTrendChart();
    
    // 테이블 데이터 로드
    loadTopKeywords(7);
    loadZeroResultKeywords(7);
    
    // 필터 변경 이벤트 리스너
    document.getElementById('trendDaysFilter')?.addEventListener('change', function() {
        updateSearchTrendChart(parseInt(this.value));
    });
    
    document.getElementById('keywordsDaysFilter')?.addEventListener('change', function() {
        loadTopKeywords(parseInt(this.value));
    });
    
    document.getElementById('zeroResultDaysFilter')?.addEventListener('change', function() {
        loadZeroResultKeywords(parseInt(this.value));
    });
}

function initSearchTrendChart() {
    const canvas = document.getElementById('searchTrendChart');
    if (!canvas) return;
    
    const ctx = canvas.getContext('2d');
    
    searchTrendChart = new Chart(ctx, {
        type: 'line',
        data: {
            labels: [],
            datasets: [{
                label: '검색 수',
                data: [],
                borderColor: 'rgb(75, 192, 192)',
                backgroundColor: 'rgba(75, 192, 192, 0.2)',
                tension: 0.1
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {
                    display: true,
                    position: 'top'
                },
                tooltip: {
                    mode: 'index',
                    intersect: false
                }
            },
            scales: {
                y: {
                    beginAtZero: true,
                    ticks: {
                        stepSize: 1
                    }
                }
            }
        }
    });
    
    // 초기 데이터 로드 (30일)
    updateSearchTrendChart(30);
}

function updateSearchTrendChart(days) {
    fetch(`/admin/api/search-quality/daily-trend?days=${days}`)
        .then(response => response.json())
        .then(data => {
            const labels = data.map(item => item.date);
            const counts = data.map(item => item.count);
            
            searchTrendChart.data.labels = labels;
            searchTrendChart.data.datasets[0].data = counts;
            searchTrendChart.update();
        })
        .catch(error => {
            console.error('일별 검색 추이 로드 실패:', error);
        });
}

function loadTopKeywords(days) {
    const tbody = document.querySelector('#topKeywordsTable tbody');
    if (!tbody) return;
    
    tbody.innerHTML = '<tr><td colspan="5" class="text-center text-muted">데이터를 불러오는 중...</td></tr>';
    
    fetch(`/admin/api/search-quality/top-keywords?days=${days}&limit=20`)
        .then(response => response.json())
        .then(data => {
            if (data.length === 0) {
                tbody.innerHTML = '<tr><td colspan="5" class="text-center text-muted">검색 데이터가 없습니다.</td></tr>';
                return;
            }
            
            tbody.innerHTML = data.map((item, index) => `
                <tr>
                    <td>${index + 1}</td>
                    <td><strong>${escapeHtml(item.keyword)}</strong></td>
                    <td>${item.searchCount}</td>
                    <td>${item.avgResultCount}</td>
                    <td>${item.clickThroughRate}%</td>
                </tr>
            `).join('');
        })
        .catch(error => {
            console.error('인기 검색어 로드 실패:', error);
            tbody.innerHTML = '<tr><td colspan="5" class="text-center text-danger">데이터 로드 실패</td></tr>';
        });
}

function loadZeroResultKeywords(days) {
    const tbody = document.querySelector('#zeroResultTable tbody');
    if (!tbody) return;
    
    tbody.innerHTML = '<tr><td colspan="3" class="text-center text-muted">데이터를 불러오는 중...</td></tr>';
    
    fetch(`/admin/api/search-quality/zero-result-keywords?days=${days}&limit=20`)
        .then(response => response.json())
        .then(data => {
            if (data.length === 0) {
                tbody.innerHTML = '<tr><td colspan="3" class="text-center text-muted">결과 없는 검색어가 없습니다.</td></tr>';
                return;
            }
            
            tbody.innerHTML = data.map((item, index) => `
                <tr>
                    <td>${index + 1}</td>
                    <td><strong class="text-danger">${escapeHtml(item.keyword)}</strong></td>
                    <td>${item.count}</td>
                </tr>
            `).join('');
        })
        .catch(error => {
            console.error('결과 없는 검색어 로드 실패:', error);
            tbody.innerHTML = '<tr><td colspan="3" class="text-center text-danger">데이터 로드 실패</td></tr>';
        });
}

function escapeHtml(text) {
    const map = {
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#039;'
    };
    return text.replace(/[&<>"']/g, m => map[m]);
}
