import org.apache.hc.core5.http.message.BasicHttpRequest

class HttpUriRequest extends BasicHttpRequest {

  private final String methodName

  HttpUriRequest(final String methodName, final URI uri) {
    super(methodName, uri)
    this.methodName = methodName
  }

  @Override
  String getMethod() {
    return methodName
  }
}
