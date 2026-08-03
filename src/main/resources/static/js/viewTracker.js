/**
 * 아티클 조회수(view count) 증가 공통 모듈.
 *
 * 원문 링크(<a target="_blank">)는 클릭 시 새 탭에서 열리며 현재 페이지는 그대로 남아있으므로,
 * 결과를 기다리지 않는 비동기 POST 한 번으로 조회수를 기록한다. 서버에서 로그인 사용자는 이메일,
 * 익명 사용자는 IP 기준 30분 쿨다운을 적용한다(ArticleEngagementController 참고).
 *
 * 사용법:
 *   recordArticleView(articleId);
 */
(function () {
    'use strict';

    function recordArticleView(articleId) {
        if (!articleId) return;

        fetch(`/api/articles/${articleId}/view`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'same-origin',
            redirect: 'manual'
        }).catch(error => {
            console.log('조회수 증가 요청 실패:', error);
        });
    }

    // 전역 노출 (다른 스크립트/인라인 핸들러에서 사용)
    window.recordArticleView = recordArticleView;
})();
