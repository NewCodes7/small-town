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

// 좋아요 버튼 클릭 이벤트 (이벤트 위임으로 CSR 렌더링된 요소도 처리)
function likeButton() {
    document.addEventListener('click', async function(e) {
        const btn = e.target.closest('.like-button');
        if (!btn) return;

        e.preventDefault();
        e.stopPropagation();

        const articleId = btn.getAttribute('data-article-id');
        const authenticated = await _getLikeButtonAuthState();

        if (!authenticated) {
            const isCurrentlyLiked = btn.classList.contains('liked');
            if (isCurrentlyLiked) {
                btn.classList.remove('liked');
                if (typeof removeFromLikedArticles === 'function') removeFromLikedArticles(parseInt(articleId));
            } else {
                btn.classList.add('liked');
                if (typeof addToLikedArticles === 'function') addToLikedArticles(parseInt(articleId));
                if (typeof showLikeLoginModal === 'function') showLikeLoginModal();
            }
            return;
        }

        try {
            const response = await fetch(`/api/articles/${articleId}/like`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                credentials: 'same-origin'
            });

            if (response.ok) {
                const data = await response.json();

                // 좋아요 상태에 따른 스타일 변경 및 localStorage 동기화
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
    });
}


// 페이지 로드 시 좋아요 상태 확인
async function loadLikeStatuses() {
    const likeButtons = document.querySelectorAll('.like-button[data-article-id]');
    const articleIds = Array.from(likeButtons).map(btn =>
        parseInt(btn.getAttribute('data-article-id'))
    );

    if (articleIds.length === 0) {
        return;
    }

    const authenticated = await _getLikeButtonAuthState();

    if (!authenticated) {
        // 비로그인: localStorage 기반으로 표시 (new-home.js와 동일한 키 사용)
        const likedIds = typeof getLikedArticleIds === 'function' ? getLikedArticleIds() : [];
        likeButtons.forEach(btn => {
            const articleId = parseInt(btn.getAttribute('data-article-id'));
            if (likedIds.includes(articleId)) {
                btn.classList.add('liked');
            }
        });
        return;
    }

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

            likeButtons.forEach(btn => {
                const articleId = parseInt(btn.getAttribute('data-article-id'));
                const isLiked = likeStatus[articleId] || false;

                if (isLiked) {
                    btn.classList.add('liked');
                }
            });
        }
    } catch (error) {
        console.error('좋아요 상태 로드 실패:', error);
    }
}

document.addEventListener('DOMContentLoaded', function() {
    likeButton();
    loadLikeStatuses();
});

// bfcache(back-forward cache)로 인해 페이지가 복원될 때도 좋아요 상태 업데이트
window.addEventListener('pageshow', function(event) {
    // bfcache에서 복원된 경우에만 실행
    if (event.persisted) {
        loadLikeStatuses();
    }
});