package com.runpulse.server.security;

import com.runpulse.server.config.AdminProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * 管理后台 JWT 签发与校验（HS256）。
 */
@Component
public class JwtService {

    private final AdminProperties props;
    private final SecretKey key;

    public JwtService(AdminProperties props) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(props.getJwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String issue(long userId, String username) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .issuedAt(new Date(now))
                .expiration(new Date(now + props.getTokenTtlMillis()))
                .signWith(key)
                .compact();
    }

    /** 校验并解析 token，非法/过期返回 null。 */
    public Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            return null;
        }
    }
}
