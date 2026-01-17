/**
 * Theme View Count - 테마 상세 페이지 조회수 증가
 * 페이지 로드 시 자동으로 조회수 +1
 */

(function() {
    'use strict';

    // 테마 조회수 증가 API 호출
    function incrementThemeViewCount(themeId) {
        if (!themeId) {
            console.warn('themeId가 없습니다.');
            return;
        }

        fetch(`/api/themes/${themeId}/view`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            credentials: 'same-origin',
            redirect: 'manual'
        })
        .then(response => {
            if (response.ok) {
                return response.json();
            }
            throw new Error('조회수 증가 실패');
        })
        .then(data => {
            if (data && data.incremented) {
                console.log(`테마 ${themeId} 조회수 증가: ${data.viewCount}`);
            }
        })
        .catch(error => {
            console.log('테마 조회수 증가 요청 실패:', error);
        });
    }

    // 페이지 로드 시 조회수 증가
    document.addEventListener('DOMContentLoaded', function() {
        // data-theme-id 속성에서 테마 ID 가져오기
        const themeContainer = document.querySelector('[data-theme-id]');
        if (themeContainer) {
            const themeId = themeContainer.getAttribute('data-theme-id');
            incrementThemeViewCount(themeId);
        }
    });
})();
