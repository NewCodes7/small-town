// ===== 비디오 관련 이벤트 핸들러 =====
let currentVideoId = null;

// 비디오 편집 버튼 클릭
document.addEventListener('click', function(e) {
    if (e.target.classList.contains('edit-video-btn') || e.target.closest('.edit-video-btn')) {
        const btn = e.target.classList.contains('edit-video-btn') ? e.target : e.target.closest('.edit-video-btn');
        currentVideoId = btn.dataset.videoId;
        alert('비디오 편집 기능은 개발 중입니다.');
    }

    if (e.target.classList.contains('delete-video-btn') || e.target.closest('.delete-video-btn')) {
        const btn = e.target.classList.contains('delete-video-btn') ? e.target : e.target.closest('.delete-video-btn');
        const videoId = btn.dataset.videoId;
        const videoTitle = btn.dataset.videoTitle;

        if (confirm(`정말로 "${videoTitle}" 영상을 삭제하시겠습니까?\n\n삭제된 영상은 복구할 수 없습니다.`)) {
            deleteVideo(videoId);
        }
    }

    if (e.target.classList.contains('edit-video-translated-title-btn') || e.target.closest('.edit-video-translated-title-btn')) {
        const btn = e.target.classList.contains('edit-video-translated-title-btn') ? e.target : e.target.closest('.edit-video-translated-title-btn');
        currentVideoId = btn.dataset.videoId;
        const currentTranslatedTitle = btn.dataset.currentTranslatedTitle;

        const newTitle = prompt('번역된 제목을 입력하세요:', currentTranslatedTitle || '');

        if (newTitle !== null) {
            updateVideoTranslatedTitle(currentVideoId, newTitle);
        }
    }

    if (e.target.classList.contains('video-category-badge')) {
        currentVideoId = e.target.dataset.videoId;
        const currentCategory = e.target.textContent.trim();

        const newCategory = prompt('카테고리를 입력하세요:', currentCategory === '미분류' ? '' : currentCategory);

        if (newCategory !== null && newCategory.trim()) {
            updateVideoCategory(currentVideoId, newCategory.trim());
        }
    }
});

// 비디오 삭제 함수
function deleteVideo(videoId) {
    fetch(`/video/api/admin/videos/${videoId}`, {
        method: 'DELETE',
        headers: {
            'Content-Type': 'application/json',
        }
    })
    .then(response => response.json())
    .then(data => {
        if (data.status === 'success') {
            alert(data.message);
            location.reload();
        } else {
            alert('영상 삭제 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('Error:', error);
        alert('영상 삭제 중 오류가 발생했습니다.');
    });
}

// 비디오 번역된 제목 수정
function updateVideoTranslatedTitle(videoId, translatedTitle) {
    fetch(`/admin/videos/${videoId}/translated-title`, {
        method: 'PUT',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({
            translatedTitle: translatedTitle
        })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert(data.message);
            location.reload();
        } else {
            alert('번역된 제목 수정 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('Error:', error);
        alert('번역된 제목 수정 중 오류가 발생했습니다.');
    });
}

// 비디오 카테고리 수정
function updateVideoCategory(videoId, categoryName) {
    fetch(`/admin/videos/${videoId}/category`, {
        method: 'PUT',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({
            categoryName: categoryName
        })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert(data.message);
            location.reload();
        } else {
            alert('카테고리 수정 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('Error:', error);
        alert('카테고리 수정 중 오류가 발생했습니다.');
    });
}

