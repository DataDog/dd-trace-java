package datadog.trace.instrumentation.javax.script;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class JavaxScriptDecorator extends BaseDecorator {
  public static final CharSequence COMPONENT = UTF8BytesString.create("javax-script");

  public static final JavaxScriptDecorator DECORATE = new JavaxScriptDecorator();

  public static final UTF8BytesString OPERATION_NAME = UTF8BytesString.create("javax-script");

  @Override
  protected String[] instrumentationNames() {
    return new String[]{"javax-script"};
  }

  @Override
  protected CharSequence spanType() {
    return UTF8BytesString.create("script");
  }

  @Override
  protected CharSequence component() {
    return COMPONENT;
  }
}
