package com.seckill.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 解析 Authorization: Bearer &lt;access token&gt;,驗證通過即設定 SecurityContext。
 * fail-closed:token 缺失、過期、篡改、或非 access 型別時「不設定認證」,
 * 交由授權層 + entry point 統一回 401;絕不因此放行受保護資源。
 *
 * <p>刻意不標 @Component:避免 Spring Boot 將其當一般 servlet filter 自動註冊而在 security chain 外重複執行。
 * 由 SecurityConfig 明確建構並以 addFilterBefore 掛入。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                Claims claims = jwtService.parse(token);
                if (jwtService.isAccess(claims)) {
                    authenticate(request, claims);
                } else {
                    log.debug("拒絕非 access 型別 token 存取受保護資源");
                }
            } catch (JwtException | IllegalArgumentException ex) {
                // 過期 / 篡改 / 格式錯誤:保持未認證,不外洩原因
                log.debug("JWT 驗證失敗:{}", ex.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, Claims claims) {
        Long userId = Long.valueOf(claims.getSubject());
        String username = jwtService.usernameOf(claims);
        String role = jwtService.roleOf(claims);
        AuthUser principal = new AuthUser(userId, username, role);

        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
