package datadog.trace.junit.utils.converter;

import static datadog.trace.api.DDTags.MANUAL_DROP;
import static datadog.trace.api.DDTags.MANUAL_KEEP;
import static datadog.trace.api.DDTags.RESOURCE_NAME;
import static datadog.trace.api.DDTags.SERVICE_NAME;
import static datadog.trace.api.DDTags.SPAN_TYPE;
import static datadog.trace.api.DDTags.THREAD_ID;
import static datadog.trace.api.DDTags.THREAD_NAME;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_METHOD;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_STATUS;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_SERVICE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_BROKER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CONSUMER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_PRODUCER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_SERVER;

import datadog.trace.junit.utils.converter.AbstractClassConstantConvertor.AbstractStringFallThruConverter;
import java.util.HashMap;
import java.util.Map;

public class TagsConverter extends AbstractStringFallThruConverter {
  private static final Map<String, String> MAPPING;

  static {
    MAPPING = new HashMap<>();
    // Tags mapping (class name will be trimmed)
    MAPPING.put("SPAN_KIND_SERVER", SPAN_KIND_SERVER);
    MAPPING.put("SPAN_KIND_CLIENT", SPAN_KIND_CLIENT);
    MAPPING.put("SPAN_KIND_PRODUCER", SPAN_KIND_PRODUCER);
    MAPPING.put("SPAN_KIND_CONSUMER", SPAN_KIND_CONSUMER);
    MAPPING.put("SPAN_KIND_BROKER", SPAN_KIND_BROKER);
    MAPPING.put("PEER_SERVICE", PEER_SERVICE);
    MAPPING.put("HTTP_URL", HTTP_URL);
    MAPPING.put("HTTP_STATUS", HTTP_STATUS);
    MAPPING.put("HTTP_METHOD", HTTP_METHOD);
    // DDTags mapping with class name
    MAPPING.put("DDTags.SPAN_TYPE", SPAN_TYPE);
    MAPPING.put("DDTags.SERVICE_NAME", SERVICE_NAME);
    MAPPING.put("DDTags.RESOURCE_NAME", RESOURCE_NAME);
    MAPPING.put("DDTags.THREAD_NAME", THREAD_NAME);
    MAPPING.put("DDTags.THREAD_ID", THREAD_ID);
    MAPPING.put("DDTags.MANUAL_KEEP", MANUAL_KEEP);
    MAPPING.put("DDTags.MANUAL_DROP", MANUAL_DROP);
    // DDTags mapping with direct field name
    MAPPING.put("SPAN_TYPE", SPAN_TYPE);
    MAPPING.put("SERVICE_NAME", SERVICE_NAME);
    MAPPING.put("RESOURCE_NAME", RESOURCE_NAME);
    MAPPING.put("THREAD_NAME", THREAD_NAME);
    MAPPING.put("THREAD_ID", THREAD_ID);
    MAPPING.put("MANUAL_KEEP", MANUAL_KEEP);
    MAPPING.put("MANUAL_DROP", MANUAL_DROP);
  }

  @Override
  protected String className() {
    return "Tags";
  }

  @Override
  protected Map<String, String> mapping() {
    return MAPPING;
  }
}
