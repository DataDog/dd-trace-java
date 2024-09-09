package datadog.trace.api.civisibility;

import java.net.URL;
import java.util.BitSet;

public interface ClassMatchingCache {

  ClassMatchingCache NO_OP =
      new ClassMatchingCache() {
        @Override
        public void recordMatchingResult(String name, URL classFile, BitSet ids) {}

        @Override
        public BitSet getRecordedMatchingResult(String name, URL classFile) {
          return null;
        }
      };

  void recordMatchingResult(String name, URL classFile, BitSet ids);

  BitSet getRecordedMatchingResult(String name, URL classFile);
}
