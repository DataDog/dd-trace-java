package datadog.trace.instrumentation.okhttp2;

import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import datadog.trace.instrumentation.appsec.rasp.modules.NetworkConnectionModule;
import java.io.IOException;

public class RaspInterceptor implements Interceptor {

  @Override
  public Response intercept(final Chain chain) throws IOException {
    final Request request = chain.request();
    NetworkConnectionModule.INSTANCE.onNetworkConnection(request.url().toString());
    return chain.proceed(request);
  }
}
