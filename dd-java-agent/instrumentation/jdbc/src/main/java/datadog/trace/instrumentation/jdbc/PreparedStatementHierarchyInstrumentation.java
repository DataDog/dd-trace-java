package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class PreparedStatementHierarchyInstrumentation
    extends AbstractPreparedStatementInstrumentation implements Instrumenter.ForTypeHierarchy {
  public PreparedStatementHierarchyInstrumentation() {
    super("jdbc", "jdbcMatcher");
  }

  @Override
  protected boolean defaultEnabled() {
    return Config.get().getJdbcUseHierarchyMatcher() && Config.get().isIntegrationsEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "java.sql.PreparedStatement";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }
}
