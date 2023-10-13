package filters;

import akka.stream.Materializer;
import javax.inject.*;
import play.libs.concurrent.HttpExecutionContext;

@Singleton
public class Filter2 extends AbstractFilter {
  @Inject
  public Filter2(Materializer mat, HttpExecutionContext ec) {
    super("filter2", mat, ec);
  }
}
