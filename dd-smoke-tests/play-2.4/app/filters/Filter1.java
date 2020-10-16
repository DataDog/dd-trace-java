package filters;

import javax.inject.*;

@Singleton
public class Filter1 extends AbstractFilter {
  public Filter1() {
    super("filter1", true);
  }
}
