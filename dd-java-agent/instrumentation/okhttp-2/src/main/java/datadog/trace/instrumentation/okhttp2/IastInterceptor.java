package datadog.trace.instrumentation.okhttp2;

import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import java.io.IOException;

public class IastInterceptor implements Interceptor {

  @Override
  public Response intercept(final Chain chain) throws IOException {
    final Request request = chain.request();
    //    final SsrfModule module = InstrumentationBridge.SSRF;
    //    if (module != null) {
    //      try {
    //        module.onURLConnection(request.url());
    //      } catch (final Throwable e) {
    //        module.onUnexpectedException("Error handling SSRF connection", e);
    //      }
    //    }
    return chain.proceed(request);
  }
}
