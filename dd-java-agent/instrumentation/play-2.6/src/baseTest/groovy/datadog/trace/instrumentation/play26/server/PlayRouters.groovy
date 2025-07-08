package datadog.trace.instrumentation.play26.server

import com.fasterxml.jackson.databind.ObjectMapper
import datadog.appsec.api.blocking.Blocking
import datadog.trace.agent.test.base.HttpServerTest
import groovy.transform.CompileStatic
import play.BuiltInComponents
import play.api.libs.json.JsValue
import play.api.mvc.AnyContent
import play.libs.concurrent.HttpExecution
import play.mvc.Http
import play.mvc.Result
import play.mvc.Results
import play.routing.Router
import play.routing.RoutingDsl
import scala.collection.JavaConverters
import scala.concurrent.ExecutionContextExecutor
import scala.xml.NodeSeq

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.function.Function
import java.util.function.Supplier

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_JSON
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_XML
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.USER_BLOCK
import static datadog.trace.agent.test.base.HttpServerTest.controller
import static java.lang.Class.forName

class PlayRouters {
  static Router sync(BuiltInComponents components) {
    try {
      forName("datadog.trace.instrumentation.play26.server.latestdep.PlayRouters").sync components
    } catch (ClassNotFoundException cnf) {
      sync26(components)
    }
  }

  static Router async(ExecutorService executor, BuiltInComponents components) {
    try {
      forName("datadog.trace.instrumentation.play26.server.latestdep.PlayRouters").async executor, components
    } catch (ClassNotFoundException cnf) {
      async26(executor, components)
    }
  }

  private static Router sync26(BuiltInComponents components) {
    RoutingDsl.fromComponents(components)
      .GET(SUCCESS.path).routeTo({
        controller(SUCCESS) {
          Results.status(SUCCESS.status, SUCCESS.body)
        }
      } as Supplier)
      .GET(FORWARDED.path).routeTo({
        controller(FORWARDED) {
          Results.status(FORWARDED.status, FORWARDED.body)
        }
      } as Supplier)
      .GET(QUERY_PARAM.path).routeTo({
        controller(QUERY_PARAM) {
          Results.status(QUERY_PARAM.status, QUERY_PARAM.body)
        }
      } as Supplier)
      .GET(QUERY_ENCODED_QUERY.path).routeTo({
        controller(QUERY_ENCODED_QUERY) {
          Results.status(QUERY_ENCODED_QUERY.status, QUERY_ENCODED_QUERY.body)
        }
      } as Supplier)
      .GET(QUERY_ENCODED_BOTH.rawPath).routeTo({
        controller(QUERY_ENCODED_BOTH) {
          Results.status(QUERY_ENCODED_BOTH.status, QUERY_ENCODED_BOTH.body).
            withHeader(HttpServerTest.IG_RESPONSE_HEADER, HttpServerTest.IG_RESPONSE_HEADER_VALUE)
        }
      } as Supplier)
      .GET(REDIRECT.path).routeTo({
        controller(REDIRECT) {
          Results.found(REDIRECT.body)
        }
      } as Supplier)
      .GET(ERROR.path).routeTo({
        controller(ERROR) {
          Results.status(ERROR.status, ERROR.body)
        }
      } as Supplier)
      .GET(EXCEPTION.path).routeTo({
        controller(EXCEPTION) {
          throw new RuntimeException(EXCEPTION.body)
        }
      } as Supplier)
      .GET(CUSTOM_EXCEPTION.path).routeAsync({
        controller(CUSTOM_EXCEPTION) {
          throw new TestHttpErrorHandler.CustomRuntimeException(CUSTOM_EXCEPTION.body)
        }
      } as Supplier)
      .GET("/path/:id/param").routeTo(PathParamSyncFunction.INSTANCE)
      .GET(USER_BLOCK.path).routeTo({
        controller(USER_BLOCK) {
          controller(USER_BLOCK) {
            Blocking.forUser('user-to-block').blockIfMatch()
            Results.status(200, "should never be reached")
          }
        }
      } as Supplier)
      .POST(CREATED.path).routeTo({
        ->
        controller(CREATED) {
          def body = body().asText().get()
          Results.status(CREATED.status, "created: $body")
        }
      } as Supplier)
      .POST(BODY_URLENCODED.path).routeTo({
        ->
        controller(BODY_URLENCODED) {
          def body = body().asFormUrlEncoded().get()
          def javaMap = JavaConverters.mapAsJavaMapConverter(body).asJava()
          def res = javaMap.collectEntries {
            [it.key, JavaConverters.asJavaCollectionConverter(it.value).asJavaCollection()]
          }
          Results.status(BODY_URLENCODED.status, res as String)
        }
      } as Supplier)
      .POST(BODY_MULTIPART.path).routeTo({
        ->
        controller(BODY_MULTIPART) {
          def body = body().asMultipartFormData().get().asFormUrlEncoded()
          def javaMap = JavaConverters.mapAsJavaMapConverter(body).asJava()
          def res = javaMap.collectEntries {
            [it.key, JavaConverters.asJavaCollectionConverter(it.value).asJavaCollection()]
          }
          Results.status(BODY_MULTIPART.status, res as String)
        }
      } as Supplier)
      .POST(BODY_JSON.path).routeTo({
        ->
        controller(BODY_JSON) {
          JsValue json = body().asJson().get()
          Results.status(BODY_JSON.status, new ObjectMapper().readTree(json.toString()))
        }
      } as Supplier)
      .POST(BODY_XML.path).routeTo({
        ->
        controller(BODY_XML) {
          NodeSeq node = body().asXml().get()
          Results.status(BODY_XML.status, node.toString())
        }
      } as Supplier)
      .build()
  }

  static Router async26(ExecutorService executor, BuiltInComponents components) {
    ExecutionContextExecutor execContext = HttpExecution.fromThread(executor)
    RoutingDsl.fromComponents(components)
      .GET(SUCCESS.getPath()).routeAsync({
        CompletableFuture.supplyAsync({
          controller(SUCCESS) {
            Results.status(SUCCESS.getStatus(), SUCCESS.getBody())
          }
        }, execContext)
      } as Supplier)
      .GET(FORWARDED.getPath()).routeAsync({
        CompletableFuture.supplyAsync({
          controller(FORWARDED) {
            Results.status(FORWARDED.getStatus(), FORWARDED.getBody()) // cheating
          }
        }, execContext)
      } as Supplier)
      .GET(QUERY_PARAM.getPath()).routeAsync({
        CompletableFuture.supplyAsync({
          controller(QUERY_PARAM) {
            Results.status(QUERY_PARAM.getStatus(), QUERY_PARAM.getBody()) // cheating
          }
        }, execContext)
      } as Supplier)
      .GET(QUERY_ENCODED_QUERY.getPath()).routeAsync({
        CompletableFuture.supplyAsync({
          controller(QUERY_ENCODED_QUERY) {
            Results.status(QUERY_ENCODED_QUERY.getStatus(), QUERY_ENCODED_QUERY.getBody()) // cheating
          }
        }, execContext)
      } as Supplier)
      .GET(QUERY_ENCODED_BOTH.getRawPath()).routeAsync({
        CompletableFuture.supplyAsync({
          controller(QUERY_ENCODED_BOTH) {
            Results.status(QUERY_ENCODED_BOTH.getStatus(), QUERY_ENCODED_BOTH.getBody()).
              withHeader(HttpServerTest.IG_RESPONSE_HEADER, HttpServerTest.IG_RESPONSE_HEADER_VALUE) // cheating
          }
        }, execContext)
      } as Supplier)
      .GET(REDIRECT.getPath()).routeAsync({
        CompletableFuture.supplyAsync({
          controller(REDIRECT) {
            Results.found(REDIRECT.getBody())
          }
        }, execContext)
      } as Supplier)
      .GET(ERROR.getPath()).routeAsync({
        CompletableFuture.supplyAsync({
          controller(ERROR) {
            Results.status(ERROR.getStatus(), ERROR.getBody())
          }
        }, execContext)
      } as Supplier)
      .GET(EXCEPTION.getPath()).routeAsync({
        CompletableFuture.supplyAsync({
          controller(EXCEPTION) {
            throw new RuntimeException(EXCEPTION.getBody())
          }
        }, execContext)
      } as Supplier)
      .GET(CUSTOM_EXCEPTION.path).routeAsync({
        CompletableFuture.supplyAsync({
          controller(CUSTOM_EXCEPTION) {
            throw new TestHttpErrorHandler.CustomRuntimeException(CUSTOM_EXCEPTION.body)
          }
        }, execContext)
      } as Supplier)
      .GET("/path/:id/param").routeAsync(new PathParamAsyncFunction(execContext))
      .GET(USER_BLOCK.path).routeAsync({
        CompletableFuture.supplyAsync({
          ->
          controller(USER_BLOCK) {
            controller(USER_BLOCK) {
              Blocking.forUser('user-to-block').blockIfMatch()
              Results.status(200, "should never be reached")
            }
          }
        }, execContext)
      } as Supplier)
      .POST(CREATED.path).routeAsync({
        ->
        def body = body().asText().get()
        CompletableFuture.supplyAsync({
          ->
          controller(CREATED) {
            Results.status(CREATED.status, "created: $body")
          }
        }, execContext)
      } as Supplier)
      .POST(BODY_URLENCODED.path).routeAsync({
        ->
        def body = body().asFormUrlEncoded().get()
        CompletableFuture.supplyAsync({
          ->
          controller(BODY_URLENCODED) {
            def javaMap = JavaConverters.mapAsJavaMapConverter(body).asJava()
            def res = javaMap.collectEntries {
              [it.key, JavaConverters.asJavaCollectionConverter(it.value).asJavaCollection()]
            }
            Results.status(BODY_URLENCODED.status, res as String)
          }
        }, execContext)
      } as Supplier)
      .POST(BODY_MULTIPART.path).routeAsync({
        ->
        def body = body().asMultipartFormData().get().asFormUrlEncoded()
        CompletableFuture.supplyAsync({
          ->
          controller(BODY_MULTIPART) {
            def javaMap = JavaConverters.mapAsJavaMapConverter(body).asJava()
            def res = javaMap.collectEntries {
              [it.key, JavaConverters.asJavaCollectionConverter(it.value).asJavaCollection()]
            }
            Results.status(BODY_MULTIPART.status, res as String)
          }
        }, execContext)
      } as Supplier)
      .POST(BODY_JSON.path).routeAsync({
        ->
        JsValue json = body().asJson().get()
        CompletableFuture.supplyAsync({
          ->
          controller(BODY_JSON) {
            Results.status(BODY_JSON.status, new ObjectMapper().readTree(json.toString()))
          }
        }, execContext)
      } as Supplier)
      .POST(BODY_XML.path).routeAsync({
        ->
        NodeSeq node = body().asXml().get()
        CompletableFuture.supplyAsync({
          ->
          controller(BODY_XML) {
            Results.status(BODY_XML.status, node.toString())
          }
        }, execContext)
      } as Supplier)
      .build()
  }

  private static AnyContent body() {
    Http.Context.current()._requestHeader().body
  }

  @CompileStatic
  static enum PathParamSyncFunction implements Function<Integer, Result> {
    INSTANCE

    @Override
    Result apply(Integer i) {
      controller(PATH_PARAM) {
        Results.ok(i as String)
      }
    }
  }

  @CompileStatic
  static class PathParamAsyncFunction implements Function<Integer, CompletionStage<Result>> {
    private final Executor executor

    PathParamAsyncFunction(Executor executor) {
      this.executor = executor
    }

    @Override
    CompletionStage<Result> apply(Integer i) {
      CompletableFuture.supplyAsync({
        controller(PATH_PARAM) {
          Results.ok(i as String)
        }
      }, executor)
    }
  }
}
