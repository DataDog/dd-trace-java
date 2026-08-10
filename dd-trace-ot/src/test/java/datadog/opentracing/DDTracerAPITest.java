package datadog.opentracing;

import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVICE_NAME;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE;
import static datadog.trace.api.DDTags.RUNTIME_ID_TAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.sampling.RateByServiceTraceSampler;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.test.util.DDJavaSpecification;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class DDTracerAPITest extends DDJavaSpecification {

  @Test
  void verifySamplerWriterConstructor() throws Exception {
    ListWriter writer = new ListWriter();
    RateByServiceTraceSampler sampler = new RateByServiceTraceSampler();
    DDTracer tracerOT = new DDTracer(DEFAULT_SERVICE_NAME, writer, sampler);
    try {
      AgentTracer.TracerAPI tracerAPI = tracerOT.getInternalTracer();
      CoreTracer tracer = (CoreTracer) tracerAPI;

      assertEquals(DEFAULT_SERVICE_NAME, getField(tracer, "serviceName"));
      assertSame(sampler, getField(tracer, "initialSampler"));
      assertSame(writer, getField(tracer, "writer"));

      Object localRootSpanTags = getField(tracer, "localRootSpanTags");
      assertNotNull(localRootSpanTags.toString());
      // Verify runtime-id and language tags are populated
      assertTrue(
          ((java.util.Map<?, ?>) localRootSpanTags).get(RUNTIME_ID_TAG).toString().length() > 0);
      assertEquals(
          LANGUAGE_TAG_VALUE, ((java.util.Map<?, ?>) localRootSpanTags).get(LANGUAGE_TAG_KEY));
    } finally {
      tracerOT.close();
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T getField(Object obj, String fieldName) throws Exception {
    Class<?> cls = obj.getClass();
    while (cls != null) {
      try {
        Field field = cls.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(obj);
      } catch (NoSuchFieldException ignored) {
        cls = cls.getSuperclass();
      }
    }
    throw new NoSuchFieldException("Field " + fieldName + " not found on " + obj.getClass());
  }
}
