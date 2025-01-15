package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nullable;
import javax.mail.Message;

public interface EmailInjectionModule extends IastModule {
  void onSendEmail(@Nullable Message message);
}
