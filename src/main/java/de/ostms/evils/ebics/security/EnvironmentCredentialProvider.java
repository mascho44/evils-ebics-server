package de.ostms.evils.ebics.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EnvironmentCredentialProvider implements CredentialProvider {
    private final String masterSecret, username, password;
    public EnvironmentCredentialProvider(@Value("${evils.crypto.master-secret:}") String masterSecret,
                                         @Value("${evils.admin.bootstrap-username:admin}") String username,
                                         @Value("${evils.admin.bootstrap-password:changeit}") String password) {
        this.masterSecret = masterSecret; this.username = username; this.password = password;
    }
    public String masterSecret() { return masterSecret; }
    public String bootstrapUsername() { return username; }
    public String bootstrapPassword() { return password; }
}
