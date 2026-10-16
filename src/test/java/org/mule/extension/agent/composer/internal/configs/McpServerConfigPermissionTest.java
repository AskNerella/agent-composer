package org.mule.extension.agent.composer.internal.configs;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class McpServerConfigPermissionTest {
    @Test
    public void parsesExactCommaSeparatedPermissionTools() throws Exception {
        McpServerConfig config = new McpServerConfig();
        set(config, "toolsRequiringPermission", " create_ticket, delete_ticket ,,send_message ");
        assertEquals(Arrays.asList("create_ticket", "delete_ticket", "send_message"),
                config.getToolsRequiringPermissionList());
        assertTrue(config.requiresPermission("delete_ticket"));
        assertFalse(config.requiresPermission("Delete_Ticket"));
        assertFalse(config.requiresPermission("unknown"));
    }

    @Test
    public void emptyPermissionConfigurationAllowsToolsWithoutApproval() throws Exception {
        McpServerConfig config = new McpServerConfig();
        set(config, "toolsRequiringPermission", "  ");
        assertEquals(Collections.emptyList(), config.getToolsRequiringPermissionList());
        assertFalse(config.requiresPermission("create_ticket"));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
