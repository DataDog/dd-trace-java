package datadog.correctnesscontract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an annotation as a correctness contract for documentation and static analysis. A
 * correctness contract states a rule that code must follow, in the RFC 2119 sense: a violation is a
 * bug, not a tradeoff. Contract annotations change no runtime behavior.
 *
 * <p>Unlike {@link datadog.perfcontract.PerfContract}, a correctness contract has no suppression. A
 * declaration that needs to break the rule shows that the contract is wrong for its type; change
 * the annotation rather than exempting the use. A {@link datadog.perfcontract.SuppressPerfContract}
 * that names a correctness contract is invalid, since every class in its {@code value} must be a
 * perf contract.
 *
 * <p>Tools use this meta-annotation to discover contracts. This annotation defines no rule itself.
 *
 * <p>{@link RetentionPolicy#CLASS} lets tools discover contracts in dependency class files without
 * exposing them through runtime reflection.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.ANNOTATION_TYPE)
public @interface CorrectnessContract {}
