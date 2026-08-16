package ai.xdev.aisdlc.service;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** CEL environment with a single dynamic input map and no application host functions. */
@Component
public class PolicyExpressionEngine {
  private static final CelCompiler COMPILER = CelCompilerFactory.standardCelCompilerBuilder().addVar("context", SimpleType.DYN).build();
  private static final CelRuntime RUNTIME = CelRuntimeFactory.plannerRuntimeBuilder().build();
  private final ObjectProvider<ChaosFaultRegistry> chaosFaults;

  public PolicyExpressionEngine() { this(null); }
  @Autowired public PolicyExpressionEngine(ObjectProvider<ChaosFaultRegistry> chaosFaults) { this.chaosFaults = chaosFaults; }

  public void validate(String expression) throws CelValidationException { compile(expression); }
  public Object evaluate(String expression, Map<String, Object> context) throws CelValidationException, CelEvaluationException {
    if (chaosFaults != null) chaosFaults.ifAvailable(registry -> registry.check(ChaosFaultRegistry.Component.POLICY_ENGINE));
    return RUNTIME.createProgram(compile(expression)).eval(Map.of("context", context));
  }
  private CelAbstractSyntaxTree compile(String expression) throws CelValidationException { return COMPILER.compile(expression).getAst(); }
}
