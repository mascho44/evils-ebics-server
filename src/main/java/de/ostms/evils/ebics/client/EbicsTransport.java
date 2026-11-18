package de.ostms.evils.ebics.client;

import java.io.IOException;

@FunctionalInterface
public interface EbicsTransport {
    String post(String xml) throws IOException, InterruptedException;
}
