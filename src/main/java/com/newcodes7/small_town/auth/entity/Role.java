package com.newcodes7.small_town.auth.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "role")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Role {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    
    @Column(name = "name", nullable = false, unique = true, length = 50)
    private String name;
    
    public Role(String name) {
        this.name = name;
    }
    
    public enum RoleType {
        USER("USER"),
        ADMIN("ADMIN");
        
        private final String value;
        
        RoleType(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
    }
}