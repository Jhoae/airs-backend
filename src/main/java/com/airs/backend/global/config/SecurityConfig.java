package com.airs.backend.global.config;

import com.airs.backend.global.exception.RestAuthenticationEntryPoint;
import com.airs.backend.global.jwt.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // “요청이 들어왔을 때 어떤 보안 필터들을
    // 어떤 순서로 통과시킬지 정해놓은 보안 절차표”
    // 요청 받음
    // 보안 필터들 순서대로 실행
    // 규칙 위반이면 차단
    // 문제 없으면 컨트롤러로 보냄

    // Spring Security가 부팅할때 @Configuration로 읽고
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable()) // CSRF 보호를 끄고 JWT 기반 인증
                .formLogin(formLogin -> formLogin.disable()) // 우리가 구현한 로그인 로직 사용
                .httpBasic(httpBasic -> httpBasic.disable()) // 우리는 로그인 후 발급된 JWT를 쓰는 방식
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                ) // 세션을 만들어서 로그인 상태를 저장하지 않고, jwt로 매 요청을 독립적으로 처리, stateless
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                ) // 인증이 필요한 요청인데, 인증이 안됐을때, restAuthenticationEntryPoint
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info", "/airs/health").permitAll()
                        .requestMatchers("/airs/auth/signup", "/airs/auth/login").permitAll()
                        .requestMatchers("/airs/campuses").permitAll()
                        .anyRequest().authenticated()
                ) // 공개 API, 보호 API 규칙
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
                // JWT 필터를 기본 UsernamePasswordAuthenticationFilter보다 앞쪽에서 실행하겠다

        // 위 보안 설정을 바탕으로
        // 실제 SecurityFilterChain 객체를 만들어서 반환
        return http.build();
    }
}
