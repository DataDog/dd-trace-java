package test;

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS;

import datadog.trace.agent.test.base.HttpServerTest;
import groovy.lang.Closure;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.reactivex.Single;
import javax.validation.constraints.NotBlank;

@Controller("/")
class TestController {
  @Get(uri = "/success", produces = MediaType.TEXT_PLAIN)
  public Single<String> success() {
    return HttpServerTest.controller(
        SUCCESS,
        new Closure<Single<String>>(null) {
          public Single<String> doCall() {
            return Single.just(SUCCESS.getBody());
          }
        });
  }

  @Get(uri = "/exception", produces = MediaType.TEXT_PLAIN)
  public Single<String> exception() {
    return HttpServerTest.controller(
        EXCEPTION,
        new Closure<Single<String>>(null) {
          public Single<String> doCall() throws Exception {
            throw new Exception(EXCEPTION.getBody());
          }
        });
  }

  @Get(uri = "/error-status", produces = MediaType.TEXT_PLAIN)
  public HttpResponse<String> error(final HttpRequest<?> request) {
    return HttpServerTest.controller(
        ERROR,
        new Closure<HttpResponse<String>>(null) {
          public HttpResponse<String> doCall() {
            return HttpResponse.serverError(ERROR.getBody());
          }
        });
  }

  @Get(uri = "/forwarded", produces = MediaType.TEXT_PLAIN)
  public HttpResponse<String> forwarded(final HttpRequest<?> request) {
    return HttpServerTest.controller(
        FORWARDED,
        new Closure<HttpResponse<String>>(null) {
          public HttpResponse<String> doCall() {
            return HttpResponse.ok(
                request.getHeaders().get("x-forwarded-for", String.class, "unknown"));
          }
        });
  }

  @Get(uri = "/redirect", produces = MediaType.TEXT_PLAIN)
  public MutableHttpResponse<Object> redirect(final HttpRequest<?> request) {
    return HttpServerTest.controller(
        REDIRECT,
        new Closure<MutableHttpResponse<Object>>(null) {
          public MutableHttpResponse<Object> doCall() {
            return HttpResponse.status(HttpStatus.valueOf(REDIRECT.getStatus()))
                .header("location", REDIRECT.getBody());
          }
        });
  }

  @Get(uri = "/path/{id}/param", produces = MediaType.TEXT_PLAIN)
  public Single<Integer> path_param(@NotBlank final Integer id) {
    return HttpServerTest.controller(
        PATH_PARAM,
        new Closure<Single<Integer>>(null) {
          public Single<Integer> doCall() {
            return Single.just(id);
          }
        });
  }

  @Get(uri = "/query", produces = MediaType.TEXT_PLAIN)
  public HttpResponse<String> query_param(final HttpRequest<?> request) {
    return handle_query(QUERY_PARAM, request);
  }

  @Get(uri = "/encoded_query", produces = MediaType.TEXT_PLAIN)
  public HttpResponse<String> query_encoded_query(final HttpRequest<?> request) {
    return handle_query(QUERY_ENCODED_QUERY, request);
  }

  @Get(uri = "/encoded%20path%20query", produces = MediaType.TEXT_PLAIN)
  public HttpResponse<String> query_encoded_both(final HttpRequest<?> request) {
    return handle_query(QUERY_ENCODED_BOTH, request);
  }

  private HttpResponse<String> handle_query(
      final HttpServerTest.ServerEndpoint endpoint, final HttpRequest<?> request) {
    return HttpServerTest.controller(
        endpoint,
        new Closure<HttpResponse<String>>(null) {
          public HttpResponse<String> doCall() {
            String query =
                "some=" + request.getParameters().getFirst("some", String.class).orElse("bad");
            return HttpResponse.ok(endpoint.bodyForQuery(query));
          }
        });
  }
}
