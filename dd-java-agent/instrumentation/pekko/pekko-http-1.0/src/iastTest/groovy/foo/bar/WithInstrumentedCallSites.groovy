package foo.bar

import org.apache.pekko.http.javadsl.model.HttpHeader
import org.apache.pekko.http.javadsl.model.HttpRequest
import groovy.transform.CompileStatic

import static com.datadog.iast.test.TaintMarkerHelpers.t

class WithInstrumentedCallSites {
  @CompileStatic
  static Map headersToMap(HttpRequest request) {
    //    request.headers
    //      .collectEntries({
    //        [t(it.name()), t(it.value())]
    //      })

    def map = [:]
    for (HttpHeader e: request.headers) {
      map[t(e.name())] = t(e.value())
    }
    map
  }
}
