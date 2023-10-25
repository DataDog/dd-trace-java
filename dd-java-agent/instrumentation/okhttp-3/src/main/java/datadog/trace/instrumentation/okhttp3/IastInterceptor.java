package datadog.trace.instrumentation.okhttp3;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.SsrfModule;
import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class IastInterceptor implements Interceptor {

  @Override
  public Response intercept(final Chain chain) throws IOException {
    final Request request = chain.request();
    final SsrfModule module = InstrumentationBridge.SSRF;
    if (module != null) {
      try {
        module.onURLConnection(request.url());
      } catch (final Throwable e) {
        module.onUnexpectedException("Error handling SSRF connection", e);
      }
    }
    return chain.proceed(request);
  }
}
