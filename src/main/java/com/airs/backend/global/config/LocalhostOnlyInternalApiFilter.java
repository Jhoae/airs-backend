package com.airs.backend.global.config;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class LocalhostOnlyInternalApiFilter extends OncePerRequestFilter {

    private static final String INTERNAL_API_KEY_HEADER = "X-AIRS-Internal-Key";

    private static final Set<String> LOCALHOST_ADDRESSES = Set.of(
            "127.0.0.1",
            "::1",
            "0:0:0:0:0:0:0:1"
    );

    private final InternalApiProperties internalApiProperties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/airs/internal/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String remoteAddr = request.getRemoteAddr();

        if (!isTrustedAddress(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "신뢰된 내부 네트워크 요청만 허용됩니다.");
            return;
        }

        if (!LOCALHOST_ADDRESSES.contains(remoteAddr) && !hasValidInternalApiKey(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "내부 API 인증 키가 필요합니다.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isTrustedAddress(HttpServletRequest request) {
        return internalApiProperties.getTrustedCidrs().stream()
                .map(IpAddressMatcher::new)
                .anyMatch(matcher -> matcher.matches(request));
    }

    private boolean hasValidInternalApiKey(HttpServletRequest request) {
        String configuredKey = internalApiProperties.getAccessKey();
        String requestKey = request.getHeader(INTERNAL_API_KEY_HEADER);

        return !configuredKey.isBlank() && configuredKey.equals(requestKey);
    }
}
