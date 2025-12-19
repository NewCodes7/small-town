package com.newcodes7.small_town.auth.repository;

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

    @Query("SELECT u FROM User u JOIN FETCH u.role LEFT JOIN FETCH u.provider WHERE u.email = :email AND u.deletedAt IS NULL")
    Optional<User> findByEmailAndDeletedAtIsNull(@Param("email") String email);

    boolean existsByEmail(String email);

    boolean existsByEmailAndDeletedAtIsNull(String email);

    @Query("SELECT u FROM User u WHERE u.email LIKE %:search% OR u.nickname LIKE %:search%")
    Page<User> findByEmailOrNicknameContaining(@Param("search") String search, Pageable pageable);
}