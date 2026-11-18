package de.ostms.evils.ebics.validation;

import de.ostms.evils.ebics.error.EbicsErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EbicsErrorCodeTest {
    @Test
    void okCodeIsZero() {
        assertEquals("000000", EbicsErrorCode.EBICS_OK.code());
    }

    @Test
    void invalidRequestHasDedicatedCode() {
        assertEquals("091002", EbicsErrorCode.EBICS_INVALID_REQUEST.code());
    }
}
