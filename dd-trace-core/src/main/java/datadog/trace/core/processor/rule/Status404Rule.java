package datadog.trace.core.processor.rule;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import datadog.trace.core.processor.TraceProcessor;

/** This span decorator protect against spam on the resource name */
public class Status404Rule implements TraceProcessor.Rule {
  @Override
  public String[] aliases() {
    return new String[] {"Status404Decorator"};
  }

  @Override
  public void processSpan(
      final DDSpan span) {
    final Object httpStatus = span.getTag(Tags.HTTP_STATUS);
    if (!span.context().isResourceNameSet()
        && httpStatus != null
        && (httpStatus.equals(404) || httpStatus.equals("404"))) {
      span.setResourceName("404");
    }
  }
}
