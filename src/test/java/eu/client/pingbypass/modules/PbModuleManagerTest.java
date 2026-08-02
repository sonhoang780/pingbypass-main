package eu.client.pingbypass.modules;

import eu.client.settings.Setting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PbModuleManagerTest {
    private PbModuleManager manager;
    private int fakeTickCount;

    private class FakeModule extends PbModule {
        FakeModule(String name) { super(name); }
        @Override public void tick() { fakeTickCount++; }
        @Override public List<Setting> getSettings() { return List.of(); }
    }

    @BeforeEach
    void setUp() {
        manager = new PbModuleManager();
        fakeTickCount = 0;
    }

    @Test
    void register_thenGetByName_caseInsensitive() {
        manager.register(new FakeModule("AutoCrystal"));
        assertNotNull(manager.getModule("autocrystal"));
    }

    @Test
    void getModules_sortedByName() {
        manager.register(new FakeModule("Surround"));
        manager.register(new FakeModule("AutoCrystal"));
        List<PbModule> modules = manager.getModules();
        assertEquals("AutoCrystal", modules.get(0).getName());
        assertEquals("Surround", modules.get(1).getName());
    }

    @Test
    void tick_onlyRunsToggledModules() {
        FakeModule module = new FakeModule("AutoCrystal");
        manager.register(module);
        manager.tick();
        assertEquals(0, fakeTickCount, "Untoggled module should not tick");

        module.setToggled(true);
        manager.tick();
        assertEquals(1, fakeTickCount);
    }

    @Test
    void unknownName_returnsNull() {
        assertNull(manager.getModule("DoesNotExist"));
    }
}
