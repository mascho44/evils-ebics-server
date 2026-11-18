package de.ostms.evils.ebics.error;

public enum EbicsErrorCode {
    EBICS_OK("000000", "OK"),
    EBICS_AUTHORISATION_ORDERTYPE_FAILED("090003", "Subscriber is not authorised for this order type"),
    EBICS_UNSUPPORTED_ORDER_TYPE("091005", "Order type is not supported by this server"),
    EBICS_XML_ERROR("091001", "XML syntax or schema error"),
    EBICS_INVALID_REQUEST("091002", "Invalid EBICS request"),
    EBICS_UNSUPPORTED_VERSION("091003", "Unsupported EBICS version"),
    EBICS_INTERNAL_ERROR("091099", "Internal server error");

    private final String code;
    private final String description;

    EbicsErrorCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() { return code; }
    public String description() { return description; }
}
