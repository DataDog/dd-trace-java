package datadog.trace.instrumentation.graalvmjs;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import org.graalvm.polyglot.Source;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

public class GraalvmjsDecorator extends BaseDecorator {
  public static final GraalvmjsDecorator DECORATOR = new GraalvmjsDecorator();

  public static final String INSTRUMENTATION = "graalvmjs";

  @Override
  protected String[] instrumentationNames() {
    return new String[]{INSTRUMENTATION};
  }

  @Override
  protected CharSequence spanType() {
    return INSTRUMENTATION;
  }

  @Override
  protected CharSequence component() {
    return INSTRUMENTATION;
  }

  public AgentSpan createSpan(String method,Source source) {
    AgentSpan span = startSpan(INSTRUMENTATION);
    afterStart(span);
    span.setResourceName(method+"/"+source.getName());
    span.setTag("script_language",source.getLanguage());
    span.setTag("script",source.getCharacters());
    return span;
  }

}
