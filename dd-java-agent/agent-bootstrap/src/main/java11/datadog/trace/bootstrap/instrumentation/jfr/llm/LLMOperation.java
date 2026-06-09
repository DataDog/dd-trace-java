package datadog.trace.bootstrap.instrumentation.jfr.llm;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.MetadataDefinition;

@MetadataDefinition
@Label("LLM Operation")
@Description("Marks an event as an LLM operation")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface LLMOperation {}
