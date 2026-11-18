package de.ostms.evils.ebics.protocol;

public class EbicsProtocolException extends RuntimeException {
    public EbicsProtocolException(String message) {
        super(message);
    }
    public EbicsProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
