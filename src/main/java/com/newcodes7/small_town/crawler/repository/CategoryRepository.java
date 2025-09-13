package com.newcodes7.small_town.crawler.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.global.entity.Category;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    @Query("SELECT DISTINCT c FROM Category c WHERE c.name = :name AND c.deletedAt IS NULL")
    Optional<Category> findByName(@Param("name") String name);
    
    @Query("SELECT c FROM Category c WHERE c.deletedAt IS NULL ORDER BY c.name")
    List<Category> findAllActive();
    
    @Override
    @Query("SELECT c FROM Category c WHERE c.deletedAt IS NULL")
    @org.springframework.lang.NonNull
    List<Category> findAll();
}
