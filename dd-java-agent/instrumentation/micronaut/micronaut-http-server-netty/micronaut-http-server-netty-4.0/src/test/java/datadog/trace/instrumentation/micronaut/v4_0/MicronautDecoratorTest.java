package datadog.trace.instrumentation.micronaut.v4_0;

import static datadog.trace.instrumentation.micronaut.v4_0.MicronautDecorator.DECORATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import io.micronaut.http.HttpResponse;
import org.tabletest.junit.TableTest;

class MicronautDecoratorTest {

  /** Codes outside the HttpStatus enum make HttpResponse.getStatus() throw. */
  @TableTest({
    "scenario          | code",
    "standard          | 200 ",
    "client closed     | 499 ",
    "application-coded | 599 "
  })
  void statusIsTheResponseCode(int code) {
    HttpResponse<?> response = mock(HttpResponse.class, CALLS_REAL_METHODS);
    doReturn(code).when(response).code();

    assertEquals(code, DECORATE.status(response));
  }
}
