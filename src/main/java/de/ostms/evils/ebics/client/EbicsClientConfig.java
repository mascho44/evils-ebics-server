package de.ostms.evils.ebics.client;

import java.net.URI;
import java.util.Objects;

/** Connection and subscriber data for one EBICS access. */
public record EbicsClientConfig(URI endpoint, String hostId, String partnerId, String userId) {
    public EbicsClientConfig {
        Objects.requireNonNull(endpoint, "endpoint");
        hostId = identifier(hostId, "hostId");
        partnerId = identifier(partnerId, "partnerId");
        userId = identifier(userId, "userId");
        if (!endpoint.isAbsolute() || !SetHolder.SCHEMES.contains(endpoint.getScheme()))
            throw new IllegalArgumentException("endpoint must use http or https");
    }

    private static String identifier(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[A-Za-z0-9._-]{1,35}")) throw new IllegalArgumentException("Invalid " + name);
        return value;
    }

    private static final class SetHolder {
        private static final java.util.Set<String> SCHEMES = java.util.Set.of("http", "https");
    }
}
