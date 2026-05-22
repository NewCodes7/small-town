// 인증 상태 캐시 (페이지 내 재사용)
let _likeButtonAuthState = null;
async function _getLikeButtonAuthState() {
    if (_likeButtonAuthState !== null) return _likeButtonAuthState;
    try {
        const res = await fetch('/api/user-info', { credentials: 'include' });
        const data = await res.json();
        _likeButtonAuthState = data.authenticated || false;
    } catch { _likeButtonAuthState = false; }
    return _likeButtonAuthState;
}

// 비디오 localStorage 유틸 (new-home.js가 없는 페이지용)
const _LIKED_VIDEOS_KEY = 'likedVideos';
function _getLikedVideoIds() {
    if (typeof getLikedVideoIds === 'function') return getLikedVideoIds();
    try { return JSON.parse(localStorage.getItem(_LIKED_VIDEOS_KEY)) || []; } catch { return []; }
}
function _addToLikedVideos(videoId) {
    if (typeof addToLikedVideos === 'function') { addToLikedVideos(videoId); return; }
    const ids = _getLikedVideoIds();
    if (!ids.includes(videoId)) { ids.push(videoId); localStorage.setItem(_LIKED_VIDEOS_KEY, JSON.stringify(ids)); }
}
function _removeFromLikedVideos(videoId) {
    if (typeof removeFromLikedVideos === 'function') { removeFromLikedVideos(videoId); return; }
    const ids = _getLikedVideoIds().filter(id => id !== videoId);
    localStorage.setItem(_LIKED_VIDEOS_KEY, JSON.stringify(ids));
}

// 좋아요 버튼 클릭 이벤트 (이벤트 위임으로 CSR 렌더링된 요소도 처리)
function likeButton() {
    document.addEventListener('click', async function(e) {
        const btn = e.target.closest('.like-button');
        if (!btn) return;

        e.preventDefault();
        e.stopPropagation();

        const articleId = btn.getAttribute('data-article-id');
        const videoId = btn.getAttribute('data-video-id');
        if (!articleId && !videoId) return;

        const authenticated = await _getLikeButtonAuthState();

        if (!authenticated) {
            const isCurrentlyLiked = btn.classList.contains('liked');
            if (isCurrentlyLiked) {
                btn.classList.remove('liked');
                if (articleId && typeof removeFromLikedArticles === 'function') removeFromLikedArticles(parseInt(articleId));
                if (videoId) _removeFromLikedVideos(parseInt(videoId));
            } else {
                btn.classList.add('liked');
                if (articleId && typeof addToLikedArticles === 'function') addToLikedArticles(parseInt(articleId));
                if (videoId) _addToLikedVideos(parseInt(videoId));
                if (typeof showLikeLoginModal === 'function') showLikeLoginModal();
            }
            return;
        }

        if (articleId) {
            try {
                const response = await fetch(`/api/articles/${articleId}/like`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    credentials: 'same-origin'
                });
                if (response.ok) {
                    const data = await response.json();
                    if (data.isLiked) {
                        btn.classList.add('liked');
                        if (typeof addToLikedArticles === 'function') addToLikedArticles(articleId);
                    } else {
                        btn.classList.remove('liked');
                        if (typeof removeFromLikedArticles === 'function') removeFromLikedArticles(articleId);
                    }
                }
            } catch (error) {
                console.error('좋아요 처리 중 오류 발생:', error);
            }
        } else if (videoId) {
            try {
                const response = await fetch(`/video/api/videos/${videoId}/like`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    credentials: 'same-origin'
                });
                if (response.ok) {
                    const data = await response.json();
                    if (data.isLiked) {
                        btn.classList.add('liked');
                        _addToLikedVideos(parseInt(videoId));
                    } else {
                        btn.classList.remove('liked');
                        _removeFromLikedVideos(parseInt(videoId));
                    }
                }
            } catch (error) {
                console.error('비디오 좋아요 처리 중 오류 발생:', error);
            }
        }
    });
}


// 페이지 로드 시 좋아요 상태 확인
async function loadLikeStatuses() {
    const authenticated = await _getLikeButtonAuthState();

    // 아티클 좋아요 상태 로드
    const articleButtons = document.querySelectorAll('.like-button[data-article-id]');
    const articleIds = Array.from(articleButtons).map(btn => parseInt(btn.getAttribute('data-article-id')));

    if (articleIds.length > 0) {
        if (!authenticated) {
            const likedIds = typeof getLikedArticleIds === 'function' ? getLikedArticleIds() : [];
            articleButtons.forEach(btn => {
                if (likedIds.includes(parseInt(btn.getAttribute('data-article-id')))) btn.classList.add('liked');
            });
        } else {
            try {
                const response = await fetch('/api/articles/like-status/batch', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    credentials: 'include',
                    body: JSON.stringify({ articleIds })
                });
                if (response.ok) {
                    const data = await response.json();
                    articleButtons.forEach(btn => {
                        const articleId = parseInt(btn.getAttribute('data-article-id'));
                        if (data.likeStatus[articleId]) btn.classList.add('liked');
                    });
                }
            } catch (error) {
                console.error('아티클 좋아요 상태 로드 실패:', error);
            }
        }
    }

    // 비디오 좋아요 상태 로드
    const videoButtons = document.querySelectorAll('.like-button[data-video-id]');
    if (videoButtons.length > 0) {
        if (!authenticated) {
            const likedIds = _getLikedVideoIds();
            videoButtons.forEach(btn => {
                if (likedIds.includes(parseInt(btn.getAttribute('data-video-id')))) btn.classList.add('liked');
            });
        } else {
            const videoIds = Array.from(videoButtons).map(btn => parseInt(btn.getAttribute('data-video-id')));
            try {
                const response = await fetch('/video/api/videos/like-status/batch', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    credentials: 'include',
                    body: JSON.stringify({ videoIds })
                });
                if (response.ok) {
                    const data = await response.json();
                    videoButtons.forEach(btn => {
                        const videoId = parseInt(btn.getAttribute('data-video-id'));
                        if (data.likeStatus[videoId]) btn.classList.add('liked');
                    });
                }
            } catch (error) {
                console.error('비디오 좋아요 상태 로드 실패:', error);
            }
        }
    }
}

document.addEventListener('DOMContentLoaded', function() {
    likeButton();
    loadLikeStatuses();
});

// bfcache(back-forward cache)로 인해 페이지가 복원될 때도 좋아요 상태 업데이트
window.addEventListener('pageshow', function(event) {
    if (event.persisted) {
        loadLikeStatuses();
    }
});
