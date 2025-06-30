-- Feedback 테이블 생성
CREATE TABLE IF NOT EXISTS feedback (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100),
    email VARCHAR(255),
    type VARCHAR(50) NOT NULL,
    message TEXT NOT NULL,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL,
    updated_at DATETIME,
    
    INDEX idx_feedback_type (type),
    INDEX idx_feedback_status (status),
    INDEX idx_feedback_created_at (created_at),
    INDEX idx_feedback_ip_created (ip_address, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 기본 제약조건 추가
ALTER TABLE feedback 
ADD CONSTRAINT chk_feedback_status 
CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'REJECTED'));

ALTER TABLE feedback 
ADD CONSTRAINT chk_feedback_type 
CHECK (type IN ('기능개선', '버그리포트', '새기능', 'UI/UX', '기타'));