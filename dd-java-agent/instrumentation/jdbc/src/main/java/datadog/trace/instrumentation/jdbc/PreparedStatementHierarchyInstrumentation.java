package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** TODO-hide this instrumentation behind a config variable**/
  @AutoService(Instrumenter.class)
  public class PreparedStatementHierarchyInstrumentation extends AbstractPreparedStatementInstrumentation
      implements Instrumenter.ForTypeHierarchy {
    public PreparedStatementHierarchyInstrumentation() {
      super("jdbc", "jdbcMatcher");
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
