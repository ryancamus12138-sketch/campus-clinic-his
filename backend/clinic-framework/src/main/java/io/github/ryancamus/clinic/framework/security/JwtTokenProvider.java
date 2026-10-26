package io.github.ryancamus.clinic.framework.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;

/**
 * 访问令牌的签发与解析（HS256）。
 * <p>
 * 令牌只携带还原 {@link LoginUser} 所需的最少信息；用户角色变更后，
 * 最迟在访问令牌过期（默认 30 分钟）时生效，刷新时会重新查库。
 */
@Component
public class JwtTokenProvider {

    private static final String CLAIM_USERNAME = "un";
    private static final String CLAIM_NICKNAME = "nn";
    private static final String CLAIM_USER_TYPE = "ut";
    private static final String CLAIM_CAMPUS_ID = "cid";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_PERMISSIONS = "perms";
    private static final int MIN_SECRET_BYTES = 32;

    private final ClinicSecurityProperties.Jwt props;
    private final SecretKey key;
    private final Clock clock;

    public JwtTokenProvider(ClinicSecurityProperties properties, Clock clock) {
        this.props = properties.jwt();
        String secret = props.secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("clinic.security.jwt.secret 至少需要 32 字节");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    public AccessToken createAccessToken(LoginUser user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(props.accessTtl());
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .id(jti)
                .issuer(props.issuer())
                .subject(String.valueOf(user.getUserId()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .claim(CLAIM_USERNAME, user.getUsername())
                .claim(CLAIM_NICKNAME, user.getNickname())
                .claim(CLAIM_USER_TYPE, user.getUserType())
                .claim(CLAIM_CAMPUS_ID, user.getCampusId() == null ? null : String.valueOf(user.getCampusId()))
                .claim(CLAIM_ROLES, List.copyOf(user.getRoles()))
                .claim(CLAIM_PERMISSIONS, List.copyOf(user.getPermissions()))
                .signWith(key)
                .compact();
        return new AccessToken(token, jti, expiresAt);
    }

    /** 解析并校验令牌；签名错误、过期、格式非法都返回空 */
    public Optional<ParsedToken> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(props.issuer())
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            LoginUser user = LoginUser.builder()
                    .userId(Long.valueOf(claims.getSubject()))
                    .username(claims.get(CLAIM_USERNAME, String.class))
                    .nickname(claims.get(CLAIM_NICKNAME, String.class))
                    .userType(claims.get(CLAIM_USER_TYPE, Integer.class))
                    .campusId(toLong(claims.get(CLAIM_CAMPUS_ID, String.class)))
                    .roles(toSet(claims.get(CLAIM_ROLES, Collection.class)))
                    .permissions(toSet(claims.get(CLAIM_PERMISSIONS, Collection.class)))
                    .build();
            return Optional.of(new ParsedToken(user, claims.getId(), claims.getExpiration().toInstant()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Long toLong(String value) {
        return value == null ? null : Long.valueOf(value);
    }

    private static Set<String> toSet(Collection<?> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            values.forEach(v -> result.add(String.valueOf(v)));
        }
        return result;
    }

    public record AccessToken(String token, String jti, Instant expiresAt) {
    }

    public record ParsedToken(LoginUser user, String jti, Instant expiresAt) {
    }
}
