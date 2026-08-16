package ai.xdev.aisdlc.service;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import javax.net.ssl.SSLContext;

/** Small transport seam: production uses JDK HTTP; tests never need a real provider endpoint. */
public interface ProviderHttpTransport {
  record Request(URI endpoint, String body, String authorizationHeader, String idempotencyKey, Duration timeout, SSLContext sslContext) {}
  record Response(int statusCode, String body) {}
  Response execute(Request request) throws IOException, InterruptedException;
}
