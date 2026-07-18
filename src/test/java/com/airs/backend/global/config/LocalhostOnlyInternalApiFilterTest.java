package com.airs.backend.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalhostOnlyInternalApiFilterTest {

    @Test
    void allows_localhost_without_internal_api_key() throws Exception {
        InternalApiProperties properties = new InternalApiProperties();
        LocalhostOnlyInternalApiFilter filter = new LocalhostOnlyInternalApiFilter(properties);
        MockHttpServletRequest request = internalRequest("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void allows_trusted_container_only_with_matching_internal_api_key() throws Exception {
        InternalApiProperties properties = new InternalApiProperties();
        properties.setTrustedCidrs(List.of("127.0.0.1/32", "172.19.0.0/16"));
        properties.setAccessKey("test-internal-key");
        LocalhostOnlyInternalApiFilter filter = new LocalhostOnlyInternalApiFilter(properties);

        MockHttpServletResponse missingKeyResponse = new MockHttpServletResponse();
        filter.doFilter(internalRequest("172.19.0.8"), missingKeyResponse, new MockFilterChain());

        MockHttpServletRequest authenticatedRequest = internalRequest("172.19.0.8");
        authenticatedRequest.addHeader("X-AIRS-Internal-Key", "test-internal-key");
        MockHttpServletResponse authenticatedResponse = new MockHttpServletResponse();
        filter.doFilter(authenticatedRequest, authenticatedResponse, new MockFilterChain());

        assertThat(missingKeyResponse.getStatus()).isEqualTo(403);
        assertThat(authenticatedResponse.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest internalRequest(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/airs/internal/nodes/node_01/measurements/range");
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
