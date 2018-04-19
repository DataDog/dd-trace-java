package stackstate.trace.agent.test;

import io.opentracing.tag.StringTag;
import io.opentracing.util.GlobalTracer;
import stackstate.trace.api.DDTags;
import stackstate.trace.api.Trace;

public class SayTracedHello {

  @Trace
  public static String sayHello() {
    new StringTag(DDTags.SERVICE_NAME)
        .set(GlobalTracer.get().scopeManager().active().span(), "test");
    return "hello!";
  }

  @Trace(operationName = "SAY_HA")
  public static String sayHA() {
    new StringTag(DDTags.SERVICE_NAME)
        .set(GlobalTracer.get().scopeManager().active().span(), "test");
    new StringTag(DDTags.SPAN_TYPE).set(GlobalTracer.get().scopeManager().active().span(), "DB");
    return "HA!!";
  }

  @Trace(operationName = "NEW_TRACE")
  public static String sayHELLOsayHA() {
    new StringTag(DDTags.SERVICE_NAME)
        .set(GlobalTracer.get().scopeManager().active().span(), "test2");
    return sayHello() + sayHA();
  }

  @Trace(operationName = "ERROR")
  public static String sayERROR() {
    throw new RuntimeException();
  }
}
