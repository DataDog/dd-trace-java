package datadog.trace.instrumentation.jdbc;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;

public class SQLCommentInjectorAdaptor implements AgentPropagation.Setter<SQLCommenter> {

  public static final SQLCommentInjectorAdaptor SETTER = new SQLCommentInjectorAdaptor();
  private static final String SAMPLING_PRIORITY = "sampling_priority";

  @Override
  public void set(SQLCommenter carrier, String key, String value) {
    // the only time this is called, is post forcing a sampling decision,
    // so that we can set the proper priority on the sql comment
    if (key.equals(SAMPLING_PRIORITY)) {
      carrier.setSamplingPriority(Integer.valueOf(value));
    }
  }
}
