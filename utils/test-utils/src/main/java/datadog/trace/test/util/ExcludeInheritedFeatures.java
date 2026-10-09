package datadog.trace.test.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.spockframework.runtime.extension.ExtensionAnnotation;

/**
 * Excludes the feature methods declared in the superclasses of the annotated spec, so only the
 * features declared in the annotated spec (and its subclasses) run. Fixture methods and helpers are
 * still inherited, which lets a spec reuse an existing suite's setup without re-running its
 * features.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtensionAnnotation(ExcludeInheritedFeaturesExtension.class)
public @interface ExcludeInheritedFeatures {}
