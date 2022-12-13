package datadog.trace.instrumentation.log4j2;

import net.bytebuddy.asm.Advice;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * @Description
 * @Author liurui
 * @Date 2022/12/9 12:16
 */
public class PatternLayoutBuildAdvice {

  public static final Logger logger = LoggerFactory.getLogger(PatternLayoutBuildAdvice.class);

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter(@Advice.This Object obj,@Advice.FieldValue("pattern") String eventPattern) {
    logger.debug("origin pattern:{}",eventPattern);
    String pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger - [%method,%line] %X{dd.service} %X{dd.trace_id} %X{dd.span_id} - %msg%n";
    try {
      Field field = PatternLayout.Builder.class.getDeclaredField("pattern");
      field.setAccessible(true);
      field.set(obj, pattern);
      logger.debug("update pattern:{}",pattern);
    }catch (Exception e){
      logger.error("pattern update exception:",e);
    }

  }
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(@Advice.FieldValue("pattern") String pattern) {
    logger.debug("exit pattern:{}",pattern);
  }
}
