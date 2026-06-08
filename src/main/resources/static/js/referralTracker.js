/**
 * 아티클 유입경로(referral) 추적 공통 모듈.
 *
 * 사용자가 어느 섹션(source)에서 아티클로 진입했는지를 클릭마다 서버에 비동기로 기록한다.
 * 조회수(/view)와 달리 쿨다운이 없어 모든 클릭이 1건씩 기록된다.
 *
 * 사용법:
 *   1) 카드 클릭 핸들러에서 직접 호출:
 *        trackReferral(articleId, 'POPULAR_HOME');
 *        trackReferral(articleId, 'RELATED_ARTICLE', currentArticleId);
 *   2) data 속성 기반 자동 추적:
 *        <div data-article-id="123" data-source="LATEST_HOME" data-source-context-id="">
 *      위 속성을 가진 엘리먼트는 클릭 시 자동으로 추적된다(전역 위임 리스너).
 *
 * source 값은 백엔드 ReferralSource enum과 일치해야 한다.
 */
(function () {
    'use strict';

    /**
     * 유입경로 클릭을 서버에 전송한다.
     * @param {number|string} articleId 클릭된 아티클 ID
     * @param {string} source ReferralSource enum 값 (예: 'POPULAR_HOME')
     * @param {number|string|null} [sourceContextId] 출발지 컨텍스트 ID (관련글 출발 article, theme/corporation id 등)
     */
    function trackReferral(articleId, source, sourceContextId) {
        if (!articleId || !source) return;

        const ctx = (sourceContextId !== undefined && sourceContextId !== null && sourceContextId !== '')
            ? Number(sourceContextId)
            : null;

        fetch(`/api/articles/${articleId}/referral`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'same-origin',
            keepalive: true,   // 같은 탭 이동(window.location.href) 시에도 전송 보장
            body: JSON.stringify({ source: source, sourceContextId: ctx })
        }).catch(() => {});
    }

    // 전역 노출 (다른 스크립트/인라인 핸들러에서 사용)
    window.trackReferral = trackReferral;

    // data-source 속성 기반 자동 추적 (이벤트 위임)
    document.addEventListener('click', function (e) {
        const el = e.target.closest('[data-source][data-article-id]');
        if (!el) return;
        // 카드 안의 별도 링크/버튼 클릭은 아티클 상세 진입으로 보지 않는다.
        if (e.target.closest('a, button, .like-button')) return;

        trackReferral(
            el.getAttribute('data-article-id'),
            el.getAttribute('data-source'),
            el.getAttribute('data-source-context-id')
        );
    }, true);
})();
