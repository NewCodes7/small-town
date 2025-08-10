// UserInfo 관리
let currentUser = null;

async function loadUserInfo() {
    try {
        const response = await fetch('/api/user-info', {
            credentials: 'same-origin'
        });
        if (response.ok) {
            currentUser = await response.json();

            if (!currentUser.isAdmin) {
                return;
            } 

            document.querySelectorAll('.admin-delete-btn').forEach(btn => {
                btn.classList.remove('d-none');
                btn.addEventListener('click', e => {
                    e.preventDefault();
                    deleteArticle(e.target.closest('.admin-delete-btn').getAttribute('data-article-id'));
                })
            })
        }
    } catch (error) {
        console.error('사용자 정보 로드 실패:', error);
    }
}
loadUserInfo();

// 글 삭제 함수
async function deleteArticle(articleId) {
    if (!currentUser || !currentUser.isAdmin) {
        alert('관리자 권한이 필요합니다.');
        return;
    }
    
    if (!confirm('정말로 이 글을 삭제하시겠습니까?')) {
        return;
    }
    
    try {
        const response = await fetch(`/api/admin/articles/${articleId}`, {
            method: 'DELETE',
            credentials: 'same-origin'
        });
        
        const data = await response.json();
        
        if (response.ok) {
            alert('글이 성공적으로 삭제되었습니다.');
            window.location.reload();
        } else {
            alert(data.message || '삭제 중 오류가 발생했습니다.');
        }
    } catch (error) {
        console.error('삭제 요청 중 오류:', error);
        alert('삭제 요청 중 오류가 발생했습니다.');
    }
}