package datadog.trace.core.propagation;

import datadog.context.propagation.CarrierSetter;
import datadog.trace.api.Config;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.core.DDSpanContext;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NoneCodec {
    private static final Logger log = LoggerFactory.getLogger(NoneCodec.class);

    private static class NoneContextInterpreter extends ContextInterpreter {
        private NoneContextInterpreter(Config config) {
            super(config);
        }

        @Override
        public TracePropagationStyle style() {
            return TracePropagationStyle.NONE;
        }

        @Override
        public boolean accept(String key, String value) {
            if (null == key || key.isEmpty()) {
                return true;
            }
            if (LOG_EXTRACT_HEADER_NAMES) {
                log.debug("Header: {}", key);
            }
            char first = Character.toLowerCase(key.charAt(0));
            switch (first) {
                case 'x':
                    handledXForwarding(key, value);
                    break;
                case 'f':
                    handledForwarding(key, value);
                    break;
                case 'u':
                    handledUserAgent(key, value);
                    break;
                default:
            }

            handledIpHeaders(key, value);
            if (handleTags(key, value)) {
                return true;
            }
            handleMappedBaggage(key, value);
            return true;
        }
    }

    public static final HttpCodec.Injector INJECTOR = new HttpCodec.Injector() {
        @Override
        public <C> void inject(DDSpanContext context, C carrier, CarrierSetter<C> setter) {}
    };

    public static HttpCodec.Extractor newExtractor(Config config, Supplier<TraceConfig> traceConfigSupplier) {
        return new TagContextExtractor(traceConfigSupplier, () -> new NoneContextInterpreter(config));
    }
}
