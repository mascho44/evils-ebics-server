package de.ostms.evils.ebics.security;

/** Central source for bootstrap credentials and the persistent key-protection secret. */
public interface CredentialProvider {
    String masterSecret();
    String bootstrapUsername();
    String bootstrapPassword();
}
