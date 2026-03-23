package datadog.trace.instrumentation.gson;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class GsonDecorator extends BaseDecorator {
  public static final GsonDecorator DECORATE = new GsonDecorator();

  public static final CharSequence GSON = UTF8BytesString.create("gson");
  public static final CharSequence GSON_TO_JSON = UTF8BytesString.create("gson.toJson");
  public static final CharSequence GSON_FROM_JSON = UTF8BytesString.create("gson.fromJson");
  public static final CharSequence JSON_SPAN_TYPE = UTF8BytesString.create("json");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"gson"};
  }

  @Override
  protected CharSequence spanType() {
    return JSON_SPAN_TYPE;
  }

  @Override
  protected CharSequence component() {
    return GSON;
  }
}
