document.addEventListener('DOMContentLoaded', async function() {
    // totalSlides = document.querySelectorAll('.banner-slide').length;
    // startAutoSlide();

    // 사용자 정보 로드
    // await loadUserInfo();

    // Floating logos 위치 고정 초기화
    initializeFloatingLogos();
});

// Floating logos 위치를 고정하는 함수
function initializeFloatingLogos() {
    const floatingLogos = document.querySelectorAll('.floating-logo');

    // 각 로고에 고정된 위치 속성 추가
    floatingLogos.forEach((logo, index) => {
        // CSS에서 설정된 위치를 JavaScript로도 확인하여 유지
        logo.style.position = 'absolute';

        // 로고 인덱스를 데이터 속성으로 저장
        logo.setAttribute('data-logo-index', index + 1);
    });

    // 윈도우 리사이즈 이벤트 리스너 추가
    let resizeTimeout;
    window.addEventListener('resize', function() {
        // 리사이즈 이벤트가 완료된 후 100ms 뒤에 실행 (성능 최적화)
        clearTimeout(resizeTimeout);
        resizeTimeout = setTimeout(function() {
            maintainFloatingLogosPosition();
        }, 100);
    });
}

// 화면 크기 변경 시 floating logos 위치 유지
function maintainFloatingLogosPosition() {
    const floatingLogos = document.querySelectorAll('.floating-logo');

    floatingLogos.forEach((logo) => {
        // CSS에서 이미 정의된 위치를 그대로 유지
        // 추가적인 위치 조정이 필요한 경우에만 JavaScript로 처리

        // 로고가 컨테이너 밖으로 나가지 않도록 보장
        const container = logo.closest('.floating-logos-container');
        if (container) {
            const containerRect = container.getBoundingClientRect();
            const logoRect = logo.getBoundingClientRect();

            // 컨테이너 경계를 벗어나는 경우 위치 조정
            if (logoRect.right > containerRect.right) {
                logo.style.right = '5%';
                logo.style.left = 'auto';
            }
            if (logoRect.left < containerRect.left) {
                logo.style.left = '5%';
                logo.style.right = 'auto';
            }
        }
    });
}

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
