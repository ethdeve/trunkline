/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.unreal.modelrouter.persistence.jpa.entity.JwtAccountEntity;
import org.unreal.modelrouter.persistence.jpa.repository.JwtAccountRepository;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 首次部署初始化服务单元测试。
 *
 * <p>「是否已初始化」完全由 JWT 账户数决定；远程模式在数据源切换前后必须要求重启；
 * 一旦已有管理员，数据源修改与管理员创建一律 409。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("SetupService 首次部署初始化服务")
class SetupServiceTest {

    private static final String H2_URL = "jdbc:h2:file:./data/trunkline;DB_CLOSE_DELAY=-1;MODE=MySQL";
    private static final String PG_URL = "jdbc:postgresql://localhost:5432/trunkline";
    /** 通过 SecretKeyValidator 的高熵 + 四类字符校验 */
    private static final String STRONG_PASSWORD = "Kd9#pR3!wM7@xQ2v";

    @TempDir
    Path tempDir;

    private SetupStateStore stateStore;
    private JwtAccountRepository jwtAccountRepository;
    private PasswordEncoder passwordEncoder;
    private SetupService service;

    @BeforeEach
    void setUp() {
        stateStore = new SetupStateStore(tempDir.resolve("setup.properties"));
        jwtAccountRepository = mock(JwtAccountRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new SetupService(stateStore, jwtAccountRepository, passwordEncoder, new ObjectMapper());
        ReflectionTestUtils.setField(service, "currentDatasourceUrl", H2_URL);
    }

    @Nested
    @DisplayName("status() 初始化状态判定")
    class StatusTests {

        @Test
        @DisplayName("无管理员且未选模式 → 未初始化、modeSelected=false")
        void status_notInitialized() {
            when(jwtAccountRepository.count()).thenReturn(0L);
            SetupService.SetupStatus status = service.status();
            assertFalse(status.initialized());
            assertEquals("LOCAL", status.mode());
            assertFalse(status.modeSelected());
            assertFalse(status.restartRequired());
            assertEquals("H2", status.datasource());
        }

        @Test
        @DisplayName("已存在管理员 → initialized=true")
        void status_initialized() {
            when(jwtAccountRepository.count()).thenReturn(1L);
            SetupService.SetupStatus status = service.status();
            assertTrue(status.initialized());
            assertFalse(status.restartRequired());
        }

        @Test
        @DisplayName("已选远程模式但当前仍是 H2 → restartRequired=true")
        void status_remoteSelectedRequiresRestart() {
            when(jwtAccountRepository.count()).thenReturn(0L);
            stateStore.saveRemote(PG_URL, "pguser", "pgpass");
            SetupService.SetupStatus status = service.status();
            assertFalse(status.initialized());
            assertTrue(status.modeSelected());
            assertEquals("REMOTE", status.mode());
            assertTrue(status.restartRequired());
        }

        @Test
        @DisplayName("已切到 PG 且状态匹配 → restartRequired=false")
        void status_remoteSwitched() {
            when(jwtAccountRepository.count()).thenReturn(0L);
            stateStore.saveRemote(PG_URL, "pguser", "pgpass");
            ReflectionTestUtils.setField(service, "currentDatasourceUrl", PG_URL);
            SetupService.SetupStatus status = service.status();
            assertFalse(status.restartRequired());
            assertEquals("PostgreSQL", status.datasource());
        }

        @Test
        @DisplayName("状态响应不回传数据库密码")
        void status_neverExposesPassword() {
            when(jwtAccountRepository.count()).thenReturn(0L);
            stateStore.saveRemote(PG_URL, "pguser", "super-secret-password");
            SetupService.SetupStatus status = service.status();
            assertFalse(status.toString().contains("super-secret-password"));
        }
    }

    @Nested
    @DisplayName("configureDatasource() 数据源配置")
    class DatasourceTests {

        @Test
        @DisplayName("本地模式落盘且无需重启")
        void configureDatasource_local() {
            when(jwtAccountRepository.count()).thenReturn(0L);
            SetupService.DatasourceResult result = service.configureDatasource(SetupMode.LOCAL, null, null, null);
            assertEquals("LOCAL", result.mode());
            assertFalse(result.restartRequired());
            assertTrue(stateStore.exists());
            assertEquals(SetupMode.LOCAL, stateStore.read().mode());
        }

        @Test
        @DisplayName("本地模式在当前为 PG 时要求重启切回 H2")
        void configureDatasource_localRestartWhenOnPg() {
            when(jwtAccountRepository.count()).thenReturn(0L);
            ReflectionTestUtils.setField(service, "currentDatasourceUrl", PG_URL);
            SetupService.DatasourceResult result = service.configureDatasource(SetupMode.LOCAL, null, null, null);
            assertTrue(result.restartRequired());
        }

        @Test
        @DisplayName("远程模式拒绝非 PostgreSQL URL")
        void configureDatasource_remoteRejectsNonPostgres() {
            when(jwtAccountRepository.count()).thenReturn(0L);
            SetupException ex = assertThrows(SetupException.class,
                    () -> service.configureDatasource(SetupMode.REMOTE, "jdbc:h2:mem:test", "u", "p"));
            assertEquals(HttpStatus.BAD_REQUEST, ex.status());
            assertEquals("SETUP_INVALID_INPUT", ex.errorCode());
        }

        @Test
        @DisplayName("远程模式拒绝空白连接信息")
        void configureDatasource_remoteRejectsBlank() {
            when(jwtAccountRepository.count()).thenReturn(0L);
            assertThrows(SetupException.class,
                    () -> service.configureDatasource(SetupMode.REMOTE, null, null, null));
            assertThrows(SetupException.class,
                    () -> service.configureDatasource(SetupMode.REMOTE, PG_URL, "", "p"));
            assertThrows(SetupException.class,
                    () -> service.configureDatasource(SetupMode.REMOTE, PG_URL, "u", ""));
        }

        @Test
        @DisplayName("已初始化后修改数据源返回 409")
        void configureDatasource_conflictWhenInitialized() {
            when(jwtAccountRepository.count()).thenReturn(1L);
            SetupException ex = assertThrows(SetupException.class,
                    () -> service.configureDatasource(SetupMode.LOCAL, null, null, null));
            assertEquals(HttpStatus.CONFLICT, ex.status());
            assertEquals("SETUP_ALREADY_INITIALIZED", ex.errorCode());
        }

        @Test
        @DisplayName("null 模式被拒绝")
        void configureDatasource_nullMode() {
            when(jwtAccountRepository.count()).thenReturn(0L);
            SetupException ex = assertThrows(SetupException.class,
                    () -> service.configureDatasource(null, null, null, null));
            assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        }
    }

    @Nested
    @DisplayName("initializeAdmin() 创建首个管理员")
    class AdminTests {

        @Test
        @DisplayName("已初始化 → 409 拒绝重复创建")
        void initializeAdmin_conflict() {
            when(jwtAccountRepository.count()).thenReturn(1L);
            SetupException ex = assertThrows(SetupException.class,
                    () -> service.initializeAdmin("admin", STRONG_PASSWORD));
            assertEquals(HttpStatus.CONFLICT, ex.status());
            assertEquals("SETUP_ALREADY_INITIALIZED", ex.errorCode());
            verify(jwtAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("远程模式未重启 → 409 要求先重启")
        void initializeAdmin_restartRequired() {
            when(jwtAccountRepository.count()).thenReturn(0L);
            stateStore.saveRemote(PG_URL, "pguser", "pgpass");
            SetupException ex = assertThrows(SetupException.class,
                    () -> service.initializeAdmin("admin", STRONG_PASSWORD));
            assertEquals(HttpStatus.CONFLICT, ex.status());
            assertEquals("SETUP_RESTART_REQUIRED", ex.errorCode());
        }

        @Test
        @DisplayName("非法用户名被拒绝")
        void initializeAdmin_invalidUsername() {
            when(jwtAccountRepository.count()).thenReturn(0L);
            assertThrows(SetupException.class, () -> service.initializeAdmin("ab", STRONG_PASSWORD));
            assertThrows(SetupException.class, () -> service.initializeAdmin("bad name!", STRONG_PASSWORD));
        }

        @Test
        @DisplayName("少于 12 位被拒绝，且提示写明实际位数")
        void initializeAdmin_tooShort() {
            when(jwtAccountRepository.count()).thenReturn(0L);
            SetupException ex = assertThrows(SetupException.class,
                    () -> service.initializeAdmin("admin", "short"));
            assertEquals(HttpStatus.BAD_REQUEST, ex.status());
            assertTrue(ex.getMessage().contains("至少需要 12 位"), ex.getMessage());
            assertTrue(ex.getMessage().contains("当前 5 位"), ex.getMessage());
        }

        @Test
        @DisplayName("14 位但含弱模式 → 精确指出片段，不再误报『不足 12 位』")
        void initializeAdmin_weakFragmentReportsExactReason() {
            when(jwtAccountRepository.count()).thenReturn(0L);
            // aaa@123456$123 共 14 位，长度达标；命中弱片段 123456
            SetupException ex = assertThrows(SetupException.class,
                    () -> service.initializeAdmin("admin", "aaa@123456$123"));
            assertEquals(HttpStatus.BAD_REQUEST, ex.status());
            assertFalse(ex.getMessage().contains("至少 12 位"), ex.getMessage());
            assertFalse(ex.getMessage().contains("不足"), ex.getMessage());
            assertTrue(ex.getMessage().contains("123456"), ex.getMessage());
        }

        @Test
        @DisplayName("空密码被拒绝")
        void initializeAdmin_emptyPassword() {
            when(jwtAccountRepository.count()).thenReturn(0L);
            SetupException ex = assertThrows(SetupException.class,
                    () -> service.initializeAdmin("admin", ""));
            assertTrue(ex.getMessage().contains("不能为空"), ex.getMessage());
        }

        @Test
        @DisplayName("默认弱口令被拒绝")
        void initializeAdmin_defaultPassword() {
            when(jwtAccountRepository.count()).thenReturn(0L);
            assertThrows(SetupException.class,
                    () -> service.initializeAdmin("admin", "ChangeMeOnFirstStartup123456"));
        }

        @Test
        @DisplayName("用户名已存在被拒绝")
        void initializeAdmin_duplicateUsername() {
            when(jwtAccountRepository.count()).thenReturn(0L);
            when(jwtAccountRepository.existsByUsername("admin")).thenReturn(true);
            SetupException ex = assertThrows(SetupException.class,
                    () -> service.initializeAdmin("admin", STRONG_PASSWORD));
            assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        }

        @Test
        @DisplayName("成功创建 ADMIN+USER 角色、密码加密的账户")
        void initializeAdmin_success() {
            when(jwtAccountRepository.count()).thenReturn(0L);
            when(jwtAccountRepository.existsByUsername("admin")).thenReturn(false);
            when(passwordEncoder.encode(STRONG_PASSWORD)).thenReturn("{bcrypt}hashed");

            SetupService.AdminResult result = service.initializeAdmin("admin", STRONG_PASSWORD);

            assertEquals("admin", result.username());
            ArgumentCaptor<JwtAccountEntity> captor = ArgumentCaptor.forClass(JwtAccountEntity.class);
            verify(jwtAccountRepository).save(captor.capture());
            JwtAccountEntity saved = captor.getValue();
            assertEquals("admin", saved.getUsername());
            assertEquals("{bcrypt}hashed", saved.getPassword());
            assertEquals("[\"ADMIN\",\"USER\"]", saved.getRoles());
            assertEquals(Boolean.TRUE, saved.getEnabled());
            assertEquals("系统管理员", saved.getName());
        }
    }
}
