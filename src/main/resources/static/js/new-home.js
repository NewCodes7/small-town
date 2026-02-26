// 이번 주 인기글 캐러셀
let popularCurrentPage = 0;

function getPopularItemsPerPage() {
    const width = window.innerWidth;
    if (width <= 768) return 2;
    if (width <= 1024) return 3;
    return 4;
}

function slidePopular(direction) {
    const track = document.querySelector('.popular-track');
    if (!track) return;

    const totalItems = track.children.length;
    const itemsPerPage = getPopularItemsPerPage();
    const maxPage = Math.max(0, Math.ceil(totalItems / itemsPerPage) - 1);

    popularCurrentPage += direction;
    if (popularCurrentPage < 0) popularCurrentPage = 0;
    if (popularCurrentPage > maxPage) popularCurrentPage = maxPage;

    const cardWithGap = track.children[0].offsetWidth + 20; // card width + gap
    track.style.transform = `translateX(-${popularCurrentPage * itemsPerPage * cardWithGap}px)`;

    // Update button states
    const prevBtn = document.querySelector('.popular-prev');
    const nextBtn = document.querySelector('.popular-next');
    if (prevBtn) prevBtn.disabled = (popularCurrentPage === 0);
    if (nextBtn) nextBtn.disabled = (popularCurrentPage >= maxPage);
}

// Reset carousel on window resize
window.addEventListener('resize', function() {
    popularCurrentPage = 0;
    const track = document.querySelector('.popular-track');
    if (track) {
        track.style.transform = 'translateX(0)';
    }
    slidePopular(0); // Update button states
});

// Initialize button states on load
document.addEventListener('DOMContentLoaded', function() {
    slidePopular(0);
});

// 아티클 상세 페이지로 이동 (조회수 증가는 상세 페이지에서 처리)
function goToArticleDetail(articleId) {
    if (articleId) {
        window.location.href = `/articles/${articleId}`;
    }
}

// 아티클 링크 클릭 시 조회수 증가 후 외부 링크 열기 (레거시 호환용)
function openArticleLink(element) {
    const articleId = element.getAttribute('data-article-id');
    const articleLink = element.getAttribute('data-article-link');

    // 조회수 증가 API 호출 (비동기, 결과 대기 안 함)
    if (articleId) {
        fetch(`/api/articles/${articleId}/view`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            credentials: 'same-origin',
            redirect: 'manual'
        }).catch(error => {
            console.log('조회수 증가 요청 실패:', error);
        });
    }

    // 외부 링크 열기
    if (articleLink) {
        window.open(articleLink, '_blank');
    }
}

// Open video modal
function openVideoModal(element) {
    const videoId = element.getAttribute('data-video-id');
    const videoTitle = element.getAttribute('data-video-title');
    const articleId = element.getAttribute('data-article-id');
    const modal = document.getElementById('videoModal');
    const iframe = document.getElementById('modalVideoPlayer');

    // Increment view count
    if (articleId) {
        fetch(`/video/api/videos/${articleId}/view`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            credentials: 'same-origin',
            redirect: 'manual'
        }).catch(error => {
            console.log('조회수 증가 요청 실패:', error);
        });
    }

    // Set iframe src with autoplay
    iframe.src = `https://www.youtube.com/embed/${videoId}?controls=1&modestbranding=1&rel=0&autoplay=1`;
    iframe.title = videoTitle;

    // Show modal
    modal.classList.add('active');
    document.body.style.overflow = 'hidden'; // Prevent background scrolling
}

// Close video modal
function closeVideoModal(event) {
    const modal = document.getElementById('videoModal');
    const iframe = document.getElementById('modalVideoPlayer');

    // Only close if clicking on modal background or close button
    if (event.target === modal || event.target.classList.contains('video-modal-close')) {
        modal.classList.remove('active');
        iframe.src = ''; // Stop video playback
        document.body.style.overflow = ''; // Restore scrolling
    }
}

// Close modal with Escape key
document.addEventListener('keydown', function(event) {
    if (event.key === 'Escape') {
        const modal = document.getElementById('videoModal');
        if (modal.classList.contains('active')) {
            closeVideoModal({ target: modal });
        }
    }
});

// 블로그 좋아요 토글
async function toggleArticleLike(button, event) {
    event.preventDefault();
    event.stopPropagation();

    const articleId = button.getAttribute('data-article-id');
    const icon = button.querySelector('.like-icon');
    const countSpan = button.querySelector('.like-count');

    try {
        const response = await fetch(`/api/articles/${articleId}/like`, {
            method: 'POST',
            credentials: 'include'
        });

        if (response.ok) {
            const data = await response.json();

            // Update UI
            countSpan.textContent = data.likeCount;

            if (data.isLiked) {
                button.classList.add('liked');
                icon.classList.remove('far');
                icon.classList.add('fas');
            } else {
                button.classList.remove('liked');
                icon.classList.remove('fas');
                icon.classList.add('far');
            }
        } else if (response.status === 401) {
            alert('좋아요를 하려면 로그인이 필요합니다.');
        }
    } catch (error) {
        console.error('좋아요 처리 실패:', error);
    }
}

// 비디오 좋아요 토글
async function toggleVideoLike(button, event) {
    event.preventDefault();
    event.stopPropagation();

    const videoId = button.getAttribute('data-video-id');
    const icon = button.querySelector('.like-icon');
    const countSpan = button.querySelector('.like-count');

    try {
        const response = await fetch(`/video/api/videos/${videoId}/like`, {
            method: 'POST',
            credentials: 'include'
        });

        if (response.ok) {
            const data = await response.json();

            // Update UI
            countSpan.textContent = data.likeCount;

            if (data.isLiked) {
                button.classList.add('liked');
                icon.classList.remove('far');
                icon.classList.add('fas');
            } else {
                button.classList.remove('liked');
                icon.classList.remove('fas');
                icon.classList.add('far');
            }
        } else if (response.status === 401) {
            alert('좋아요를 하려면 로그인이 필요합니다.');
        }
    } catch (error) {
        console.error('좋아요 처리 실패:', error);
    }
}

// 좋아요 상태 로드 (배치 API 호출)
async function loadLikeStatus() {
    // Load article like status via batch API
    const articleButtons = document.querySelectorAll('.like-button[data-article-id]');
    const articleIds = Array.from(articleButtons).map(button =>
        parseInt(button.getAttribute('data-article-id'))
    );

    if (articleIds.length > 0) {
        try {
            const response = await fetch('/api/articles/like-status/batch', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                credentials: 'include',
                body: JSON.stringify({ articleIds })
            });

            if (response.ok) {
                const data = await response.json();
                const likeStatus = data.likeStatus;

                articleButtons.forEach(button => {
                    const articleId = parseInt(button.getAttribute('data-article-id'));
                    const isLiked = likeStatus[articleId] || false;
                    const icon = button.querySelector('.like-icon');

                    if (isLiked) {
                        button.classList.add('liked');
                        if (icon) {
                            icon.classList.remove('far');
                            icon.classList.add('fas');
                        }
                    }
                });
            }
        } catch (error) {
            console.error('좋아요 상태 로드 실패:', error);
        }
    }

    // Load video like status via batch API
    const videoButtons = document.querySelectorAll('.like-button[data-video-id]');
    const videoIds = Array.from(videoButtons).map(button =>
        parseInt(button.getAttribute('data-video-id'))
    );

    if (videoIds.length > 0) {
        try {
            const response = await fetch('/video/api/videos/like-status/batch', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                credentials: 'include',
                body: JSON.stringify({ videoIds })
            });

            if (response.ok) {
                const data = await response.json();
                const likeStatus = data.likeStatus;

                videoButtons.forEach(button => {
                    const videoId = parseInt(button.getAttribute('data-video-id'));
                    const isLiked = likeStatus[videoId] || false;
                    const icon = button.querySelector('.like-icon');

                    if (isLiked) {
                        button.classList.add('liked');
                        if (icon) {
                            icon.classList.remove('far');
                            icon.classList.add('fas');
                        }
                    }
                });
            }
        } catch (error) {
            console.error('좋아요 상태 로드 실패:', error);
        }
    }
}

// 상대 시간 계산 함수
function getRelativeTime(dateString) {
    const now = new Date();
    const date = new Date(dateString);
    const diffInSeconds = Math.floor((now - date) / 1000);

    const minute = 60;
    const hour = minute * 60;
    const day = hour * 24;
    const week = day * 7;
    const month = day * 30;
    const year = day * 365;

    if (diffInSeconds < minute) {
        return '방금 전';
    } else if (diffInSeconds < hour) {
        const minutes = Math.floor(diffInSeconds / minute);
        return `${minutes}분 전`;
    } else if (diffInSeconds < day) {
        const hours = Math.floor(diffInSeconds / hour);
        return `${hours}시간 전`;
    } else if (diffInSeconds < week) {
        const days = Math.floor(diffInSeconds / day);
        return `${days}일 전`;
    } else if (diffInSeconds < month) {
        const weeks = Math.floor(diffInSeconds / week);
        return `${weeks}주일 전`;
    } else if (diffInSeconds < year) {
        const months = Math.floor(diffInSeconds / month);
        return `${months}달 전`;
    } else {
        const years = Math.floor(diffInSeconds / year);
        return `${years}년 전`;
    }
}

// 날짜 포맷팅 함수
function formatDate(dateString) {
    const date = new Date(dateString);
    return date.toLocaleDateString('ko-KR', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
    });
}

// 테마 클릭 시 조회수 증가
function incrementThemeViewCount(themeId, element) {
    fetch(`/api/themes/${themeId}/view`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        credentials: 'same-origin',
        redirect: 'manual'
    }).then(response => {
        if (response.ok) {
            return response.json();
        }
    }).then(data => {
        if (data && data.incremented) {
            console.log(`테마 ${themeId} 조회수 증가:`, data.viewCount);
        }
    }).catch(error => {
        console.log('테마 조회수 증가 요청 실패:', error);
    });
}

// 페이지 로드 시 상대 시간 적용 및 좋아요 상태 로드
document.addEventListener('DOMContentLoaded', function() {
    // 상대 시간 표시
    document.querySelectorAll('.relative-time').forEach(element => {
        const dateString = element.getAttribute('data-date');
        if (dateString) {
            // 상대 시간 표시
            element.textContent = getRelativeTime(dateString);
            // 툴팁에 포맷된 날짜 표시
            element.title = formatDate(dateString);
        }
    });

    // 좋아요 상태 로드
    loadLikeStatus();

    // 검색 자동완성 초기화 (searchAutocomplete.js 모듈 사용)
    if (typeof SearchAutocomplete !== 'undefined') {
        const autocomplete = new SearchAutocomplete({
            searchInputId: 'searchInput',
            dropdownId: 'autocompleteDropdown',
            formId: 'searchForm',
            autocompleteApiUrl: '/api/autocomplete',
            searchHistoryKey: 'small_town_search_history',
            supportTheme: true,
            corporationUrlPattern: '/corporations/{id}',
            debounceDelay: 100
        });
        autocomplete.init();
    }

    // 테마 클릭 이벤트 리스너 추가
    document.querySelectorAll('.theme-collection').forEach(element => {
        element.addEventListener('click', function(e) {
            const themeId = this.getAttribute('href').split('/').pop().split('?')[0];
            if (themeId) {
                incrementThemeViewCount(themeId, this);
            }
        });
    });
});

// ===== 검색 자동완성 기능 =====
// searchAutocomplete.js 모듈로 이전됨 (SearchAutocomplete 클래스 사용)
