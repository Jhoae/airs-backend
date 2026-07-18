package com.airs.backend.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "internal-api")
public class InternalApiProperties {

    private List<String> trustedCidrs = List.of("127.0.0.1/32", "::1/128");
    private String accessKey = "";

    public List<String> getTrustedCidrs() {
        return trustedCidrs;
    }

    public void setTrustedCidrs(List<String> trustedCidrs) {
        this.trustedCidrs = trustedCidrs == null || trustedCidrs.isEmpty()
                ? List.of("127.0.0.1/32", "::1/128")
                : List.copyOf(trustedCidrs);
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey == null ? "" : accessKey;
    }
}
