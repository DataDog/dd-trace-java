package datadog.trace.core.propagation;

import static datadog.trace.core.propagation.PropagationUtils.TRACE_PARENT_KEY;
import static datadog.trace.core.propagation.PropagationUtils.traceParent;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.core.DDSpanContext;

public class SqlCommentInjector {

  public static final HttpCodec.Injector SQL_INJECTOR = new SqlCommentInjector.Injector();

  private SqlCommentInjector() {}

  private static class Injector implements HttpCodec.Injector {

    public Injector() {}

    @Override
    public <C> void inject(DDSpanContext context, C carrier, AgentPropagation.Setter<C> setter) {
      setter.set(carrier, TRACE_PARENT_KEY, traceParent(context).toString());
    }
  }
}
