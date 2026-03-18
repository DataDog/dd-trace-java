package datadog.trace.instrumentation.log4j2;

import datadog.trace.api.Config;
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
    String pattern = Config.get().getLogPattern();
    logger.debug("origin pattern:{},new pattern:{}",eventPattern,pattern);
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
