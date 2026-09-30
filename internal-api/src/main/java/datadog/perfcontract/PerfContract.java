package datadog.perfcontract;

import datadog.trace.api.function.NoEscape;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an annotation as a performance contract for documentation and static analysis. Contract
 * annotations describe performance constraints, such as the retention rule documented by {@link
 * NoEscape}, without changing runtime behavior.
 *
 * <p>Tools use this meta-annotation to discover contracts and recognize {@link
 * SuppressPerfContract} exemptions. This annotation defines no rule itself.
 *
 * <p>{@link RetentionPolicy#CLASS} lets tools discover contracts in dependency class files without
 * exposing them through runtime reflection.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.ANNOTATION_TYPE)
public @interface PerfContract {}
