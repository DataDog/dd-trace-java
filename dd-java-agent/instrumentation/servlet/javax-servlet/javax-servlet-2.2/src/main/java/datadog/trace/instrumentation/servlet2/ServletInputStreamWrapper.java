package datadog.trace.instrumentation.servlet2;

import datadog.trace.api.http.StoredByteBody;
import datadog.trace.instrumentation.servlet.AbstractServletInputStreamWrapper;
import javax.servlet.ServletInputStream;

public class ServletInputStreamWrapper extends AbstractServletInputStreamWrapper {
  public ServletInputStreamWrapper(ServletInputStream is, StoredByteBody storedByteBody) {
    super(is, storedByteBody);
  }
}
