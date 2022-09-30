package datadog.smoketest.moduleapp;

public class ModuleApplication {
  public static void main(final String[] args) throws InterruptedException {
    Thread.sleep(600);
    ModuleApplication.testBaeModuleRead();
  }
  public static void testBaeModuleRead() {
    Module base = Object.class.getModule();
    Module thisModule = ModuleApplication.class.getModule();

    if(base.canRead(base)) {
      System.out.println("Good. Base can read Base");
    }else {
      throw new RuntimeException("Fail! Base cannot read base");
    }

    if(!base.canRead(thisModule)) {
      System.out.println("Good. Base cannot read thisModule");
    }else {
      throw new RuntimeException("Fail! Base can read thisModule");
    }

    System.out.println("Finish!!!");
  }
}
