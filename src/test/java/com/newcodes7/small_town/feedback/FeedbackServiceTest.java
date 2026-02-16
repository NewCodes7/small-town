package com.newcodes7.small_town.feedback;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.auth.entity.Provider;
import com.newcodes7.small_town.auth.entity.Role;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.ProviderRepository;
import com.newcodes7.small_town.auth.repository.RoleRepository;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.auth.util.UserTestHelper;
import com.newcodes7.small_town.feedback.Feedback.FeedbackStatus;

/**
 * FeedbackService 통합 테스트
 */
@SpringBootTest
@TestPropertySource("classpath:application-test.properties")
@Transactional
public class FeedbackServiceTest {

    @Autowired
    private FeedbackService feedbackService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User testUser;
    private Role userRole;
    private Provider localProvider;

    @BeforeEach
    void setUp() {
        userRole = roleRepository.findByName("USER")
                .orElseGet(() -> roleRepository.save(new Role("USER")));
        
        localProvider = providerRepository.findByName("LOCAL")
                .orElseGet(() -> providerRepository.save(new Provider("LOCAL")));

        testUser = UserTestHelper.createTestUser(
                "test@example.com",
                passwordEncoder.encode("password123"),
                "테스트유저",
                userRole,
                localProvider
        );
        testUser = userRepository.save(testUser);
    }

    @Test
    @DisplayName("피드백 생성 - 성공")
    void createFeedback_Success() {
        FeedbackCreateDto dto = new FeedbackCreateDto();
        dto.setName("홍길동");
        dto.setEmail("hong@example.com");
        dto.setType("기능개선");
        dto.setMessage("검색 기능을 개선해주세요. 더 빠르고 정확한 검색이 필요합니다.");

        FeedbackResponseDto result = feedbackService.createFeedback(
                dto, "127.0.0.1", "TestAgent", testUser.getEmail()
        );

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("홍길동");
        assertThat(result.getType()).isEqualTo("기능개선");
        assertThat(result.getStatus()).isEqualTo(FeedbackStatus.PENDING.name());
    }

    @Test
    @DisplayName("피드백 생성 - 익명 사용자")
    void createFeedback_Anonymous() {
        FeedbackCreateDto dto = new FeedbackCreateDto();
        dto.setType("버그리포트");
        dto.setMessage("페이지 로딩이 느립니다. 개선이 필요합니다.");

        FeedbackResponseDto result = feedbackService.createFeedback(
                dto, "192.168.0.1", "TestAgent", null
        );

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("익명");
    }

    @Test
    @DisplayName("피드백 조회 - 전체")
    void getAllFeedbacks() {
        FeedbackCreateDto dto = new FeedbackCreateDto();
        dto.setType("기능개선");
        dto.setMessage("테스트 피드백입니다. 열 글자 이상입니다.");
        feedbackService.createFeedback(dto, "127.0.0.1", "TestAgent", null);

        Pageable pageable = PageRequest.of(0, 10);
        Page<FeedbackResponseDto> feedbacks = feedbackService.getAllFeedbacks(pageable);

        assertThat(feedbacks.getContent()).isNotEmpty();
    }

    @Test
    @DisplayName("피드백 상태 업데이트")
    void updateFeedbackStatus() {
        FeedbackCreateDto dto = new FeedbackCreateDto();
        dto.setType("기타");
        dto.setMessage("테스트 피드백입니다. 열 글자 이상입니다.");
        FeedbackResponseDto created = feedbackService.createFeedback(
                dto, "127.0.0.1", "TestAgent", null
        );

        FeedbackResponseDto updated = feedbackService.updateFeedbackStatus(
                created.getId(), FeedbackStatus.COMPLETED
        );

        assertThat(updated.getStatus()).isEqualTo(FeedbackStatus.COMPLETED.name());
    }

    @Test
    @DisplayName("피드백 생성 - 짧은 메시지")
    void createFeedback_ShortMessage() {
        FeedbackCreateDto dto = new FeedbackCreateDto();
        dto.setType("기타");
        dto.setMessage("짧음");

        assertThatThrownBy(() -> feedbackService.createFeedback(
                dto, "127.0.0.1", "TestAgent", null
        )).isInstanceOf(RuntimeException.class)
          .hasMessageContaining("10자 이상");
    }
}
