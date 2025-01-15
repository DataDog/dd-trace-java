package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.EmailInjectionModule;
import javax.annotation.Nullable;
import javax.mail.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmailInjectionModuleImpl extends SinkModuleBase implements EmailInjectionModule {

  private static final Logger LOGGER = LoggerFactory.getLogger(EmailInjectionModule.class);

  public EmailInjectionModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onSendEmail(@Nullable final Message message) {
    if (message == null) {
      return;
    }
    checkInjection(VulnerabilityType.EMAIL_HTML_INJECTION, message);
  }
}
