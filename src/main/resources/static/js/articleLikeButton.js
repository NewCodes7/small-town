// 좋아요 버튼 클릭 이벤트
function likeButton() {
    document.querySelectorAll('.like-button').forEach(btn => {
        btn.addEventListener('click', async function(e) {
            e.preventDefault();
            e.stopPropagation();

            const articleId = this.getAttribute('data-article-id');

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
                        this.classList.add('liked');
                        addToLikedArticles(articleId);
                    } else {
                        this.classList.remove('liked');
                        removeFromLikedArticles(articleId);
                    }
                }
            } catch (error) {
                console.error('좋아요 처리 중 오류 발생:', error);
            }
        });
    });
}


// 페이지 로드 시 좋아요 상태 확인
async function loadLikeStatuses() {
    const likeButtons = document.querySelectorAll('.like-button');

    // 사용자 인증 상태 확인
    let userInfo = null;
    try {
        const response = await fetch('/api/user-info', { credentials: 'include' });
        if (response.ok) {
            userInfo = await response.json();
        }
    } catch (error) {
        console.error('사용자 정보 로드 중 오류 발생:', error);
    }

    for (const btn of likeButtons) {
        const articleId = parseInt(btn.getAttribute('data-article-id'));

        try {
            if (userInfo && userInfo.authenticated) {
                // 로그인 사용자: 서버에서 조회
                const response = await fetch(`/api/articles/${articleId}/like-status`, {
                    credentials: 'same-origin'
                });
                if (response.ok) {
                    const data = await response.json();

                    if (data.hasLiked) {
                        btn.classList.add('liked');
                    }
                }
            } else {
                // 비로그인 사용자: localStorage에서 조회
                const likedIds = getLikedArticleIds();
                if (likedIds.includes(articleId)) {
                    btn.classList.add('liked');
                }
            }
        } catch (error) {
            console.error('좋아요 상태 로드 중 오류 발생:', error);
        }
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