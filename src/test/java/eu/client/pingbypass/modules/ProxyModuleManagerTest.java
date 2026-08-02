package eu.client.pingbypass.modules;

import eu.client.EUClient;
import eu.client.modules.Module;
import eu.client.modules.ModuleManager;
import eu.client.settings.Setting;
import eu.client.settings.impl.BooleanSetting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ProxyModuleManager.
 *
 * <p>Verifies that the manager correctly pulls Module instances from
 * the global ModuleManager and exposes them for proxy-side use.</p>
 *
 * <p>Uses mocked ModuleManager and Module instances to avoid loading
 * the full Minecraft environment in tests.</p>
 *
 * <p><b>Validates: Requirements 8.1, 8.4</b></p>
 */
class ProxyModuleManagerTest {

    private ProxyModuleManager manager;
    private ModuleManager mockModuleManager;

    // Mock modules representing the 5 proxy-safe combat modules.
    // AutoCrystal/AutoTotem migrated to ServerAutoCrystal/ServerAutoTotem
    // (EUClient.PB_MODULE_MANAGER), no longer registered here -- see
    // ProxyModuleManager.PROXY_SAFE_MODULES.
    private Module surround, autoArmor, holeFill, selfFill, selfTrap;

    @BeforeEach
    void setUp() throws Exception {
        mockModuleManager = mock(ModuleManager.class);

        surround = createMockModule("Surround", new BooleanSetting("Center", "", true));
        autoArmor = createMockModule("AutoArmor");
        holeFill = createMockModule("HoleFill");
        selfFill = createMockModule("SelfFill");
        selfTrap = createMockModule("SelfTrap");

        // Wire up the mock ModuleManager to return our mock modules by class
        // Use doReturn().when() to avoid generic type mismatch with when().thenReturn()
        doReturn(surround).when(mockModuleManager).getModule(eu.client.modules.impl.combat.SurroundModule.class);
        doReturn(autoArmor).when(mockModuleManager).getModule(eu.client.modules.impl.combat.AutoArmorModule.class);
        doReturn(holeFill).when(mockModuleManager).getModule(eu.client.modules.impl.combat.HoleFillModule.class);
        doReturn(selfFill).when(mockModuleManager).getModule(eu.client.modules.impl.combat.SelfFillModule.class);
        doReturn(selfTrap).when(mockModuleManager).getModule(eu.client.modules.impl.combat.SelfTrapModule.class);

        // Inject the mock ModuleManager
        setStaticField(EUClient.class, "MODULE_MANAGER", mockModuleManager);

        manager = new ProxyModuleManager();
        manager.init();
    }

    // ========================================================================
    // Module registration and lookup (Requirement 8.1)
    // ========================================================================

    @Test
    void init_registersExpectedModules() {
        assertNotNull(manager.getModules());
        assertEquals(5, manager.getModules().size(),
                "Should register 5 proxy-safe combat modules");
    }

    @Test
    void getModule_byName_returnsCorrectModule() {
        Module s = manager.getModule("Surround");
        assertNotNull(s, "Should find Surround by name");
        assertEquals("Surround", s.getName());
    }

    @Test
    void getModule_byName_caseInsensitive() {
        Module s = manager.getModule("surround");
        assertNotNull(s, "Should find module with case-insensitive lookup");
        assertEquals("Surround", s.getName());
    }

    @Test
    void getModule_unknownName_returnsNull() {
        assertNull(manager.getModule("NonExistentModule"),
                "Should return null for unknown module name");
    }

    @Test
    void getModules_returnsSortedByName() {
        var modules = manager.getModules();
        for (int i = 1; i < modules.size(); i++) {
            assertTrue(modules.get(i - 1).getName().compareTo(modules.get(i).getName()) <= 0,
                    "Modules should be sorted alphabetically by name");
        }
    }

    @Test
    void registeredModules_haveSettings() {
        Module s = manager.getModule("Surround");
        assertNotNull(s);
        assertFalse(s.getSettings().isEmpty(),
                "Surround should have settings registered");
        assertEquals(1, s.getSettings().size());
    }

    @Test
    void getSetting_unknownName_returnsNull() {
        Module s = manager.getModule("Surround");
        assertNotNull(s);
        assertNull(s.getSetting("NonExistent"),
                "Should return null for unknown setting name");
    }

    // ========================================================================
    // Toggle behavior (Requirement 8.4)
    // ========================================================================

    @Test
    void setToggled_true_enablesModule() {
        Module s = manager.getModule("Surround");
        assertNotNull(s);
        s.setToggled(true);
        assertTrue(s.isToggled());
        s.setToggled(false);
    }

    @Test
    void setToggled_false_disablesModule() {
        Module s = manager.getModule("Surround");
        assertNotNull(s);
        s.setToggled(true);
        s.setToggled(false);
        assertFalse(s.isToggled());
    }

    @Test
    void toggleOneModule_doesNotAffectOthers() {
        Module s = manager.getModule("Surround");
        Module armor = manager.getModule("AutoArmor");
        assertNotNull(s);
        assertNotNull(armor);

        boolean armorBefore = armor.isToggled();
        s.setToggled(true);
        assertEquals(armorBefore, armor.isToggled(),
                "Enabling Surround should not affect AutoArmor");
        s.setToggled(false);
    }

    @Test
    void modules_areRealModuleInstances() {
        Module s = manager.getModule("Surround");
        assertSame(surround, s,
                "ProxyModuleManager should reference the same Module instance from ModuleManager");
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private Module createMockModule(String name, Setting... settings) {
        Module module = mock(Module.class);
        when(module.getName()).thenReturn(name);
        when(module.getSettings()).thenReturn(List.of(settings));
        when(module.getSetting(anyString())).thenAnswer(inv -> {
            String settingName = inv.getArgument(0);
            for (Setting s : settings) {
                if (s.getName().equalsIgnoreCase(settingName)) return s;
            }
            return null;
        });
        // Track toggled state
        final boolean[] toggled = {false};
        doAnswer(inv -> { toggled[0] = inv.getArgument(0); return null; })
                .when(module).setToggled(anyBoolean());
        doAnswer(inv -> { toggled[0] = inv.getArgument(0); return null; })
                .when(module).setToggled(anyBoolean(), anyBoolean());
        when(module.isToggled()).thenAnswer(inv -> toggled[0]);
        return module;
    }

    private static void setStaticField(Class<?> clazz, String fieldName, Object value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }
}
