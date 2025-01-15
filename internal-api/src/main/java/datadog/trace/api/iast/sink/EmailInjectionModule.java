package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nullable;
import javax.mail.internet.MimeMessage;

public interface EmailInjectionModule extends IastModule {
  void onSendEmail(@Nullable MimeMessage body);
}
