package datadog.trace.instrumentation.rabbitmq.amqp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.instrumentation.rabbitmq.amqp.RabbitDecorator.CLIENT_DECORATE;
import static datadog.trace.instrumentation.rabbitmq.amqp.RabbitDecorator.OPERATION_AMQP_DELIVER;
import static datadog.trace.instrumentation.rabbitmq.amqp.RabbitDecorator.RABBITMQ_AMQP;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import com.rabbitmq.client.Command;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class RabbitCommandInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public RabbitCommandInstrumentation() {
    super("amqp", "rabbitmq");
  }

  @Override
  public String hierarchyMarkerType() {
    return "com.rabbitmq.client.Command";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".RabbitDecorator",
      // These are only used by muzzleCheck:
      packageName + ".TracedDelegatingConsumer"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(),
        RabbitCommandInstrumentation.class.getName() + "$CommandConstructorAdvice");
  }

  public static class CommandConstructorAdvice {
    @Advice.OnMethodEnter
    public static int getCallDepth() {
      return CallDepthThreadLocalMap.incrementCallDepth(Command.class);
    }

    @Advice.OnMethodExit
    public static void setResourceNameAddHeaders(
        @Advice.This final Command command, @Advice.Enter final int callDepth) {
      if (callDepth > 0) {
        return;
      }
      final AgentSpan span = activeSpan();

      if (span != null && command.getMethod() != null) {
        // now we have 3 different operations on schema v1
        if (!span.getSpanName().equals(OPERATION_AMQP_DELIVER.toString())
            && RABBITMQ_AMQP.equals(span.getTag(Tags.COMPONENT))) {
          CLIENT_DECORATE.onCommand(span, command);
        }
      }
      CallDepthThreadLocalMap.reset(Command.class);
    }

    /**
     * This instrumentation will match with 2.6, but the channel instrumentation only matches with
     * 2.7 because of TracedDelegatingConsumer. This unused method is added to ensure consistent
     * muzzle validation by preventing match with 2.6.
     */
    public static void muzzleCheck(final TracedDelegatingConsumer consumer) {
      consumer.handleRecoverOk(null);
    }
  }
}
