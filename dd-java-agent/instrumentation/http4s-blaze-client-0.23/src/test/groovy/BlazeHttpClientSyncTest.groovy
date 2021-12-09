import spock.lang.Timeout

@Timeout(5)
abstract class BlazeHttpClientSyncTest extends BlazeHttpClientTestBase {
  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    BlazeClientHelper.doSyncRequest(method, uri, headers, callback)
  }
}
