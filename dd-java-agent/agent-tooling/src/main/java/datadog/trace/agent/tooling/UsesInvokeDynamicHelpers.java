package datadog.trace.agent.tooling;

import datadog.trace.agent.tooling.bytebuddy.InvokeDynamicHelperUsersPlugin;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks the annotated class for processing by {@link InvokeDynamicHelperUsersPlugin}. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface UsesInvokeDynamicHelpers {}
