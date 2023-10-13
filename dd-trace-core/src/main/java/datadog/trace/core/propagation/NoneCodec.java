package datadog.trace.core.propagation;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.DDSpanContext;

public class NoneCodec {

  public static final HttpCodec.Injector INJECTOR =
      new HttpCodec.Injector() {
        @Override
        public <C> void inject(
            DDSpanContext context, C carrier, AgentPropagation.Setter<C> setter) {}
      };

  public static final HttpCodec.Extractor EXTRACTOR =
      new HttpCodec.Extractor() {
        @Override
        public <C> TagContext extract(C carrier, AgentPropagation.ContextVisitor<C> getter) {
          return null;
        }
      };
}
