package datadog.trace.instrumentation.pekkohttp.iast

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.javadsl.ConnectHttp
import org.apache.pekko.http.javadsl.Http
import org.apache.pekko.http.javadsl.ServerBinding
import org.apache.pekko.http.javadsl.marshallers.jackson.Jackson
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.model.HttpRequest as sHttpRequest
import org.apache.pekko.http.javadsl.model.headers.Cookie
import org.apache.pekko.http.javadsl.server.AllDirectives
import org.apache.pekko.http.javadsl.server.Route
import org.apache.pekko.http.javadsl.unmarshalling.Unmarshaller
import org.apache.pekko.http.scaladsl.model.HttpResponse
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.model.headers.HttpCookiePair
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller$
import org.apache.pekko.stream.ActorMaterializer
import org.apache.pekko.stream.javadsl.Flow
import com.datadog.iast.test.TaintMarkerHelpers
import foo.bar.WithInstrumentedCallSites
import scala.collection.immutable.Seq

import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static org.apache.pekko.http.javadsl.model.Uri.RELAXED
import static org.apache.pekko.http.javadsl.server.PathMatchers.segment
import static com.datadog.iast.test.TaintMarkerHelpers.t
import static scala.collection.JavaConverters.mapAsJavaMapConverter
import static scala.collection.JavaConverters.seqAsJavaListConverter

class PekkoIastTestWebServer extends AllDirectives implements Closeable {

  int port
  Closure stop

  static void main(String[] args) {
    def webServer = new PekkoIastTestWebServer()
    webServer.start()
    System.in.read()
    webServer.stop()
  }

  void start() {
    ActorSystem system = ActorSystem.create('pekko-http-server')
    ActorMaterializer materializer = ActorMaterializer.create(system)

    Route routes = pathPrefix('iast') {
      route(
      path(segment('path').slash(segment())) {
        var1 ->
        get {
          complete("IAST: ${t(var1)}")
        }
      },
      path('query') {
        parameterOptional('var') {
          var ->
          get {
            complete("IAST: ${t(var.orElse('<empty>'))}")
          }
        }
      },
      path('query_multival') {
        parameterList('var') {
          var ->
          get {
            complete("IAST: ${var.collect { t(it) }}")
          }
        }
      },
      path('all_query') {
        parameterMultiMap {
          params ->
          get {
            def m = params.entrySet().collectEntries {
              e ->
              [t(e.key), e.value.collect {
                  t(it)
                }]
            }
            complete("IAST: $m")
          }
        }
      },
      path('all_query_simple_map') {
        parameterMap {
          params ->
          get {
            def m = params.entrySet().collectEntries {
              e ->
              [t(e.key), t(e.value)]
            }
            complete("IAST: $m")
          }
        }
      },
      path('all_query_list') {
        parameterList {
          params ->
          get {
            def m = params.collect {
              e ->
              [t(e.key), t(e.value)]
            }
            complete("IAST: $m")
          }
        }
      },
      path('header') {
        optionalHeaderValueByName('x-my-header', header ->
        complete("IAST: ${t(header.orElse('<no header>'))}"))
      },
      path('all_headers') {
        extractRequest {
          request ->
          def map = WithInstrumentedCallSites.headersToMap(request)
          complete("IAST: $map")
        }
      },
      path('all_headers_req_ctx') {
        extractRequestContext {
          reqCtx ->
          def request = reqCtx.request
          def map = WithInstrumentedCallSites.headersToMap(request)
          complete("IAST: $map")
        }
      },
      path('cookie') {
        cookie('var1') {
          cookie ->
          complete("IAST: ${t(cookie.value())}")
        }
      },
      path('cookie_optional') {
        optionalCookie('var1') {
          cookie ->
          complete(cookie.present ? "IAST: ${t(cookie.get().value())}" : "<no var1 cookie present>")
        }
      },
      path('all_cookies') {
        extractRequest {
          req ->
          Cookie cookieHeader = req.headers.find {
            it.is('cookie') // Not Groovy "is"
          }
          def pairList = cookieHeader.cookies.collect {
            [t(it.name()), t(it.value())]
          }
          complete("IAST: $pairList")
        }
      },
      path('all_cookies_scala') {
        extractRequest {
          sHttpRequest req ->
          Seq<HttpCookiePair> cookies = req.cookies()
          def pairList = cookies.iterator().toIterable().collect({
            [t(it.name()), t(it.value())]
          } as Closure)
          complete("IAST: $pairList")
        }
      },
      path('json') {
        post {
          entity(Jackson.unmarshaller(Map)) {
            map ->
            def mmap = map.collectEntries {
              e ->
              [t(e.key), e.value instanceof List ? e.value.collect {
                  t(it)
                } : t(e.value)]
            }
            complete("IAST: $mmap")
          }
        }
      },
      path('json_mufeu') {
        post {
          // this unmarshaller transforms the input before passing it to the
          // inner unmarshaller (rather than transforming the output)
          request(Unmarshaller$.MODULE$.messageUnmarshallerFromEntityUnmarshaller(
          Jackson.unmarshaller(Map) as org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller) as Unmarshaller) {
            Map map ->
            def mmap = map.collectEntries {
              e ->
              [t(e.key), e.value instanceof List ? e.value.collect {
                  t(it)
                } : t(e.value)]
            }
            complete("IAST: $mmap")
          }
        }
      },
      path('uri') {
        extractUri {
          uri ->
          get {
            def map1 = uri.query(StandardCharsets.UTF_8, RELAXED).toMultiMap()
            def tmmap = map1.collectEntries {
              [t(it.key), it.value.collect {
                  t(it)
                }]
            }
            complete(
            // path and pathSegments are not actually tainted
            // we only care about the query string
            """
            Path: ${t(uri.path())}
            Path Segments: ${t(uri.pathSegments())}
            Query String: ${t(uri.queryString(StandardCharsets.UTF_8).get())}
            Raw Query String ${t(uri.rawQueryString().get())}
            Query as MultiMap: ${tmmap}
            """.stripIndent()
            )
          }
        }
      },
      path('form_single') {
        post {
          formFieldOptional('var') {
            complete("IAST: ${t(it.orElse("<no value>"))}")
          }
        }
      },
      path('form_map') {
        post {
          formFieldMap {
            m ->
            def tm = m.collectEntries {
              [t(it.key), t(it.value)]
            }
            complete("IAST: ${tm}")
          }
        }
      },
      path('form_multi_map') {
        post {
          formFieldMultiMap {
            m ->
            def tm = m.collectEntries {
              e ->
              [t(e.key), e.value.collect(TaintMarkerHelpers.&t)]
            }
            complete("IAST: ${tm}")
          }
        }
      },
      path('form_list') {
        post {
          formFieldList {
            l ->
            def tm = l.collect {
              e ->
              [t(e.key), t(e.value)]
            }
            complete("IAST: ${tm}")
          }
        }
      },
      path('form_urlencoded_only') {
        post {
          def unmarshaller
          if (Unmarshaller.respondsTo('entityToUrlEncodedFormData')) {
            // 10.0
            unmarshaller = Unmarshaller.entityToUrlEncodedFormData()
          } else {
            // 10.1
            unmarshaller = Unmarshaller$.MODULE$.defaultUrlEncodedFormDataUnmarshaller()
          }
          entity(unmarshaller) {
            formData ->
            Uri.Query q = formData.fields()
            def m = mapAsJavaMapConverter(q.toMultiMap()).asJava()
            def tm = m.collectEntries {
              e ->
              [t(e.key), seqAsJavaListConverter(e.value).asJava().collect(TaintMarkerHelpers.&t)]
            }
            complete("IAST: ${tm}")
          }
        }
      },
      )
    }

    Flow<HttpRequest, HttpResponse, NotUsed> flow = routes.flow(system, materializer)

    CompletionStage<ServerBinding> binding
    binding = Http.get(system)
    .bindAndHandle(flow, ConnectHttp.toHost('localhost', 0), materializer)
    def latch = new CountDownLatch(1)
    binding.thenAccept {
      serverBinding ->
      port = serverBinding.localAddress().getPort()
      System.out.println('Server started on port ' + port)
      latch.countDown()
    }

    stop = {
      binding
      .thenCompose(ServerBinding::unbind)
      .thenAccept {
        system.terminate()
      }
    }

    if (!latch.await(15, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Server didn't start (apparently) in 15 seconds")
    }
  }

  void stop() {
    stop.call()
  }

  @Override
  void close() {
    stop()
  }
}
