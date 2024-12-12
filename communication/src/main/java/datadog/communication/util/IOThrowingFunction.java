package datadog.communication.util;

import java.io.IOException;

@FunctionalInterface
public interface IOThrowingFunction<T, U> {

  U apply(T t) throws IOException;
}
