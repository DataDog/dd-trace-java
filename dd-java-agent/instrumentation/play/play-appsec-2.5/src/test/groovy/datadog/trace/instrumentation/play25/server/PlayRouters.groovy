package datadog.trace.instrumentation.play25.server

import com.fasterxml.jackson.databind.JsonNode
import datadog.appsec.api.blocking.Blocking
import datadog.trace.agent.test.base.HttpServerTest
import groovy.transform.CompileStatic
import play.api.mvc.RequestHeader
import play.mvc.Http
import play.mvc.Result
import play.mvc.Results
import play.routing.Router
import play.routing.RoutingDsl
import scala.Option
import scala.collection.Seq

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.function.Function
import java.util.function.Supplier

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_JSON
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_HERE
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.USER_BLOCK
import static datadog.trace.agent.test.base.HttpServerTest.controller

class PlayRouters {
  static Router sync() {
    new RoutingDsl()
      .GET(SUCCESS.path).routeTo({
        controller(SUCCESS) {
          Results.ok(SUCCESS.body)
        }
      } as Supplier)
      .GET(FORWARDED.path).routeTo({
        controller(FORWARDED) {
          Option<String> headerValue = requestHeader.headers().get('x-forwarded-for')
          Results.ok(headerValue.empty ? 'x-forwarded-for header not present' : headerValue.get())
        }
      } as Supplier)
      .GET(QUERY_PARAM.path).routeTo({
        controller(QUERY_PARAM) {
          Option<Seq<String>> some = requestHeader.queryString().get('some')
          Results.ok("some=${some.empty ? '(null)' : some.get().apply(0)}")
        }
      } as Supplier)
      .GET(QUERY_ENCODED_QUERY.path).routeTo({
        controller(QUERY_ENCODED_QUERY) {
          Option<Seq<String>> some = requestHeader.queryString().get('some')
          Results.ok("some=${some.empty ? '(null)' : some.get().apply(0)}")
        }
      } as Supplier)
      .GET(QUERY_ENCODED_BOTH.rawPath).routeTo({
        controller(QUERY_ENCODED_BOTH) {
          Option<Seq<String>> some = requestHeader.queryString().get('some')
          Results.ok("some=${some.empty ? '(null)' : some.get().apply(0)}").
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
      .GET(NOT_HERE.path).routeTo({
        controller(NOT_HERE) {
          Results.notFound(NOT_HERE.body)
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
          String body = body().asText()
          Results.status(CREATED.status, "created: $body")
        }
      } as Supplier)
      .POST(BODY_URLENCODED.path).routeTo({
        ->
        controller(BODY_URLENCODED) {
          Map<String, String[]> body = body().asFormUrlEncoded()
          Results.status(BODY_URLENCODED.status, body as String)
        }
      } as Supplier)
      .POST(BODY_MULTIPART.path).routeTo({
        ->
        controller(BODY_MULTIPART) {
          Map<String, String[]> body = body().asMultipartFormData().asFormUrlEncoded()
          Results.status(BODY_MULTIPART.status, body as String)
        }
      } as Supplier)
      .POST(BODY_JSON.path).routeTo({
        ->
        JsonNode json = body().asJson()
        controller(BODY_JSON) {
          Results.status(BODY_JSON.status, json)
        }
      } as Supplier)
      .build()
  }

  @CompileStatic
  static Router async(Executor execContext) {
    new RoutingDsl()
      .GET(SUCCESS.getPath()).routeAsync({
        CompletableFuture.supplyAsync({
          controller(SUCCESS) {
            Results.ok(SUCCESS.body)
          }
        }, execContext)
      } as Supplier)
      .GET(FORWARDED.getPath()).routeAsync({
        Option<String> header = requestHeader.headers().get('x-forwarded-for')
        CompletableFuture.supplyAsync({
          controller(FORWARDED) {
            Results.ok(header.empty ? '(null)' : header.get())
          }
        }, execContext)
      } as Supplier)
      .GET(QUERY_PARAM.getPath()).routeAsync({
        Option<Seq<String>> some = requestHeader.queryString().get('some')
        CompletableFuture.supplyAsync({
          controller(QUERY_PARAM) {
            Results.ok("some=${some.empty ? '(null)' : some.get().apply(0)}")
          }
        }, execContext)
      } as Supplier)
      .GET(QUERY_ENCODED_QUERY.getPath()).routeAsync({
        Option<Seq<String>> some = requestHeader.queryString().get('some')
        CompletableFuture.supplyAsync({
          controller(QUERY_ENCODED_QUERY) {
            Results.ok("some=${some.empty ? '(null)' : some.get().apply(0)}")
          }
        }, execContext)
      } as Supplier)
      .GET(QUERY_ENCODED_BOTH.getRawPath()).routeAsync({
        Option<Seq<String>> some = requestHeader.queryString().get('some')
        CompletableFuture.supplyAsync({
          controller(QUERY_ENCODED_BOTH) {
            Results.ok("some=${some.empty ? '(null)' : some.get().apply(0)}").
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
            Blocking.forUser('user-to-block').blockIfMatch()
            Results.status(200, "should never be reached")
          }
        }, execContext)
      } as Supplier)
      .POST(CREATED.path).routeAsync({
        ->
        String body = body().asText()
        CompletableFuture.supplyAsync({
          ->
          controller(CREATED) {
            Results.status(CREATED.status, "created: $body")
          }
        }, execContext)
      } as Supplier)
      .POST(BODY_URLENCODED.path).routeAsync({
        ->
        Map<String, String[]> body = body().asFormUrlEncoded()
        CompletableFuture.supplyAsync({
          ->
          controller(BODY_URLENCODED) {
            Results.status(BODY_URLENCODED.status, body as String)
          }
        }, execContext)
      } as Supplier)
      .POST(BODY_MULTIPART.path).routeAsync({
        ->
        Map<String, String[]> body = body().asMultipartFormData().asFormUrlEncoded()
        CompletableFuture.supplyAsync({
          ->
          controller(BODY_MULTIPART) {
            Results.status(BODY_MULTIPART.status, body as String)
          }
        }, execContext)
      } as Supplier)
      .POST(BODY_JSON.path).routeAsync({
        ->
        JsonNode json = body().asJson()
        CompletableFuture.supplyAsync({
          ->
          controller(BODY_JSON) {
            Results.status(BODY_JSON.status, json)
          }
        }, execContext)
      } as Supplier)
      .build()
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

  private static RequestHeader getRequestHeader() {
    Http.Context.current()._requestHeader()
  }

  private static play.mvc.Http.RequestBody body() {
    Http.Context.current()._requestHeader().body
  }
}
