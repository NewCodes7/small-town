package com.newcodes7.small_town.auth.jwt;

import com.newcodes7.small_town.auth.entity.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtTokenProvider {
    
    @Value("${app.jwt.secret:smalltown-jwt-secret-key-for-authentication-and-authorization-system}")
    private String jwtSecret;
    
    @Value("${app.jwt.access-token-expiration:86400000}") // 24시간
    private long accessTokenExpiration;
    
    @Value("${app.jwt.refresh-token-expiration:604800000}") // 7일
    private long refreshTokenExpiration;
    
    private SecretKey key;
    
    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }
    
    public String generateAccessToken(Authentication authentication) {
        UserDetails userPrincipal = (UserDetails) authentication.getPrincipal();
        return generateToken(userPrincipal.getUsername(), accessTokenExpiration, "ACCESS");
    }
    
    public String generateRefreshToken(Authentication authentication) {
        UserDetails userPrincipal = (UserDetails) authentication.getPrincipal();
        return generateToken(userPrincipal.getUsername(), refreshTokenExpiration, "REFRESH");
    }
    
    public String generateAccessToken(String email) {
        return generateToken(email, accessTokenExpiration, "ACCESS");
    }
    
    public String generateRefreshToken(String email) {
        return generateToken(email, refreshTokenExpiration, "REFRESH");
    }
    
    private String generateToken(String email, long expiration, String tokenType) {
        Date expiryDate = new Date(System.currentTimeMillis() + expiration);
        
        return Jwts.builder()
                .subject(email)
                .claim("type", tokenType)
                .issuedAt(new Date())
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }
    
    public String getEmailFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        
        return claims.getSubject();
    }
    
    public String getTokenType(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        
        return claims.get("type", String.class);
    }
    
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token);
            return true;
        } catch (SecurityException ex) {
            System.err.println("Invalid JWT signature");
        } catch (MalformedJwtException ex) {
            System.err.println("Invalid JWT token");
        } catch (ExpiredJwtException ex) {
            System.err.println("Expired JWT token");
        } catch (UnsupportedJwtException ex) {
            System.err.println("Unsupported JWT token");
        } catch (IllegalArgumentException ex) {
            System.err.println("JWT claims string is empty");
        }
        return false;
    }
    
    public boolean isAccessToken(String token) {
        return "ACCESS".equals(getTokenType(token));
    }
    
    public boolean isRefreshToken(String token) {
        return "REFRESH".equals(getTokenType(token));
    }
}