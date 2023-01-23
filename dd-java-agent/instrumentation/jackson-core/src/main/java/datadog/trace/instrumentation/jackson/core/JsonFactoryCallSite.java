package datadog.trace.instrumentation.jackson.core;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.JacksonModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@CallSite(spi = IastAdvice.class)
public class JsonFactoryCallSite {

  @CallSite.After(
      "com.fasterxml.jackson.core.JsonParser com.fasterxml.jackson.core.JsonFactory.createParser(java.io.InputStream)")
  public static JsonParser afterCreate(
      @CallSite.This @Nonnull final JsonFactory self,
      @CallSite.Argument @Nullable final Object input,
      @CallSite.Return @Nullable final JsonParser jsonParser) {
    final JacksonModule module = InstrumentationBridge.JACKSON;
    if (module != null) {
      try {
        module.onJsonFactoryCreateParser(input, jsonParser);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterCreate threw", e);
      }
    }
    return jsonParser;
  }

  @CallSite.After(
      "com.fasterxml.jackson.core.JsonParser com.fasterxml.jackson.core.JsonFactory.createParser(java.lang.String)")
  public static JsonParser afterCreate(
      @CallSite.This @Nonnull final JsonFactory self,
      @CallSite.Argument @Nullable final String content,
      @CallSite.Return @Nullable final JsonParser jsonParser) {
    final JacksonModule module = InstrumentationBridge.JACKSON;
    if (module != null) {
      try {
        module.onJsonFactoryCreateParser(content, jsonParser);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterCreate threw", e);
      }
    }
    return jsonParser;
  }
}
