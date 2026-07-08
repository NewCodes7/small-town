package com.newcodes7.small_town.search.repository;

import com.newcodes7.small_town.search.entity.RagQueryLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RagQueryLogRepository extends JpaRepository<RagQueryLog, Long> {
}
