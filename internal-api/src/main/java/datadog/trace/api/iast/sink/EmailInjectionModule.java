package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nullable;
import javax.mail.Message;
import javax.mail.internet.MimeMultipart;

public interface EmailInjectionModule extends IastModule {
  void onSendEmail(@Nullable MimeMultipart body);

  void onSendEmail(@Nullable Message message);
}
