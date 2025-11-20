// 사용자 인증 정보 관리 공통 모듈

class UserAuth {
    constructor() {
        this.userInfo = null;
        this.isAdmin = false;
        this.authenticated = false;
    }

    // 사용자 정보 가져오기
    async fetchUserInfo() {
        try {
            const response = await fetch('/api/user-info');
            const data = await response.json();

            this.userInfo = data;
            this.authenticated = data.authenticated || false;
            this.isAdmin = data.isAdmin || false;

            return data;
        } catch (error) {
            console.error('사용자 정보 조회 실패:', error);
            this.authenticated = false;
            this.isAdmin = false;
            return null;
        }
    }

    // 관리자 여부 확인
    isAdminUser() {
        return this.isAdmin;
    }

    // 인증 여부 확인
    isAuthenticated() {
        return this.authenticated;
    }

    // 사용자 이름 가져오기
    getUsername() {
        return this.userInfo?.username || null;
    }

    // 관리자 컨트롤 표시 (기본 구현)
    showAdminControls(selector = '.admin-control-panel') {
        if (this.isAdmin) {
            const panels = document.querySelectorAll(selector);
            panels.forEach(panel => {
                panel.classList.remove('d-none');
                panel.style.display = '';
            });
        }
    }

    // 관리자 컨트롤 숨기기
    hideAdminControls(selector = '.admin-control-panel') {
        const panels = document.querySelectorAll(selector);
        panels.forEach(panel => {
            panel.classList.add('d-none');
            panel.style.display = 'none';
        });
    }
}

// 전역 인스턴스 생성
const userAuth = new UserAuth();
