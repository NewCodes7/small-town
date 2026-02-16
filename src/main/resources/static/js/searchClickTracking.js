/**
 * Search Click Tracking
 * 검색 결과에서 사용자가 클릭한 아티클/비디오를 추적합니다.
 */

(function() {
    'use strict';
    
    // 페이지 로드 시 초기화
    document.addEventListener('DOMContentLoaded', function() {
        initSearchClickTracking();
    });
    
    function initSearchClickTracking() {
        const urlParams = new URLSearchParams(window.location.search);
        const keyword = urlParams.get('keyword');
        
        // 검색 키워드가 없으면 추적하지 않음
        if (!keyword || keyword.trim() === '') {
            return;
        }
        
        // 아티클 카드 클릭 추적
        trackArticleClicks(keyword.trim());
        
        // 비디오 카드 클릭 추적
        trackVideoClicks(keyword.trim());
    }
    
    function trackArticleClicks(keyword) {
        // 아티클 카드 wrapper 찾기
        const articleCards = document.querySelectorAll('.article-card-wrapper .article-card[data-article-id]');
        
        articleCards.forEach(function(card, index) {
            // 클릭 추적을 위한 이벤트 리스너 추가
            card.addEventListener('click', function(event) {
                const articleId = this.getAttribute('data-article-id');
                
                // 클릭 추적 전송
                trackClick({
                    articleId: articleId,
                    keyword: keyword,
                    position: index + 1
                });
            }, { capture: true }); // capture phase에서 실행하여 다른 핸들러보다 먼저 실행
        });
    }
    
    function trackVideoClicks(keyword) {
        // 비디오 카드 wrapper 찾기
        const videoCards = document.querySelectorAll('.video-card-wrapper .video-card[data-video-id]');
        
        videoCards.forEach(function(card, index) {
            // 클릭 추적을 위한 이벤트 리스너 추가
            card.addEventListener('click', function(event) {
                const videoId = this.getAttribute('data-video-id');
                
                // 클릭 추적 전송
                trackClick({
                    videoId: videoId,
                    keyword: keyword,
                    position: index + 1
                });
            }, { capture: true });
        });
        
        // 비디오 링크도 추적 (외부 링크)
        const videoLinks = document.querySelectorAll('a.video-link[data-video-id]');
        videoLinks.forEach(function(link, index) {
            link.addEventListener('click', function(event) {
                const videoId = this.getAttribute('data-video-id');
                
                trackClick({
                    videoId: videoId,
                    keyword: keyword,
                    position: index + 1
                });
            });
        });
    }
    
    function trackClick(data) {
        const payload = {
            keyword: data.keyword,
            articleId: data.articleId || null,
            videoId: data.videoId || null,
            position: data.position || null,
            searchLogId: null // SearchLog ID는 현재 알 수 없으므로 null
        };
        
        // navigator.sendBeacon을 지원하는 경우
        if (navigator.sendBeacon) {
            const blob = new Blob([JSON.stringify(payload)], { type: 'application/json' });
            navigator.sendBeacon('/api/search/click', blob);
        } else {
            // 폴백: fetch with keepalive
            fetch('/api/search/click', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify(payload),
                keepalive: true
            }).catch(function(error) {
                console.error('클릭 추적 실패:', error);
            });
        }
    }
})();
