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


// 페이지 로드 시 좋아요 상태 확인 (서버에서 제공한 data-liked 속성 사용)
function loadLikeStatuses() {
    const likeButtons = document.querySelectorAll('.like-button');

    likeButtons.forEach(btn => {
        const isLiked = btn.getAttribute('data-liked') === 'true';

        if (isLiked) {
            btn.classList.add('liked');
        }
    });
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