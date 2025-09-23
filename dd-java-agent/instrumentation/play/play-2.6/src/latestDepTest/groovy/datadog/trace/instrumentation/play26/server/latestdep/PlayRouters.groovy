package datadog.trace.instrumentation.play26.server.latestdep

import com.fasterxml.jackson.databind.JsonNode
import datadog.appsec.api.blocking.Blocking
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.instrumentation.play26.server.TestHttpErrorHandler
import groovy.transform.CompileStatic
import org.w3c.dom.Document
import play.BuiltInComponents
import play.libs.concurrent.ClassLoaderExecution
import play.mvc.Http
import play.mvc.Result
import play.mvc.Results
import play.routing.RequestFunctions
import play.routing.Router
import play.routing.RoutingDsl
import scala.concurrent.ExecutionContextExecutor

import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService

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

// TODO: a lot of this routes don't exercise query/parameter extraction, when they should
class PlayRouters {
  static Router sync(BuiltInComponents components) {
    RoutingDsl.fromComponents(components)
      .GET(SUCCESS.path).routingTo({
        controller(SUCCESS) {
          Results.ok(SUCCESS.body)
        }
      } as RequestFunctions.Params0<Result>)
      .GET(FORWARDED.path).routingTo({ req ->
        controller(FORWARDED) {
          Results.status(FORWARDED.status, req.header('X-Forwarded-For').orElse('(no header)'))
        }
      } as RequestFunctions.Params0<Result>)
      .GET(QUERY_PARAM.path).routingTo({ req ->
        controller(QUERY_PARAM) {
          Results.status(QUERY_PARAM.status, "some=${req.queryString('some').orElse('(null)')}")
        }
      } as RequestFunctions.Params0<Result>)
      .GET(QUERY_ENCODED_QUERY.path).routingTo({ req ->
        controller(QUERY_ENCODED_QUERY) {
          Results.status(QUERY_ENCODED_QUERY.status, "some=${req.queryString('some').orElse('(null)')}")
        }
      } as RequestFunctions.Params0<Result>)
      .GET(QUERY_ENCODED_BOTH.rawPath).routingTo({ req ->
        controller(QUERY_ENCODED_BOTH) {
          Results.status(QUERY_ENCODED_BOTH.status, "some=${req.queryString('some').orElse('(null)')}").
            withHeader(HttpServerTest.IG_RESPONSE_HEADER, HttpServerTest.IG_RESPONSE_HEADER_VALUE)
        }
      } as RequestFunctions.Params0<Result>)
      .GET(REDIRECT.path).routingTo({
        controller(REDIRECT) {
          Results.found(REDIRECT.body)
        }
      } as RequestFunctions.Params0<Result>)
      .GET(ERROR.path).routingTo({
        controller(ERROR) {
          Results.status(ERROR.status, ERROR.body)
        }
      } as RequestFunctions.Params0<Result>)
      .GET(EXCEPTION.path).routingTo({
        controller(EXCEPTION) {
          throw new RuntimeException(EXCEPTION.body)
        }
      } as RequestFunctions.Params0<Result>)
      .GET(CUSTOM_EXCEPTION.path).routingTo({
        controller(CUSTOM_EXCEPTION) {
          throw new TestHttpErrorHandler.CustomRuntimeException(CUSTOM_EXCEPTION.body)
        }
      } as RequestFunctions.Params0<Result>)
      .GET('/path/:id/param').routingTo(PathParamHandlerSync.INSTANCE)
      .GET(USER_BLOCK.path).routingTo({
        controller(USER_BLOCK) {
          Blocking.forUser('user-to-block').blockIfMatch()
          Results.status(200, "should never be reached")
        }
      } as RequestFunctions.Params0<Result>)
      .POST(CREATED.path).routingTo({ Http.Request req ->
        controller(CREATED) {
          String body = req.body().asText()
          Results.created("created: $body")
        }
      } as RequestFunctions.Params0<Result>)
      .POST(BODY_URLENCODED.path).routingTo({ Http.Request req ->
        controller(BODY_URLENCODED) {
          Map<String, String[]> body = req.body()asFormUrlEncoded()
          Results.status(BODY_URLENCODED.status, body as String)
        }
      } as RequestFunctions.Params0<Result>)
      .POST(BODY_MULTIPART.path).routingTo({ Http.Request req ->
        controller(BODY_MULTIPART) {
          Map<String, String[]> body = req.body().asMultipartFormData().asFormUrlEncoded()
          Results.status(BODY_MULTIPART.status, body as String)
        }
      } as RequestFunctions.Params0<Result>)
      .POST(BODY_JSON.path).routingTo({ Http.Request req ->
        controller(BODY_JSON) {
          JsonNode json = req.body().asJson()
          Results.status(BODY_JSON.status, json)
        }
      } as RequestFunctions.Params0<Result>)
      .POST(BODY_XML.path).routingTo({ Http.Request req ->
        controller(BODY_XML) {
          Document xml = req.body().asXml()
          Results.status(BODY_XML.status, documentToString(xml))
        }
      } as RequestFunctions.Params0<Result>)
      .build()
  }

  @CompileStatic
  private static enum PathParamHandlerSync implements RequestFunctions.Params1<Integer, Result> {
    INSTANCE

    @Override
    Result apply(Http.Request request, Integer id) {
      controller(PATH_PARAM) {
        Results.ok(id as String)
      }
    }
  }

  static Router async(ExecutorService executor, BuiltInComponents components) {
    ExecutionContextExecutor execContext = ClassLoaderExecution.fromThread(executor)
    RoutingDsl.fromComponents(components)
      .GET(SUCCESS.path).routingAsync({
        CompletableFuture.supplyAsync({
          controller(SUCCESS) {
            Results.ok(SUCCESS.body)
          }
        }, execContext)
      } as RequestFunctions.Params0<? extends CompletionStage<Result>>)
      .GET(FORWARDED.path).routingAsync({ req ->
        CompletableFuture.supplyAsync({
          controller(FORWARDED) {
            Results.status(FORWARDED.status, req.header('X-Forwarded-For').orElse('(no header)'))
          }
        }, execContext)
      } as RequestFunctions.Params0<? extends CompletionStage<Result>>)
      .GET(QUERY_PARAM.path).routingAsync({ req ->
        CompletableFuture.supplyAsync({
          controller(QUERY_PARAM) {
            Results.status(QUERY_PARAM.status, "some=${req.queryString('some').orElse('(null)')}")
          }
        }, execContext)
      } as RequestFunctions.Params0<? extends CompletionStage<Result>>)
      .GET(QUERY_ENCODED_QUERY.path).routingAsync({ req ->
        CompletableFuture.supplyAsync({
          controller(QUERY_ENCODED_QUERY) {
            Results.status(QUERY_ENCODED_QUERY.status, "some=${req.queryString('some').orElse('(null)')}")
          }
        }, execContext)
      } as RequestFunctions.Params0<? extends CompletionStage<Result>>)
      .GET(QUERY_ENCODED_BOTH.getRawPath()).routingAsync({ req ->
        CompletableFuture.supplyAsync({
          controller(QUERY_ENCODED_BOTH) {
            Results.status(QUERY_ENCODED_BOTH.status, "some=${req.queryString('some').orElse('(null)')}").
              withHeader(HttpServerTest.IG_RESPONSE_HEADER, HttpServerTest.IG_RESPONSE_HEADER_VALUE) // cheating
          }
        }, execContext)
      } as RequestFunctions.Params0<? extends CompletionStage<Result>>)
      .GET(REDIRECT.path).routingAsync({
        CompletableFuture.supplyAsync({
          controller(REDIRECT) {
            Results.found(REDIRECT.body)
          }
        }, execContext)
      } as RequestFunctions.Params0<? extends CompletionStage<Result>>)
      .GET(ERROR.path).routingAsync({
        CompletableFuture.supplyAsync({
          controller(ERROR) {
            Results.status(ERROR.status, ERROR.body)
          }
        }, execContext)
      } as RequestFunctions.Params0<? extends CompletionStage<Result>>)
      .GET(EXCEPTION.path).routingAsync({
        CompletableFuture.supplyAsync({
          controller(EXCEPTION) {
            throw new RuntimeException(EXCEPTION.body)
          }
        }, execContext)
      } as RequestFunctions.Params0<? extends CompletionStage<Result>>)
      .GET(CUSTOM_EXCEPTION.path).routingAsync({
        CompletableFuture.supplyAsync({
          controller(CUSTOM_EXCEPTION) {
            throw new TestHttpErrorHandler.CustomRuntimeException(CUSTOM_EXCEPTION.body)
          }
        }, execContext)
      } as RequestFunctions.Params0<? extends CompletionStage<Result>>)
      .GET('/path/:id/param').routingAsync(PathParamHandlerAsync.INSTANCE)
      .GET(USER_BLOCK.path).routingAsync({
        CompletableFuture.supplyAsync({
          ->
          controller(USER_BLOCK) {
            Blocking.forUser('user-to-block').blockIfMatch()
            Results.status(200, "should never be reached")
          }
        }, execContext)
      } as RequestFunctions.Params0<? extends CompletionStage<Result>>)
      .POST(CREATED.path).routingAsync({ Http.Request req ->
        def body = req.body().asText()
        CompletableFuture.supplyAsync({
          ->
          controller(CREATED) {
            Results.created("created: $body")
          }
        }, execContext)
      } as RequestFunctions.Params0<? extends CompletionStage<Result>>)
      .POST(BODY_URLENCODED.path).routingAsync({ Http.Request req ->
        CompletableFuture.supplyAsync({
          ->
          def body = req.body().asFormUrlEncoded()
          controller(BODY_URLENCODED) {
            Results.status(BODY_URLENCODED.status, body as String)
          }
        }, execContext)
      } as RequestFunctions.Params0<? extends CompletionStage<Result>>)
      .POST(BODY_MULTIPART.path).routingAsync({
        Map<String, String[]> body = it.body().asMultipartFormData().asFormUrlEncoded()
        CompletableFuture.supplyAsync({
          ->
          controller(BODY_MULTIPART) {
            Results.status(BODY_MULTIPART.status, body as String)
          }
        }, execContext)
      } as RequestFunctions.Params0<? extends CompletionStage<Result>>)
      .POST(BODY_JSON.path).routingAsync({ Http.Request req ->
        JsonNode json = req.body().asJson()
        CompletableFuture.supplyAsync({
          ->
          controller(BODY_JSON) {
            Results.status(BODY_JSON.status, json)
          }
        }, execContext)
      } as RequestFunctions.Params0<? extends CompletionStage<Result>>)
      .POST(BODY_XML.path).routingAsync({ Http.Request req ->
        Document xml = req.body().asXml()
        CompletableFuture.supplyAsync({
          ->
          controller(BODY_XML) {
            Results.status(BODY_XML.status, documentToString(xml))
          }
        }, execContext)
      } as RequestFunctions.Params0<? extends CompletionStage<Result>>)
      .build()
  }

  @CompileStatic
  private static enum PathParamHandlerAsync implements RequestFunctions.Params1<Integer, CompletionStage<Result>> {
    INSTANCE

    @Override
    CompletionStage<Result> apply(Http.Request request, Integer id) {
      CompletableFuture.supplyAsync({
        ->
        controller(PATH_PARAM) {
          Results.ok(id as String)
        }
      })
    }
  }

  private static String documentToString(Document doc) {
    StringWriter writer = new StringWriter()
    TransformerFactory.newInstance().newTransformer().with {
      setOutputProperty(OutputKeys.INDENT, "no")
      transform(new DOMSource(doc), new StreamResult(writer))
    }
    writer.toString()
  }
}
