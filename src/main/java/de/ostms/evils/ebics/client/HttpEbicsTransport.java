package de.ostms.evils.ebics.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class HttpEbicsTransport implements EbicsTransport {
    private final URI endpoint;
    private final HttpClient client;

    public HttpEbicsTransport(URI endpoint) {
        this.endpoint=endpoint;
        this.client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public String post(String xml) throws IOException, InterruptedException {
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60)).header("Content-Type","application/xml; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(xml, StandardCharsets.UTF_8)).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if(response.statusCode()!=200) throw new IOException("EBICS endpoint returned HTTP "+response.statusCode());
        return response.body();
    }
}
