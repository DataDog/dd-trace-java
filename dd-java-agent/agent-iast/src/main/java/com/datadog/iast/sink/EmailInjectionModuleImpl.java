package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.EmailInjectionModule;
import javax.annotation.Nullable;

public class EmailInjectionModuleImpl extends SinkModuleBase implements EmailInjectionModule {
  public EmailInjectionModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onSendEmail(@Nullable final Object messageContent) {
    if (messageContent == null) {
      return;
    }
    checkInjection(VulnerabilityType.EMAIL_HTML_INJECTION, messageContent);
  }
}
