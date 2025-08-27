package annotatedsample;

import io.opentelemetry.instrumentation.annotations.AddingSpanAttributes;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import java.util.List;

public class AnnotatedMethods {
  @AddingSpanAttributes
  public static String sayHelloWithStringAttribute(@SpanAttribute("custom-tag") String param) {
    return "hello!";
  }

  @AddingSpanAttributes
  public static String sayHelloWithIntAttribute(@SpanAttribute("custom-tag") int param) {
    return "hello!";
  }

  @AddingSpanAttributes
  public static String sayHelloWithLongAttribute(@SpanAttribute("custom-tag") long param) {
    return "hello!";
  }

  @AddingSpanAttributes
  public static String sayHelloWithListAttribute(@SpanAttribute("custom-tag") List<?> param) {
    return "hello!";
  }

  @AddingSpanAttributes
  public static String sayHelloWithMultipleAttributes(
      @SpanAttribute("custom-tag1") String param1, @SpanAttribute("custom-tag2") String param2) {
    return "hello!";
  }
}
