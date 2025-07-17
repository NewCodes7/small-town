document.addEventListener('DOMContentLoaded', async function() {
    // totalSlides = document.querySelectorAll('.banner-slide').length;
    // startAutoSlide();
    
    // 사용자 정보 로드
    await loadUserInfo();
    
    // ArticleManager 초기화
    articleManager = new ArticleManager();
});

function startAutoSlide() {
    if (slideInterval) {
        clearInterval(slideInterval);
    }
    slideInterval = setInterval(() => {
        if (!isHovered && !manualControl) {
            nextSlide();
        }
    }, 4000);
}

function stopAutoSlide() {
    if (slideInterval) {
        clearInterval(slideInterval);
        slideInterval = null;
    }
}

function nextSlide() {
    currentSlide = (currentSlide + 1) % totalSlides;
    updateSlide();
}

function previousSlide() {
    currentSlide = (currentSlide - 1 + totalSlides) % totalSlides;
    updateSlide();
    handleManualControl();
}

function goToSlide(index) {
    currentSlide = index;
    updateSlide();
    handleManualControl();
}

function handleManualControl() {
    manualControl = true;
    stopAutoSlide();
    setTimeout(() => {
        manualControl = false;
        startAutoSlide();
    }, 8000); // 8초 후 자동 슬라이드 재시작
}

function updateSlide() {
    const slides = document.querySelectorAll('.banner-slide');
    const indicators = document.querySelectorAll('.indicator');
    
    slides.forEach((slide, index) => {
        slide.classList.remove('active', 'prev');
        if (index === currentSlide) {
            slide.classList.add('active');
        } else if (index < currentSlide) {
            slide.classList.add('prev');
        }
    });
    
    indicators.forEach((indicator, index) => {
        indicator.classList.toggle('active', index === currentSlide);
    });
}

// // 마우스 호버 시 자동 슬라이드 일시정지
// document.querySelector('.banner-carousel').addEventListener('mouseenter', () => {
//     isHovered = true;
// });

// document.querySelector('.banner-carousel').addEventListener('mouseleave', () => {
//     isHovered = false;
// });