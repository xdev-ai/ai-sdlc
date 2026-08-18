package ai.xdev.aisdlc.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.ui.ExtendedModelMap;

/**
 * Counts the control-plane requests one page view actually makes.
 *
 * <p>The method serving sixteen pages used to issue thirty-one requests on every one of them, so opening a document
 * also fetched SBOMs, agent sessions, provenance records and risk scores. {@link PortalPageDataTest} proves no page
 * lost data it reads; this proves the waste is gone, by counting through the real controller rather than by reasoning
 * about the guards.
 */
class PortalRequestBudgetTest {
  /** Records every path the controller asks for, and answers with empty data. */
  private static final class CountingClient extends ManagementApiClient {
    private final List<String> requested = new ArrayList<>();

    private CountingClient() { super("http://management-server:8081"); }

    @Override public PageData page(String path, String token) { requested.add(path); return PageData.empty(); }
    @Override public ListData list(String path, String token) { requested.add(path); return ListData.empty(); }
    @Override public ObjectData object(String path, String token) { requested.add(path); return ObjectData.empty(); }
    @Override public ObjectData trace(String path, String token) { requested.add(path); return ObjectData.empty(); }
  }

  private static Map<String, Integer> countPerPage() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String view : List.of("overview", "projects", "kits", "validations", "evidence", "traceability",
        "governance", "policy-as-code", "agent-governance", "risk-intelligence", "scm", "supply-chain", "reviews",
        "notifications", "quality", "audit")) {
      CountingClient client = new CountingClient();
      PortalController controller = new PortalController(client, new ReactAssetService());

      OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "test-token",
          Instant.now(), Instant.now().plusSeconds(300));
      OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
      when(authorized.getAccessToken()).thenReturn(accessToken);
      OidcUser user = mock(OidcUser.class);
      when(user.getFullName()).thenReturn("Administrator");

      controller.app(view, UUID.randomUUID(), UUID.randomUUID(), 0, null, null, user, authorized,
          new ExtendedModelMap());
      counts.put(view, client.requested.size());
    }
    return counts;
  }

  @Test void noPageFetchesAnythingLikeThirtyOneDatasets() {
    Map<String, Integer> counts = countPerPage();

    counts.forEach((view, count) ->
        assertTrue(count <= 8, view + " issued " + count + " control-plane requests; it used to be 31 and the point of "
            + "the page-dataset table is that a view fetches only what it renders"));
    int worst = counts.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
    int total = counts.values().stream().mapToInt(Integer::intValue).sum();
    System.out.println("requests per page: " + counts);
    System.out.println("worst page: " + worst + " requests; all sixteen pages together: " + total
        + " (previously 16 x 31 = 496)");
    assertTrue(total < 100, "sixteen page views together issued " + total + " requests");
  }

  /** A page must still fetch what it renders, so a count of one or two would mean the guard went too far. */
  @Test void eachPageStillFetchesItsOwnData() {
    Map<String, Integer> counts = countPerPage();

    // Organizations always, projects when an organization is selected: two before any page-specific data.
    counts.forEach((view, count) ->
        assertTrue(count >= 3, view + " issued only " + count + " requests, which cannot include its own data"));
  }

  @Test void theDocumentationViewIsServedByItsOwnHandlerAndNotThroughThisBudget() {
    CountingClient client = new CountingClient();
    PortalController controller = new PortalController(client, new ReactAssetService());
    OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "t",
        Instant.now(), Instant.now().plusSeconds(300));
    OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
    when(authorized.getAccessToken()).thenReturn(accessToken);
    OidcUser user = mock(OidcUser.class);
    when(user.getFullName()).thenReturn("Administrator");

    controller.knowledge(UUID.randomUUID(), UUID.randomUUID(), null, null, null, null, user, authorized,
        new ExtendedModelMap());

    assertEquals(3, client.requested.size(),
        "the documentation handler should ask for organizations, projects and spaces only: " + client.requested);
  }
}
