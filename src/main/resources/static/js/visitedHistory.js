/**
 * 방문 기록 관리 유틸리티
 * localStorage를 사용하여 사용자가 클릭한 블로그/비디오 기록을 저장하고 관리합니다.
 */

const VISITED_ARTICLES_KEY = 'visitedArticles';
const VISITED_VIDEOS_KEY = 'visitedVideos';
const VISITED_CARD_CLASS = 'visited-card';

/**
 * localStorage에서 방문 기록을 가져옵니다.
 * @param {string} key - localStorage 키 (VISITED_ARTICLES_KEY 또는 VISITED_VIDEOS_KEY)
 * @returns {Array<number>} 방문한 아이템 ID 배열
 */
function getVisitedItems(key) {
    try {
        const stored = localStorage.getItem(key);
        if (stored) {
            return JSON.parse(stored);
        }
    } catch (error) {
        console.error('방문 기록을 가져오는 중 오류 발생:', error);
    }
    return [];
}

/**
 * localStorage에 방문 기록을 저장합니다.
 * @param {string} key - localStorage 키
 * @param {Array<number>} items - 저장할 아이템 ID 배열
 */
function setVisitedItems(key, items) {
    try {
        localStorage.setItem(key, JSON.stringify(items));
    } catch (error) {
        console.error('방문 기록을 저장하는 중 오류 발생:', error);
    }
}

/**
 * 아이템을 방문 기록에 추가합니다.
 * @param {string} key - localStorage 키
 * @param {number} itemId - 추가할 아이템 ID
 */
function addVisitedItem(key, itemId) {
    const visited = getVisitedItems(key);

    // 이미 방문 기록에 있으면 추가하지 않음
    if (!visited.includes(itemId)) {
        visited.push(itemId);
        setVisitedItems(key, visited);
    }
}

/**
 * 블로그/비디오 카드에 방문 표시를 적용합니다.
 */
function applyVisitedStyles() {
    // 블로그 카드 방문 표시
    const visitedArticles = getVisitedItems(VISITED_ARTICLES_KEY);
    document.querySelectorAll('.article-card').forEach(card => {
        const articleId = parseInt(card.getAttribute('data-article-id'));
        if (visitedArticles.includes(articleId)) {
            card.classList.add(VISITED_CARD_CLASS);
        }
    });

    // 그룹 뷰의 child 항목 방문 표시 (블로그)
    document.querySelectorAll('.child').forEach(child => {
        const articleId = parseInt(child.getAttribute('data-article-id'));
        if (articleId && visitedArticles.includes(articleId)) {
            child.classList.add(VISITED_CARD_CLASS);
        }
    });

    // 비디오 카드 방문 표시
    const visitedVideos = getVisitedItems(VISITED_VIDEOS_KEY);
    document.querySelectorAll('.video-card').forEach(card => {
        const videoId = parseInt(card.getAttribute('data-article-id'));
        if (visitedVideos.includes(videoId)) {
            card.classList.add(VISITED_CARD_CLASS);
        }
    });

    // 그룹 뷰의 child 항목 방문 표시 (비디오)
    document.querySelectorAll('.child').forEach(child => {
        const videoId = parseInt(child.getAttribute('data-article-id'));
        if (videoId && visitedVideos.includes(videoId)) {
            child.classList.add(VISITED_CARD_CLASS);
        }
    });
}

/**
 * 블로그/비디오 카드 클릭 이벤트를 바인딩합니다.
 */
function bindArticleCardClicks() {
    // 리스트 뷰의 블로그 카드
    document.querySelectorAll('.article-card').forEach(card => {
        card.addEventListener('click', function(e) {
            // child 영역을 클릭한 경우는 제외 (child 자체적으로 처리)
            if (e.target.closest('.child')) {
                return;
            }

            // 관리자 버튼이나 회사 링크 클릭은 제외
            if (e.target.closest('.admin-control-panel') ||
                e.target.closest('.admin-delete-btn') ||
                e.target.closest('.admin-edit-btn') ||
                e.target.closest('.admin-summary-btn') ||
                e.target.closest('.company-link')) {
                return;
            }

            const articleId = parseInt(card.getAttribute('data-article-id'));
            if (articleId) {
                addVisitedItem(VISITED_ARTICLES_KEY, articleId);
                card.classList.add(VISITED_CARD_CLASS);
            }
        });
    });

    // 리스트 뷰의 비디오 카드
    document.querySelectorAll('.video-card').forEach(card => {
        card.addEventListener('click', function(e) {
            // child 영역을 클릭한 경우는 제외 (child 자체적으로 처리)
            if (e.target.closest('.child')) {
                return;
            }

            // 관리자 버튼이나 회사 링크 클릭은 제외
            if (e.target.closest('.admin-control-panel') ||
                e.target.closest('.admin-delete-btn') ||
                e.target.closest('.admin-edit-btn') ||
                e.target.closest('.admin-summary-btn') ||
                e.target.closest('.company-link')) {
                return;
            }

            const videoId = parseInt(card.getAttribute('data-article-id'));
            if (videoId) {
                addVisitedItem(VISITED_VIDEOS_KEY, videoId);
                card.classList.add(VISITED_CARD_CLASS);
            }
        });
    });

    // 그룹 뷰의 child 항목들 (블로그 & 비디오)
    document.querySelectorAll('.child').forEach(child => {
        // "더보기" child는 제외
        if (child.classList.contains('more-corporation-articles')) {
            return;
        }

        child.addEventListener('click', function(e) {
            // stopPropagation 제거 - 링크 클릭 등 다른 이벤트가 정상 작동하도록 함
            const articleId = parseInt(child.getAttribute('data-article-id'));
            if (articleId) {
                // 페이지 URL로 비디오인지 블로그인지 판단
                const isVideoPage = window.location.pathname.includes('/video');
                const key = isVideoPage ? VISITED_VIDEOS_KEY : VISITED_ARTICLES_KEY;

                addVisitedItem(key, articleId);
                child.classList.add(VISITED_CARD_CLASS);
            }
        });
    });
}

/**
 * 비디오 모달 열기 시 방문 기록에 추가합니다.
 * 기존 openVideoModal 함수를 감싸서 방문 기록 기능을 추가합니다.
 */
function wrapOpenVideoModal() {
    // openVideoModal 함수가 정의될 때까지 대기
    const checkAndWrap = () => {
        if (typeof window.openVideoModal === 'function') {
            const originalOpenVideoModal = window.openVideoModal;

            window.openVideoModal = function(element) {
                const articleId = parseInt(element.getAttribute('data-article-id'));

                // 방문 기록에 즉시 추가
                if (articleId) {
                    addVisitedItem(VISITED_VIDEOS_KEY, articleId);

                    // 해당 카드에 즉시 방문 표시
                    const card = document.querySelector(`.video-card[data-article-id="${articleId}"]`);
                    if (card) {
                        card.classList.add(VISITED_CARD_CLASS);
                    }

                    // child 요소에도 즉시 방문 표시
                    const child = document.querySelector(`.child[data-article-id="${articleId}"]`);
                    if (child) {
                        child.classList.add(VISITED_CARD_CLASS);
                    }
                }

                // 원래 함수 실행
                originalOpenVideoModal.call(this, element);
            };
        }
    };

    // 즉시 실행
    checkAndWrap();

    // DOMContentLoaded 이후에도 한 번 더 실행 (다른 스크립트가 나중에 로드될 경우 대비)
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', checkAndWrap);
    }
}

/**
 * 페이지 로드 시 방문 기록 기능을 초기화합니다.
 */
function initVisitedHistory() {
    // 페이지 로드 시 방문한 카드에 스타일 적용
    applyVisitedStyles();

    // 블로그/비디오 카드 클릭 이벤트 바인딩
    bindArticleCardClicks();

    // 비디오 모달 함수 래핑 (video.html이 있는 경우)
    wrapOpenVideoModal();
}

// 페이지 로드 완료 시 초기화
// DOMContentLoaded를 사용하여 다른 스크립트들이 로드된 후에 실행
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function() {
        // 약간의 지연을 주어 다른 이벤트 핸들러들이 먼저 바인딩되도록 함
        setTimeout(initVisitedHistory, 100);
    });
} else {
    // 이미 로드된 경우 즉시 실행
    setTimeout(initVisitedHistory, 100);
}
