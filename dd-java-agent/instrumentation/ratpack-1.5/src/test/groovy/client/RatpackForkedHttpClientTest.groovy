package client


import ratpack.exec.ExecResult

class RatpackForkedHttpClientTest extends RatpackHttpClientTest {

  @Override
  int doRequest(String method, URI uri, List<List<String>> headers, String body, Closure callback) {
    ExecResult<Integer> result = exec.yield {
      def resp = client.request(uri) { spec ->
        spec.method(method)
        spec.headers { headersSpec ->
          headers.each {
            headersSpec.add(it[0], it[1])
          }
        }
      }
      return resp.fork().map {
        callback?.call()
        it.status.code
      }
    }
    return result.value
  }
}
