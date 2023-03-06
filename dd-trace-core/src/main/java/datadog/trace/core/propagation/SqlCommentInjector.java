package datadog.trace.core.propagation;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.core.DDSpanContext;

public class SqlCommentInjector {

  protected static final String SAMPLING_PRIORITY = "sampling_priority";
  private static final String SAMPLING_PRIORITY_ACCEPT = String.valueOf(1);
  private static final String SAMPLING_PRIORITY_DROP = String.valueOf(0);

  public static final HttpCodec.Injector SQL_INJECTOR = new SqlCommentInjector.Injector();

  private SqlCommentInjector() {}

  private static class Injector implements HttpCodec.Injector {

    public Injector() {}

    @Override
    public <C> void inject(DDSpanContext context, C carrier, AgentPropagation.Setter<C> setter) {
      if (context.lockSamplingPriority()) {
        final String injectedSamplingPriority =
            convertSamplingPriority(context.getSamplingPriority());
        setter.set(carrier, SAMPLING_PRIORITY, injectedSamplingPriority);
      }
    }
  }

  private static String convertSamplingPriority(final int samplingPriority) {
    return samplingPriority > 0 ? SAMPLING_PRIORITY_ACCEPT : SAMPLING_PRIORITY_DROP;
  }
}
