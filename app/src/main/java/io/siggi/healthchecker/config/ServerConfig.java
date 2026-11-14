package io.siggi.healthchecker.config;

import java.util.List;

/**
 * @param publicBaseUrl external base URL without a trailing slash, or {@code null} if not set
 */
public record ServerConfig(int port, String bind, String publicBaseUrl, List<String> trustedProxies,
                           UnknownHostsPolicy unknownHosts) {
    public ServerConfig {
        trustedProxies = List.copyOf(trustedProxies);
    }
}
