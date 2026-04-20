/**
 * Dev notes
 *
 * <p>Constraints:
 *
 * <ul>
 *   <li>Introduce as few as possible matchers
 *   <li>Only have matchers for generic purpose, don't introduce feature / produce / use-case
 *       specific matchers
 * </ul>
 *
 * Todo:
 *
 * <ul>
 *   <li>Think about extensibility? Open matchers for inheritance or specialization
 *   <li>Tag assertions are WIP. Too much coupling in the current Groovy solution
 *   <li>Span links assertions might be a bit too rigid for now
 * </ul>
 */
package datadog.trace.agent.test.assertions;
