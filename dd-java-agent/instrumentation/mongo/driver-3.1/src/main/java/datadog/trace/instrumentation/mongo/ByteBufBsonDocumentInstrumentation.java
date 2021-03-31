package datadog.trace.instrumentation.mongo;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.declaresField;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import com.mongodb.MongoClientOptions;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.bson.BsonDocument;
import org.bson.ByteBuf;

@AutoService(Instrumenter.class)
public class ByteBufBsonDocumentInstrumentation extends Instrumenter.Tracing {

  public ByteBufBsonDocumentInstrumentation() {
    super("mongo");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return NameMatchers.<TypeDescription>named("com.mongodb.connection.ByteBufBsonDocument")
        .and(declaresField(named("byteBuf")));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.bson.BsonDocument", "org.bson.ByteBuf");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(isConstructor(), getClass().getName() + "$ExposeBuffer");
  }

  public static final class ExposeBuffer {
    @Advice.OnMethodExit
    public static void exposeBuffer(
        @Advice.This BsonDocument doc, @Advice.FieldValue("byteBuf") ByteBuf byteBuf) {
      InstrumentationContext.get(BsonDocument.class, ByteBuf.class).put(doc, byteBuf);
    }

    public static void muzzleCheck() {
      MongoClientOptions.builder().addCommandListener(null).build();
    }
  }
}
