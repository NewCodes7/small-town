// 카드 클릭 시 상세 페이지로 이동
document.querySelectorAll('.corporation-card').forEach(card => {
    card.addEventListener('click', function(e) {
        const cardLink = this.querySelector('.corp-card-link');
        if (cardLink) {
            window.location.href = cardLink.href;
        }
    });
    
    // 카드에 커서 스타일 추가
    card.style.cursor = 'pointer';
});

// 검색 폼 개선
document.getElementById('searchKeyword').addEventListener('keypress', function(e) {
    if (e.key === 'Enter') {
        this.form.submit();
    }
});