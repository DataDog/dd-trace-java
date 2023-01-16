package datadog.trace.api.iast.propagation;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface JacksonModule extends IastModule {

  void onJsonFactoryCreateParser(@Nullable Object input, @Nullable Object jsonParser);

  void onJsonFactoryCreateParser(@Nullable String content, @Nullable Object jsonParser);

  void onJsonParserGetString(@Nonnull Object jsonParser, @Nullable String result);
}
