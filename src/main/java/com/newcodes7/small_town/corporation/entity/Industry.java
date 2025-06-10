package com.newcodes7.small_town.corporation.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "industry")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Industry {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    
    @Column(nullable = false, unique = true, length = 50)
    private String name;
    
    @OneToMany(mappedBy = "industry", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<CorporationIndustry> corporationIndustries = new ArrayList<>();
}