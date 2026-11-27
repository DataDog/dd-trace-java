package datadog.trace.instrumentation.gson;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class GsonDecorator extends BaseDecorator {
  public static final GsonDecorator DECORATE = new GsonDecorator();

  public static final CharSequence GSON_COMPONENT = UTF8BytesString.create("gson");
  public static final CharSequence JSON_TYPE = UTF8BytesString.create("json");
  public static final CharSequence GSON_TO_JSON = UTF8BytesString.create("gson.toJson");
  public static final CharSequence GSON_FROM_JSON = UTF8BytesString.create("gson.fromJson");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"gson"};
  }

  @Override
  protected CharSequence spanType() {
    return JSON_TYPE;
  }

  @Override
  protected CharSequence component() {
    return GSON_COMPONENT;
  }

  @Override
  protected void doAfterStart(AgentSpan span) {
    super.doAfterStart(span);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_INTERNAL);
  }
}
