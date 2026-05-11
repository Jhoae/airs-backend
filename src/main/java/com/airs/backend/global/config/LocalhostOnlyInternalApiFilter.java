package com.airs.backend.global.config;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class LocalhostOnlyInternalApiFilter extends OncePerRequestFilter {

    private static final Set<String> LOCALHOST_ADDRESSES = Set.of(
            "127.0.0.1",
            "::1",
            "0:0:0:0:0:0:0:1"
    );

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

        if (!LOCALHOST_ADDRESSES.contains(remoteAddr)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "localhost 요청만 허용됩니다.");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
