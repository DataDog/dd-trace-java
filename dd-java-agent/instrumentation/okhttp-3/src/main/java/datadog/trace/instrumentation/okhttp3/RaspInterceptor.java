package datadog.trace.instrumentation.okhttp3;

import datadog.trace.instrumentation.appsec.rasp.modules.NetworkConnectionModule;
import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class RaspInterceptor implements Interceptor {

  @Override
  public Response intercept(final Chain chain) throws IOException {
    final Request request = chain.request();
    NetworkConnectionModule.INSTANCE.onNetworkConnection(request.url().toString());
    return chain.proceed(request);
  }
}
