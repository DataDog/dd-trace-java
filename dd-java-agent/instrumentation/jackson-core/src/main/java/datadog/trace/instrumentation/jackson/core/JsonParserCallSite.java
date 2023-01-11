package datadog.trace.instrumentation.jackson.core;

import com.fasterxml.jackson.core.JsonParser;
import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.JacksonModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@CallSite(spi = IastAdvice.class)
public class JsonParserCallSite {

  @CallSite.AfterArray(
      value = {
        @CallSite.After("java.lang.String com.fasterxml.jackson.core.JsonParser.currentName()"),
        @CallSite.After("java.lang.String com.fasterxml.jackson.core.JsonParser.getCurrentName()"),
        @CallSite.After("java.lang.String com.fasterxml.jackson.core.JsonParser.getText()"),
        @CallSite.After(
            "java.lang.String com.fasterxml.jackson.core.JsonParser.getValueAsString()"),
        @CallSite.After(
            "java.lang.String com.fasterxml.jackson.core.JsonParser.getValueAsString(java.lang.String)"),
        @CallSite.After("java.lang.String com.fasterxml.jackson.core.JsonParser.nextFieldName()"),
        @CallSite.After("java.lang.String com.fasterxml.jackson.core.JsonParser.nextTextValue()")
      })
  public static String afterGetString(
      @CallSite.This @Nonnull final JsonParser self,
      @CallSite.Return @Nullable final String result) {
    final JacksonModule module = InstrumentationBridge.JACKSON;
    if (module != null) {
      try {
        module.onJsonParserGetString(self, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterGetString threw", e);
      }
    }
    return result;
  }
}
