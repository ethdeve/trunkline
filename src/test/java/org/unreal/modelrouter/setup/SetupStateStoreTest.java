/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.setup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 初始化向导状态文件读写测试。
 *
 * @author Trunkline Team
 */
@DisplayName("SetupStateStore 初始化状态文件持久化")
class SetupStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("文件不存在 → 默认本地模式、exists=false")
    void read_whenMissing_returnsDefaultLocal() {
        SetupStateStore store = new SetupStateStore(tempDir.resolve("setup.properties"));
        assertFalse(store.exists());
        SetupState state = store.read();
        assertEquals(SetupMode.LOCAL, state.mode());
        assertNull(state.updatedAt());
        assertNull(state.password());
        assertNull(state.datasourceUrl());
    }

    @Test
    @DisplayName("saveLocal 落盘后 exists=true，且清除历史远程密码")
    void saveLocal_persistsAndClearsRemote() {
        Path file = tempDir.resolve("setup.properties");
        SetupStateStore store = new SetupStateStore(file);
        store.saveRemote("jdbc:postgresql://db/trunkline", "pguser", "topsecret");
        store.saveLocal();

        assertTrue(store.exists());
        SetupState state = store.read();
        assertEquals(SetupMode.LOCAL, state.mode());
        assertNotNull(state.updatedAt());
        try {
            String content = Files.readString(file);
            assertFalse(content.contains("topsecret"), "切回本地模式不应残留远程密码");
            assertFalse(content.contains("jdbc:postgresql"), "切回本地模式不应残留远程 URL");
        } catch (IOException e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("saveRemote 读回完整连接信息")
    void saveRemote_roundTrip() {
        SetupStateStore store = new SetupStateStore(tempDir.resolve("setup.properties"));
        store.saveRemote("jdbc:postgresql://db:5432/trunkline", "pguser", "pgpass");
        SetupState state = store.read();
        assertEquals(SetupMode.REMOTE, state.mode());
        assertEquals("jdbc:postgresql://db:5432/trunkline", state.datasourceUrl());
        assertEquals("pguser", state.username());
        assertEquals("pgpass", state.password());
        assertNotNull(state.updatedAt());
    }

    @Test
    @DisplayName("未知 mode 值按本地模式降级，不抛异常")
    void read_unknownModeFallsBackToLocal() {
        Path file = tempDir.resolve("setup.properties");
        try {
            Files.writeString(file, "mode=GARBAGE\nupdatedAt=2026-01-01T00:00:00Z\n");
        } catch (IOException e) {
            fail(e);
        }
        SetupStateStore store = new SetupStateStore(file);
        SetupState state = store.read();
        assertEquals(SetupMode.LOCAL, state.mode());
    }
}
