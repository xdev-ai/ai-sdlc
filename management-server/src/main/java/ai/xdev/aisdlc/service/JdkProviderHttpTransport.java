package ai.xdev.aisdlc.service;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class JdkProviderHttpTransport implements ProviderHttpTransport {
  @Override
  public Response execute(Request request) throws IOException, InterruptedException {
    HttpClient.Builder client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(request.timeout());
    if (request.sslContext() != null) client.sslContext(request.sslContext());
    HttpRequest outbound = HttpRequest.newBuilder(request.endpoint())
        .timeout(request.timeout())
        .header("Content-Type", "application/json")
        .header("Authorization", request.authorizationHeader())
        .header("Idempotency-Key", request.idempotencyKey())
        .POST(HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8))
        .build();
    HttpResponse<String> response = client.build().send(outbound, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new Response(response.statusCode(), response.body());
  }
}
