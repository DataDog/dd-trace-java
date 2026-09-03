/** Runs the async-handler context-retention reproducer with default Scala Promise propagation. */
class PekkoHttpAsyncHandlerWrapperTest extends AbstractPekkoHttpAsyncHandlerWrapperTest {

  @Override
  protected boolean expectedCompletionPriority() {
    return false;
  }
}
