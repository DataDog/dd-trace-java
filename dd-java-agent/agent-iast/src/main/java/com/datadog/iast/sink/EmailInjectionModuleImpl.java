package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.EmailInjectionModule;
import javax.annotation.Nullable;
import javax.mail.internet.MimeMessage;

public class EmailInjectionModuleImpl extends SinkModuleBase implements EmailInjectionModule {

  public EmailInjectionModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onSendEmail(@Nullable final MimeMessage message) {
    if (message == null) {
      return;
    }

    checkInjection(VulnerabilityType.EMAIL_INJECTION, message);
  }
}
