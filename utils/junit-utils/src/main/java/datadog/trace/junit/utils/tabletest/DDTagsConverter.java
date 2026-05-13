package datadog.trace.junit.utils.tabletest;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;

public class DDTagsConverter implements ArgumentConverter {
  @Override
  public Object convert(Object source, ParameterContext context)
      throws ArgumentConversionException {
    if (source instanceof Map) {
      // convert keys and values from the map
      Map<? super Object, ? super Object> map = new HashMap<>();
      for (Map.Entry<? super Object, ? super Object> e :
          ((Map<? super Object, ? super Object>) source).entrySet()) {
        map.put(convert(e.getKey(), context), convert(e.getValue(), context));
      }
      return map;
    }
    if (source.toString().startsWith("DDTags.")) {
      switch (source.toString()) {
        case "DDTags.SPAN_TYPE":
          return DDTags.SPAN_TYPE;
        case "DDTags.SERVICE_NAME":
          return DDTags.SERVICE_NAME;
        case "DDTags.RESOURCE_NAME":
          return DDTags.RESOURCE_NAME;
        case "DDTags.THREAD_NAME":
          return DDTags.THREAD_NAME;
        case "DDTags.THREAD_ID":
          return DDTags.THREAD_ID;
        case "DDTags.MANUAL_KEEP":
          return DDTags.MANUAL_KEEP;
        case "DDTags.MANUAL_DROP":
          return DDTags.MANUAL_DROP;
        default:
          throw new ArgumentConversionException("Cannot convert " + source);
      }
    }
    if (source.toString().startsWith("Tags.")) {
      switch (source.toString()) {
        case "Tags.SPAN_KIND_SERVER":
          return Tags.SPAN_KIND_SERVER;
        case "Tags.SPAN_KIND_CLIENT":
          return Tags.SPAN_KIND_CLIENT;
        case "Tags.SPAN_KIND_PRODUCER":
          return Tags.SPAN_KIND_PRODUCER;
        case "Tags.SPAN_KIND_CONSUMER":
          return Tags.SPAN_KIND_CONSUMER;
        case "Tags.SPAN_KIND_BROKER":
          return Tags.SPAN_KIND_BROKER;
        case "Tags.PEER_SERVICE":
          return Tags.PEER_SERVICE;
        case "Tags.HTTP_URL":
          return Tags.HTTP_URL;
        case "Tags.HTTP_STATUS":
          return Tags.HTTP_STATUS;
        case "Tags.HTTP_METHOD":
          return Tags.HTTP_METHOD;
        default:
          throw new ArgumentConversionException("Cannot convert " + source);
      }
    }
    return source.toString();
  }
}
