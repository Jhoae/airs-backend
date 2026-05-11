package com.airs.backend.global.jwt;

import java.io.IOException;

import com.airs.backend.global.exception.RestAuthenticationEntryPoint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;

    // 실제 JWT 검사기.
    // 모든 요청에서 controller, service보다 먼저 이 메서드가 실행됨
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String authorizationHeader = request.getHeader(AUTHORIZATION_HEADER);
        // Authorization: Bearer eyJhbGciOi... 요청이 들어오면
        // authorizationHeader = Bearer eyJhbGciOi...

        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {

            // 다음 필터/컨트롤러로 요청을 넘기는 함수
            // login이나 sigup처럼 공개 API는 그냥 넘어감
            filterChain.doFilter(request, response);
            return;
        }

        // "Bearer " 부분을 잘라냄
        // token = eyJhbGciOi...
        String token = authorizationHeader.substring(BEARER_PREFIX.length());

        if (!jwtTokenProvider.validateToken(token)) {
            restAuthenticationEntryPoint.commence(
                    request,
                    response,
                    new BadCredentialsException("유효하지 않은 토큰입니다.")
            );
            return;
        }

        Long userId = jwtTokenProvider.extractUserId(token);

        // “현재 인증된 사용자”를 표현하는 객체
        // principal = userId
        // credentials = null - 비밀번호 검사는 로그인 때 끝
        // authorities = 권한 없음 - role 기반 권한 체크를 안 하니까 빈 목록
        // “현재 로그인 사용자는 userId=1인 사람이다"라는 인증 객체
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        new CurrentUserPrincipal(userId),
                        null,
                        AuthorityUtils.NO_AUTHORITIES
                );

        // 토큰이 유효하면 userId를 꺼내서 Authentication 객체를 만들고
        // SecurityContextHolder에 등록
        // - 이제부터 Spring은 이 요청을 “인증된 사용자 요청”으로 인식
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 이 필터의 일은 끝났으니 다음 필터나 컨트롤러로 요청을 넘기기
        filterChain.doFilter(request, response);

        // 정리
        // 토큰 읽음
        // 토큰 적절함
        // SecurityContextHolder에 인증 정보 등록
        // Spring Security가 “인증된 요청이네”라고 이해
        // 보호 API(ex 기기등록) 수행
    }
}
