package datadog.trace.instrumentation.ibmmq;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.MessagingClientDecorator;

public final class IbmMqDecorator extends MessagingClientDecorator {
  public static final CharSequence IBMMQ = UTF8BytesString.create("ibmmq");
  private final String spanKind;
  private final CharSequence spanType;

  public IbmMqDecorator(String resourcePrefix, String spanKind, CharSequence spanType) {
    this.spanKind = spanKind;
    this.spanType = spanType;
  }

  @Override
  protected String service() {
    return "WIP DO NOT COMMIT";
  }

  @Override
  protected CharSequence component() {
    return IBMMQ;
  }

  @Override
  protected CharSequence spanType() {
    return spanType;
  }

  @Override
  protected String spanKind() {
    return spanKind;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"ibmmq"};
  }
}
