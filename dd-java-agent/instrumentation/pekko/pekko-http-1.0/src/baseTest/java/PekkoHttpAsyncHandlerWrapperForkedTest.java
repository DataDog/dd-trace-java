/** Runs the async-handler context-retention reproducer with completion-priority propagation. */
class PekkoHttpAsyncHandlerWrapperForkedTest extends AbstractPekkoHttpAsyncHandlerWrapperTest {

  @Override
  protected boolean expectedCompletionPriority() {
    return true;
  }
}
