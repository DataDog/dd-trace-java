package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import java.io.Reader;
import javax.annotation.Nonnull;

public interface CodeInjectionModule extends IastModule {

  void onEval(@Nonnull Reader reader);

  void onEval(@Nonnull String string);
}
