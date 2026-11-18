package de.ostms.evils.ebics.admin;

/** iban is null/blank for the user's account-wide default, or an IBAN for a per-account override. */
public record PaymentAuthorizationRequest(String iban, String signatureClass) { }
