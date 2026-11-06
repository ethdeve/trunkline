package org.unreal.modelrouter.config.version;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VersionClassesTest {

    @Test
    void configMetadata_gettersSetters() {
        ConfigMetadata metadata = new ConfigMetadata();
        metadata.setConfigKey("test-key");
        metadata.setCurrentVersion(5);
        metadata.setInitialVersion(1);
        metadata.setCreatedAt(LocalDateTime.now());
        metadata.setLastModified(LocalDateTime.now());
        metadata.setLastModifiedBy("admin");
        metadata.setTotalVersions(5);

        assertEquals("test-key", metadata.getConfigKey());
        assertEquals(5, metadata.getCurrentVersion());
        assertEquals(1, metadata.getInitialVersion());
        assertNotNull(metadata.getCreatedAt());
        assertNotNull(metadata.getLastModified());
        assertEquals("admin", metadata.getLastModifiedBy());
        assertEquals(5, metadata.getTotalVersions());
    }

    @Test
    void configMetadata_existingVersions() {
        ConfigMetadata metadata = new ConfigMetadata();
        metadata.setExistingVersions(new java.util.HashSet<>(List.of(1, 2, 3)));

        assertTrue(metadata.getExistingVersions().contains(2));
        assertFalse(metadata.getExistingVersions().contains(4));
    }

    @Test
    void versionInfo_gettersSetters() {
        VersionInfo info = new VersionInfo();
        info.setVersion(3);
        info.setCreatedAt(LocalDateTime.now());
        info.setCreatedBy("admin");
        info.setDescription("Test version");
        info.setChangeType(VersionInfo.ChangeType.UPDATE);
        info.setConfigSnapshot(Map.of("key", "value"));

        assertEquals(3, info.getVersion());
        assertNotNull(info.getCreatedAt());
        assertEquals("admin", info.getCreatedBy());
        assertEquals("Test version", info.getDescription());
        assertEquals(VersionInfo.ChangeType.UPDATE, info.getChangeType());
        assertEquals("value", info.getConfigSnapshot().get("key"));
    }
}
