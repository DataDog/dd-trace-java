package filters;

import akka.stream.Materializer;
import javax.inject.*;
import play.libs.concurrent.HttpExecutionContext;

@Singleton
public class Filter4 extends AbstractFilter {
  @Inject
  public Filter4(Materializer mat, HttpExecutionContext ec) {
    super("filter4", mat, ec);
  }
}
