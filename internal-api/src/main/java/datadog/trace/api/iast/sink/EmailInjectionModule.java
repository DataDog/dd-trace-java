package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nullable;

public interface EmailInjectionModule extends IastModule {
  void onSendEmail(@Nullable String messageContent);

  void taint(String content);

  void taint(Object content);
}
