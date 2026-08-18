package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.service.AgentRulesService;
import ai.xdev.aisdlc.service.AgentRulesService.AgentRulesView;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What an AI coding agent is allowed to assume, in one request.
 *
 * <p>The agent runs on a developer's machine, inside whichever assistant they use. It needs the active constitution,
 * the active policies, the pinned Spec Kit versions, and where the project's documentation lives. Those already exist
 * as four endpoints; combining them client-side would let two machines running two client versions disagree about the
 * rules, which makes them not rules. The server composes the bundle so there is one answer.
 *
 * <p>Two representations of the same bundle: JSON for a client that will render it, and Markdown for a client that
 * pastes it into a system prompt. The Markdown is produced here rather than in the client, because a client that
 * formats the rules can also quietly soften them.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent-rules")
public class AgentRulesController {
  private final AgentRulesService rules;

  public AgentRulesController(AgentRulesService rules) { this.rules = rules; }

  @GetMapping
  AgentRulesView rules(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) {
    rules.requireProject(projectId);
    return rules.rulesFor(projectId, jwt.getSubject());
  }

  /** The same bundle as the text an agent is actually given. */
  @GetMapping(path = "/markdown", produces = MediaType.TEXT_PLAIN_VALUE)
  String markdown(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) {
    rules.requireProject(projectId);
    return rules.rulesAsMarkdown(projectId, jwt.getSubject());
  }
}
