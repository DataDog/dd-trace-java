package datadog.smoketest.moduleapp;

import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ResolvedModule;
import java.nio.file.spi.FileSystemProvider;  // service type in java.base
import java.util.function.Predicate;
import java.util.stream.Stream;

/*

 *  Basic test of java.lang.Module
 */

public class BasicModuleTest {

    /**
     * Tests that the given module reads all modules in the boot layer.
     */
    private void testReadsAllBootModules(Module m) {
        ModuleLayer bootLayer = ModuleLayer.boot();
        bootLayer.configuration()
            .modules()
            .stream()
            .map(ResolvedModule::name)
            .map(bootLayer::findModule)
            .forEach(target -> assertTrue(m.canRead(target.get())));
    }

    /**
     * Returns a {@code Predicate} to test if a package is exported.
     */
    private Predicate<Exports> doesExport(String pn) {
        return e -> (e.source().equals(pn) && !e.isQualified());
    }

    public void doTests(){
      testThisModule();
      testUnnamedModules();
      testBaseModule();
    }
    private void testThisModule() {
        Module thisModule = BasicModuleTest.class.getModule();
        Module baseModule = Object.class.getModule();

        assertFalse(thisModule.isNamed());
        assertTrue(thisModule.getName() == null);
        assertTrue(thisModule.getDescriptor() == null);
        assertTrue(thisModule.getLayer() == null);
        assertTrue(thisModule.toString().startsWith("unnamed module "));

        ClassLoader thisLoader = BasicModuleTest.class.getClassLoader();
        assertTrue(thisLoader == thisModule.getClassLoader());
        assertTrue(thisLoader.getUnnamedModule() == thisModule);

        // unnamed modules read all other modules
        ClassLoader cl;
        cl = ClassLoader.getPlatformClassLoader();
        assertTrue(thisModule.canRead(cl.getUnnamedModule()));
        cl = ClassLoader.getSystemClassLoader();
        assertTrue(thisModule.canRead(cl.getUnnamedModule()));
        testReadsAllBootModules(thisModule);

        // unnamed modules export all packages
        assertTrue(thisModule.isExported(""));
        assertTrue(thisModule.isExported("", thisModule));
        assertTrue(thisModule.isExported("", baseModule));
        assertTrue(thisModule.isExported("p"));
        assertTrue(thisModule.isExported("p", thisModule));
        assertTrue(thisModule.isExported("p", baseModule));

        // this test is in the unnamed package
        assertTrue(thisModule.getPackages().contains(""));
    }

    public void testUnnamedModules() {
        Module thisModule = BasicModuleTest.class.getModule();
        Module baseModule = Object.class.getModule();

        ClassLoader loader1 = ClassLoader.getSystemClassLoader();
        ClassLoader loader2 = loader1.getParent();

        Module m1 = loader1.getUnnamedModule();
        Module m2 = loader2.getUnnamedModule();

        assertTrue(m1 != m2);

        assertFalse(m1.isNamed());
        assertFalse(m2.isNamed());

        assertTrue(m1.getLayer() == null);
        assertTrue(m2.getLayer() == null);

        assertTrue(m1.toString().startsWith("unnamed module "));
        assertTrue(m2.toString().startsWith("unnamed module "));

        // unnamed module reads all modules
        assertTrue(m1.canRead(m2));
        assertTrue(m2.canRead(m1));

        testReadsAllBootModules(m1);
        testReadsAllBootModules(m2);

        assertTrue(m1.isExported(""));
        assertTrue(m1.isExported("", thisModule));
        assertTrue(m1.isExported("", baseModule));
        assertTrue(m1.isExported("p"));
        assertTrue(m1.isExported("p", thisModule));
        assertTrue(m1.isExported("p", baseModule));
    }

    public void testBaseModule() {
        Module base = Object.class.getModule();
        Module thisModule = BasicModuleTest.class.getModule();

        // getName
        assertTrue(base.getName().equals("java.base"));

        // getDescriptor
        assertTrue(base.getDescriptor().exports().stream()
                .anyMatch(doesExport("java.lang")));

        // getClassLoader
        assertTrue(base.getClassLoader() == null);

        // getLayer
        assertTrue(base.getLayer() == ModuleLayer.boot());

        // toString
        assertEquals(base.toString(), "module java.base");

        // getPackages
        assertTrue(base.getPackages().contains("java.lang"));

        System.out.println("HOLA");
        // canRead
        assertTrue(base.canRead(base));
        assertFalse(base.canRead(thisModule));

        // addReads
        try {
            base.addReads(thisModule);
            assertTrue(false);
        } catch (IllegalCallerException expected) { }
        assertFalse(base.canRead(thisModule));

        // isExported
        assertTrue(base.isExported("java.lang"));
        assertTrue(base.isExported("java.lang", thisModule));
        assertTrue(base.isExported("java.lang", base));
        assertFalse(base.isExported("jdk.internal.misc"));
        assertFalse(base.isExported("jdk.internal.misc", thisModule));
        assertTrue(base.isExported("jdk.internal.misc", base));
        assertFalse(base.isExported("java.wombat"));
        assertFalse(base.isExported("java.wombat", thisModule));
        assertFalse(base.isExported("java.wombat", base));

        // addExports
        try {
            base.addExports("java.lang", thisModule);
            assertTrue(false);
        } catch (IllegalCallerException expected) { }
        try {
            base.addExports("jdk.internal.misc", thisModule);
            assertTrue(false);
        } catch (IllegalCallerException expected) { }
        assertFalse(base.isExported("jdk.internal.misc"));
        assertFalse(base.isExported("jdk.internal.misc", thisModule));

        // isOpen
        assertFalse(base.isOpen("java.lang"));
        assertFalse(base.isOpen("java.lang", thisModule));
        assertTrue(base.isOpen("java.lang", base));
        assertFalse(base.isOpen("jdk.internal.misc"));
        assertFalse(base.isOpen("jdk.internal.misc", thisModule));
        assertTrue(base.isOpen("jdk.internal.misc", base));
        assertFalse(base.isOpen("java.wombat"));
        assertFalse(base.isOpen("java.wombat", thisModule));
        assertFalse(base.isOpen("java.wombat", base));

        // addOpens
        try {
            base.addOpens("jdk.internal.misc", thisModule);
            assertTrue(false);
        } catch (IllegalCallerException expected) { }
        assertFalse(base.isOpen("jdk.internal.misc"));
        assertFalse(base.isOpen("jdk.internal.misc", thisModule));

        // canUse
        assertTrue(base.canUse(FileSystemProvider.class));
        assertFalse(base.canUse(Thread.class));

        // addUses
        try {
            base.addUses(FileSystemProvider.class);
            assertTrue(false);
        } catch (IllegalCallerException expected) { }
        try {
            base.addUses(Thread.class);
            assertTrue(false);
        } catch (IllegalCallerException expected) { }
        assertFalse(base.canUse(Thread.class));
    }

    private void assertTrue(boolean condition) {
      if(!condition) {
        throw new RuntimeException("Condition assertTrue failure");
      }
    }

    private void assertFalse(boolean condition) {
      if(condition) {
        throw new RuntimeException("Condition assertFalse failure");
      }
    }
    private void assertEquals(String text1, String text2) {
      if(!text1.equals(text2)) {
        throw new RuntimeException("Condition assertEquals failure");
      }

    }
}
