package com.newcodes7.small_town.auth.repository;

import com.newcodes7.small_town.auth.entity.Provider;
import com.newcodes7.small_town.auth.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    @Query("SELECT u FROM User u JOIN FETCH u.role LEFT JOIN FETCH u.provider WHERE u.email = :email")
    Optional<User> findByEmailWithRoleAndProvider(@Param("email") String email);

    @Query("SELECT u FROM User u JOIN FETCH u.role LEFT JOIN FETCH u.provider WHERE u.id = :id")
    Optional<User> findByIdWithRoleAndProvider(@Param("id") Long id);

    @Query("SELECT u FROM User u JOIN FETCH u.role LEFT JOIN FETCH u.provider WHERE u.email = :email AND u.deletedAt IS NULL")
    Optional<User> findByEmailAndDeletedAtIsNull(@Param("email") String email);

    boolean existsByEmail(String email);

    boolean existsByEmailAndDeletedAtIsNull(String email);

    @Query("SELECT u FROM User u WHERE u.email LIKE %:search% OR u.nickname LIKE %:search%")
    Page<User> findByEmailOrNicknameContaining(@Param("search") String search, Pageable pageable);

    @Query(value = """
            SELECT u.* FROM users u
            LEFT JOIN (
                SELECT user_id, MAX(created_at) AS last_article_view
                FROM view_log
                WHERE user_id IS NOT NULL
                GROUP BY user_id
            ) la ON la.user_id = u.id
            ORDER BY la.last_article_view DESC NULLS LAST
            """,
            countQuery = "SELECT COUNT(*) FROM users",
            nativeQuery = true)
    Page<User> findAllOrderByLastActivityDesc(Pageable pageable);

    @Query(value = """
            SELECT u.* FROM users u
            LEFT JOIN (
                SELECT user_id, MAX(created_at) AS last_article_view
                FROM view_log
                WHERE user_id IS NOT NULL
                GROUP BY user_id
            ) la ON la.user_id = u.id
            WHERE u.email LIKE %:search% OR u.nickname LIKE %:search%
            ORDER BY la.last_article_view DESC NULLS LAST
            """,
            countQuery = "SELECT COUNT(*) FROM users u WHERE u.email LIKE %:search% OR u.nickname LIKE %:search%",
            nativeQuery = true)
    Page<User> findByEmailOrNicknameContainingOrderByLastActivityDesc(@Param("search") String search, Pageable pageable);

    // OAuth provider ID 기반 조회
    @Query("SELECT u FROM User u JOIN FETCH u.role LEFT JOIN FETCH u.provider WHERE u.provider = :provider AND u.oauthProviderId = :oauthProviderId")
    Optional<User> findByProviderAndOauthProviderId(@Param("provider") Provider provider, @Param("oauthProviderId") String oauthProviderId);

    boolean existsByProviderAndOauthProviderId(Provider provider, String oauthProviderId);

    // OAuth username 기반 조회
    @Query("SELECT u FROM User u JOIN FETCH u.role LEFT JOIN FETCH u.provider WHERE u.oauthUsername = :oauthUsername AND u.deletedAt IS NULL")
    Optional<User> findByOauthUsernameAndDeletedAtIsNull(@Param("oauthUsername") String oauthUsername);

    // OAuth provider ID 기반 조회 (삭제되지 않은 사용자만)
    @Query("SELECT u FROM User u JOIN FETCH u.role LEFT JOIN FETCH u.provider WHERE u.oauthProviderId = :oauthProviderId AND u.deletedAt IS NULL")
    Optional<User> findByOauthProviderIdAndDeletedAtIsNull(@Param("oauthProviderId") String oauthProviderId);

    /**
     * username으로 사용자 조회 (email, oauthUsername, oauthProviderId 중 하나)
     * CustomUserDetailsService와 동일한 로직
     */
    default Optional<User> findByUsernameAndDeletedAtIsNull(String username) {
        if (username == null || username.trim().isEmpty()) {
            return Optional.empty();
        }

        // 1. email로 찾기
        Optional<User> user = findByEmailAndDeletedAtIsNull(username);
        if (user.isPresent()) {
            return user;
        }

        // 2. oauthUsername으로 찾기
        user = findByOauthUsernameAndDeletedAtIsNull(username);
        if (user.isPresent()) {
            return user;
        }

        // 3. oauthProviderId로 찾기
        user = findByOauthProviderIdAndDeletedAtIsNull(username);
        if (user.isPresent()) {
            return user;
        }

        // 4. "user_숫자" 형태면 ID로 찾기
        if (username.startsWith("user_")) {
            try {
                String idStr = username.substring(5);
                if (!idStr.equals("unknown")) {
                    Long userId = Long.parseLong(idStr);
                    // JOIN FETCH를 사용하여 role과 provider를 함께 로드
                    return findByIdWithRoleAndProvider(userId)
                            .filter(u -> u.getDeletedAt() == null);
                }
            } catch (NumberFormatException e) {
                // ID 파싱 실패 시 무시
            }
        }

        return Optional.empty();
    }
}