package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class DB2PreparedStatementInstrumentation extends AbstractPreparedStatementInstrumentation
    implements Instrumenter.ForTypeHierarchy {
  public DB2PreparedStatementInstrumentation() {
    super("jdbc", "db2");
  }

  @Override
  public String hierarchyMarkerType() {
    return "com.ibm.db2.jcc.DB2PreparedStatement";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }
}
