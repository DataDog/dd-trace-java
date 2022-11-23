package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface LdapInjectionModule extends IastModule {

  void onDirContextSearch(
      @Nullable String name, @Nonnull String filterExpr, @Nullable Object[] filterArgs);
}
