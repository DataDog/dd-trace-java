package datadog.trace.instrumentation.springwebflux.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;

// Adapted from Opentelemetry
public final class StatusCodes {
  private static final Logger LOGGER = LoggerFactory.getLogger(StatusCodes.class);
  public static final Function<ClientResponse, Integer> STATUS_CODE_FUNCTION =
      getStatusCodeFunction();

  private static Function<ClientResponse, Integer> getStatusCodeFunction() {
    Function<ClientResponse, Integer> statusCodeFunction = getStatusCodeFunction60();
    if (statusCodeFunction == null) {
      statusCodeFunction = getStatusCodeFunction51();
    }
    if (statusCodeFunction == null) {
      statusCodeFunction = getStatusCodeFunction50();
    }
    if (statusCodeFunction == null) {
      LOGGER.debug(
          "Unable to find a status code extractor function working for the current webflux client version. "
              + "Status codes will not be tagged on webflux client spans");
    }
    return statusCodeFunction;
  }

  // in webflux 6.0, HttpStatusCode class was introduced, and statusCode() was changed to return
  // HttpStatusCode instead of HttpStatus
  private static Function<ClientResponse, Integer> getStatusCodeFunction60() {
    MethodHandle statusCode;
    MethodHandle value;
    try {
      Class<?> httpStatusCodeClass =
          Class.forName(
              "org.springframework.http.HttpStatusCode", false, StatusCodes.class.getClassLoader());
      statusCode =
          MethodHandles.publicLookup()
              .findVirtual(
                  ClientResponse.class, "statusCode", MethodType.methodType(httpStatusCodeClass));
      value =
          MethodHandles.publicLookup()
              .findVirtual(httpStatusCodeClass, "value", MethodType.methodType(int.class));
    } catch (ClassNotFoundException | IllegalAccessException | NoSuchMethodException e) {
      return null;
    }

    return response -> {
      try {
        Object httpStatusCode = statusCode.invoke(response);
        return (int) value.invoke(httpStatusCode);
      } catch (Throwable e) {
        return null;
      }
    };
  }

  // in webflux 5.1, rawStatusCode() was introduced to retrieve the exact status code
  // note: rawStatusCode() was deprecated in 6.0
  private static Function<ClientResponse, Integer> getStatusCodeFunction51() {
    MethodHandle rawStatusCode;
    try {
      rawStatusCode =
          MethodHandles.publicLookup()
              .findVirtual(ClientResponse.class, "rawStatusCode", MethodType.methodType(int.class));
    } catch (IllegalAccessException | NoSuchMethodException e) {
      return null;
    }

    return response -> {
      try {
        return (int) rawStatusCode.invoke(response);
      } catch (Throwable e) {
        return null;
      }
    };
  }

  // in webflux 5.0, statusCode() returns HttpStatus, which only represents standard status codes
  // (there's no way to capture arbitrary status codes)
  private static Function<ClientResponse, Integer> getStatusCodeFunction50() {
    MethodHandle statusCode;
    MethodHandle value;
    try {
      statusCode =
          MethodHandles.publicLookup()
              .findVirtual(
                  ClientResponse.class, "statusCode", MethodType.methodType(HttpStatus.class));
      value =
          MethodHandles.publicLookup()
              .findVirtual(HttpStatus.class, "value", MethodType.methodType(int.class));
    } catch (IllegalAccessException | NoSuchMethodException e) {
      return null;
    }

    return response -> {
      try {
        Object httpStatusCode = statusCode.invoke(response);
        return (int) value.invoke(httpStatusCode);
      } catch (Throwable e) {
        return null;
      }
    };
  }

  private StatusCodes() {}
}
