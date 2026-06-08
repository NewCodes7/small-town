package com.newcodes7.small_town.admin.service;

import com.newcodes7.small_town.admin.dto.AdminUserListDto;
import com.newcodes7.small_town.admin.dto.ArticleViewLogDto;
import com.newcodes7.small_town.admin.dto.VideoViewLogDto;
import com.newcodes7.small_town.article.entity.ViewLog;
import com.newcodes7.small_town.like.repository.LikeLogRepository;
import com.newcodes7.small_town.article.repository.ViewLogRepository;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.search.entity.SearchLog;
import com.newcodes7.small_town.search.repository.SearchLogRepository;
import com.newcodes7.small_town.video.entity.VideoViewLog;
import com.newcodes7.small_town.video.repository.VideoLikeLogRepository;
import com.newcodes7.small_town.video.repository.VideoViewLogRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserActivityService {

    private final UserRepository userRepository;
    private final ViewLogRepository viewLogRepository;
    private final VideoViewLogRepository videoViewLogRepository;
    private final SearchLogRepository searchLogRepository;
    private final LikeLogRepository likeLogRepository;
    private final VideoLikeLogRepository videoLikeLogRepository;

    public Page<AdminUserListDto> getUsers(String search, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<User> users = search != null && !search.isBlank()
                ? userRepository.findByEmailOrNicknameContainingOrderByLastActivityDesc(search, pageable)
                : userRepository.findAllOrderByLastActivityDesc(pageable);

        List<Long> userIds = users.map(User::getId).toList();
        Map<Long, LocalDateTime> lastViewAtMap = viewLogRepository.findLastArticleViewAtByUserIds(userIds)
                .stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (LocalDateTime) row[1]
                ));

        return users.map(u -> new AdminUserListDto(
                u.getId(),
                u.getNickname(),
                u.getEmail(),
                u.getStatus().name(),
                u.getLastLoginAt(),
                u.getCreatedAt(),
                lastViewAtMap.get(u.getId())
        ));
    }

    public User getUser(Long userId) {
        return userRepository.findByIdWithRoleAndProvider(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));
    }

    public Page<ArticleViewLogDto> getArticleViewsByUser(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<ViewLog> logs = viewLogRepository.findByUserIdWithDetails(userId, pageable);
        return toArticleViewLogDtoPage(logs, userId, null);
    }

    public Page<ArticleViewLogDto> getArticleViewsByIp(String ip, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<ViewLog> logs = viewLogRepository.findAnonymousByIpAddressWithDetails(ip, pageable);
        return toArticleViewLogDtoPage(logs, null, ip);
    }

    public Page<VideoViewLogDto> getVideoViewsByUser(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<VideoViewLog> logs = videoViewLogRepository.findByUserIdWithDetails(userId, pageable);
        return toVideoViewLogDtoPage(logs, userId, null);
    }

    public Page<VideoViewLogDto> getVideoViewsByIp(String ip, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<VideoViewLog> logs = videoViewLogRepository.findAnonymousByIpAddressWithDetails(ip, pageable);
        return toVideoViewLogDtoPage(logs, null, ip);
    }

    public Page<SearchLog> getSearchLogsByUser(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return searchLogRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    public Page<SearchLog> getSearchLogsByIp(String ip, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return searchLogRepository.findAnonymousByIpAddressOrderByCreatedAtDesc(ip, pageable);
    }

    private Page<ArticleViewLogDto> toArticleViewLogDtoPage(Page<ViewLog> logs, Long userId, String ip) {
        if (logs.isEmpty()) {
            return logs.map(vl -> null);
        }
        List<Long> articleIds = logs.map(vl -> vl.getArticle().getId()).toList();
        Set<Long> likedIds;
        if (userId != null) {
            likedIds = Set.copyOf(likeLogRepository.findLikedArticleIdsByUserIdAndArticleIds(userId, articleIds));
        } else {
            likedIds = Set.copyOf(likeLogRepository.findLikedArticleIdsByIpAddressAndArticleIds(ip, articleIds));
        }

        return logs.map(vl -> new ArticleViewLogDto(
                vl.getId(),
                vl.getArticle().getId(),
                vl.getArticle().getTitle(),
                vl.getArticle().getLink(),
                vl.getArticle().getCorporation() != null ? vl.getArticle().getCorporation().getName() : "-",
                vl.getArticle().getViewCount(),
                vl.getArticle().getLikeCount(),
                vl.getIpAddress(),
                vl.getCreatedAt(),
                likedIds.contains(vl.getArticle().getId())
        ));
    }

    private Page<VideoViewLogDto> toVideoViewLogDtoPage(Page<VideoViewLog> logs, Long userId, String ip) {
        if (logs.isEmpty()) {
            return logs.map(vl -> null);
        }
        List<Long> videoIds = logs.map(vl -> vl.getVideo().getId()).toList();
        Set<Long> likedIds;
        if (userId != null) {
            likedIds = Set.copyOf(videoLikeLogRepository.findLikedVideoIdsByUserIdAndVideoIds(userId, videoIds));
        } else {
            likedIds = Set.copyOf(videoLikeLogRepository.findLikedVideoIdsByIpAddressAndVideoIds(ip, videoIds));
        }

        return logs.map(vl -> new VideoViewLogDto(
                vl.getId(),
                vl.getVideo().getId(),
                vl.getVideo().getTitle(),
                vl.getVideo().getLink(),
                vl.getVideo().getCorporation() != null ? vl.getVideo().getCorporation().getName() : "-",
                vl.getVideo().getViewCount(),
                vl.getVideo().getLikeCount(),
                vl.getIpAddress(),
                vl.getCreatedAt(),
                likedIds.contains(vl.getVideo().getId())
        ));
    }
}
