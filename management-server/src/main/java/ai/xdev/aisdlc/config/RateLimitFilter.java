package ai.xdev.aisdlc.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** In-memory edge guard. Production replicas should use a distributed Bucket4j backend. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RateLimitFilter extends OncePerRequestFilter {
  private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
  private final long capacity;
  private final Duration refillPeriod;

  public RateLimitFilter(@Value("${aisdlc.rate-limit.capacity:120}") long capacity,
                         @Value("${aisdlc.rate-limit.refill-period:PT1M}") Duration refillPeriod) {
    if (capacity < 1) throw new IllegalArgumentException("Rate limit capacity must be positive");
    this.capacity = capacity;
    this.refillPeriod = refillPeriod;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    String key = request.getRemoteAddr();
    Bucket bucket = buckets.computeIfAbsent(key, ignored -> Bucket.builder().addLimit(Bandwidth.classic(capacity, Refill.greedy(capacity, refillPeriod))).build());
    if (!bucket.tryConsume(1)) {
      response.setStatus(429);
      response.setHeader("Retry-After", String.valueOf(Math.max(1, refillPeriod.toSeconds())));
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      response.getWriter().write("{\"type\":\"https://aisdlc.dev/problems/rate-limit\",\"title\":\"Too many requests\",\"status\":429,\"detail\":\"Rate limit exceeded. Retry after the stated interval.\"}");
      return;
    }
    chain.doFilter(request, response);
  }
}
