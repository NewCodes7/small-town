package com.newcodes7.small_town.auth.repository;

import com.newcodes7.small_town.auth.entity.Provider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProviderRepository extends JpaRepository<Provider, Integer> {
    
    Optional<Provider> findByName(String name);
}