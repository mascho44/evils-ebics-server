package de.ostms.evils.ebics.protocol;

import java.time.LocalDate;

public record EbicsRequest(
        String version,
        String hostId,
        String partnerId,
        String userId,
        String orderType,
        String orderId,
        String xml,
        /** body/DataTransfer/OrderData, base64-decoded - null when the request has no body
         * (most order types) or the element is absent. See EbicsRequestParser. */
        String orderDataXml,
        /** header/mutable/TransactionPhase ("Initialisation", "Transfer", "Receipt") - only
         * meaningful for multi-phase order/file-transfer requests (ebicsRequest), null for the
         * unsecured key-management requests which don't carry a phase. */
        String transactionPhase,
        /** header/static/TransactionID, hex-encoded as sent on the wire - identifies which
         * download this Receipt-phase request is acknowledging. Null outside Transfer/Receipt. */
        String transactionId,
        /** header/static/OrderDetails/BTDOrderParams/Service - only present for EBICS 3.0 BTD
         * business-transaction-format downloads (e.g. real clients' camt.053 statement fetch,
         * which is sent as AdminOrderType=BTD, not the legacy order type STA). */
        String btdServiceName,
        String btdMsgName,
        LocalDate dateFrom,
        LocalDate dateTo
) {
    public EbicsRequest(String version, String hostId, String partnerId, String userId,
                         String orderType, String orderId, String xml) {
        this(version, hostId, partnerId, userId, orderType, orderId, xml, null, null, null, null, null, null, null);
    }

    public EbicsRequest(String version, String hostId, String partnerId, String userId,
                         String orderType, String orderId, String xml, String orderDataXml) {
        this(version, hostId, partnerId, userId, orderType, orderId, xml, orderDataXml, null, null, null, null, null, null);
    }
}
