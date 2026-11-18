package de.ostms.evils.ebics.error;

public record EbicsErrorResponse(EbicsErrorCode code, String message) {}
