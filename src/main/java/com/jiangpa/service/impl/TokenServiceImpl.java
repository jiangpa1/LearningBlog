package com.jiangpa.service.impl;


import com.jiangpa.common.CacheKeys;
import com.jiangpa.exception.BusinessException;
import com.jiangpa.properties.JwtProperties;
import com.jiangpa.service.TokenService;
import com.jiangpa.utils.JwtUtils;
import com.jiangpa.vo.TokenPair;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;


@Service
@Slf4j
public class TokenServiceImpl implements TokenService {
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtils jwtUtils;
    private final StringRedisTemplate stringRedisTemplate;
    private final JwtProperties jwtProperties;

    public TokenServiceImpl(JwtUtils jwtUtils, StringRedisTemplate stringRedisTemplate, JwtProperties jwtProperties) {
        this.jwtUtils = jwtUtils;
        this.stringRedisTemplate = stringRedisTemplate;
        this.jwtProperties = jwtProperties;
    }

    @Override
    public TokenPair issue(Long userId, String username, Integer role) {
        String accessToken = jwtUtils.generateAccessToken(userId, username, role);
        String refreshToken = jwtUtils.generateRefreshToken(userId, username, role);

        try {
            stringRedisTemplate.opsForValue().set(CacheKeys.tokenRefresh(userId),
                    jwtUtils.hashToken(refreshToken), jwtProperties.getRefreshExpiration());
        } catch (Exception e) {
            log.warn("登录缓存不可写", e);
            throw new BusinessException(503, "服务暂时不可用");
        }

        TokenPair tokenPair = new TokenPair();
        tokenPair.setAccessToken(accessToken);
        tokenPair.setRefreshToken(refreshToken);
        tokenPair.setExpiresIn(jwtProperties.getAccessExpiration().toMillis());

        return tokenPair;
    }

    @Override
    public TokenPair refresh(String refreshToken) {
        Claims claims;
        try {
            claims = jwtUtils.parseToken(refreshToken);
        } catch (ExpiredJwtException e) {
            throw new BusinessException(401, "登录已过期，请重新登陆");
        }catch (SignatureException | MalformedJwtException e) {
            // SignatureException：签名对不上，token 被篡改或不是本系统签发的
            // MalformedJwtException：token 结构损坏，根本不是合法 JWT
            // 注意 SignatureException 要用 io.jsonwebtoken.security 包下的那个，
            // io.jsonwebtoken 包下有个同名类已被废弃，catch 错了会捕获不到
            throw new BusinessException(401, "token 无效");
        } catch (JwtException e) {
            // 兜底，接住其余所有 JWT 相关异常，避免漏网后变成 500
            throw new BusinessException(401, "token 无效");
        }

        if(!jwtUtils.isRefreshToken(claims)){
            throw new BusinessException(401, "token类型错误");
        }

        Long userId = jwtUtils.getUserId(claims);

        String stored = null;
        try {
            stored = stringRedisTemplate.opsForValue().get(CacheKeys.tokenRefresh(userId));
        } catch (Exception e) {
            log.warn("无法查询到refresh key", e);
            throw new BusinessException(503, "服务暂时不可用");
        }
        if(stored == null || !stored.equals(jwtUtils.hashToken(refreshToken))){
            throw new BusinessException(401, "登录已失效，请重新登陆");
        }

        Number role = claims.get("role", Number.class);
        return issue(userId, claims.get("username", String.class), role == null ? null : role.intValue());
    }

    @Override
    public void logout(String authorization) {
        String token = stripBearer(authorization);
        if(token == null) {
            log.warn("Authorization 头格式非法，登出未生效：{}", authorization);
            return;
        }

        Claims claims = parseQuietly(token);
        if(claims == null) return;

        Long userId = jwtUtils.getUserId(claims);
        try {
            stringRedisTemplate.delete(CacheKeys.tokenRefresh(userId));
        } catch (Exception e) {
            log.warn("删除 refresh key 失败，不影响此次返回", e);
        }

        if (!jwtUtils.isAccessToken(claims)) {
            log.warn("登出时 token 类型不是 access，跳过黑名单");
            return;      // refresh key 已经删了，目的达到了
        }

        long remaining = jwtUtils.getRemainingMillis(claims);
        if (remaining > 0) {
            try {
                stringRedisTemplate.opsForValue().set(
                        CacheKeys.tokenBlacklist(jwtUtils.hashToken(token)), "1", remaining, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.error("写黑名单失败，登出未生效（fail-closed）", e);
                throw new BusinessException(503, "认证服务暂时不可用，请稍后重试");
            }
        }
    }

    private Claims parseQuietly(String token) {
        try {
            return jwtUtils.parseToken(token);
        }catch (ExpiredJwtException e){
            return e.getClaims();
        }catch (Exception e){
            log.warn("token 解析失败，登出未生效：{}", e.getMessage());
            return null;
        }
    }

    private String stripBearer(String authorization) {
        if (authorization == null) {
            return null;
        }
        if (!authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null; // 不是 Bearer 认证
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }

    @Override
    public boolean isRevoked(String accessToken) {
        String key = CacheKeys.tokenBlacklist(jwtUtils.hashToken(accessToken));
        try {
            return stringRedisTemplate.hasKey(key);
        }catch (Exception e){
            log.error("黑名单查询失败，按已吊销处理（fail-closed），key={}", key, e);
            throw new BusinessException(503, "认证服务暂时不可用，请稍后重试");
        }
    }
}
