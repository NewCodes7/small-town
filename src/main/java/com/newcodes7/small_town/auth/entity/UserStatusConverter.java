package com.newcodes7.small_town.auth.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class UserStatusConverter implements AttributeConverter<User.UserStatus, String> {
    
    @Override
    public String convertToDatabaseColumn(User.UserStatus attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.getValue();
    }
    
    @Override
    public User.UserStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        
        for (User.UserStatus status : User.UserStatus.values()) {
            if (status.getValue().equals(dbData)) {
                return status;
            }
        }
        
        throw new IllegalArgumentException("Unknown database value: " + dbData);
    }
}