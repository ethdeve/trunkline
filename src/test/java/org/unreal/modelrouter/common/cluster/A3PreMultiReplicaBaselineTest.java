package org.unreal.modelrouter.common.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.common.cluster.MultiReplicaSharedStateGate;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A3-pre 多副本一致性前置基线测试。
 *
 * <p>固化两件容易回归、后果又重的事情：① 生产 profile 下 {@code trunkline.cluster.replicas}
 * 必须默认显式单副本（=1），否则单副本 K8s Pod 会被 K8s 环境探测误判为多副本，在
 * {@code mode=FAIL} 下启动失败（误伤）；② 生产 {@code shared-state-check.mode} 必须为 FAIL，
 * 保证多副本 + 共享态开关缺失时 fail-fast。</p>
 *
 * @see MultiReplicaSharedStateGate
 */
class A3PreMultiReplicaBaselineTest {

    private Map<String, Object> loadProdYml() {
        try (InputStream in = A3PreMultiReplicaBaselineTest.class
                .getClassLoader().getResourceAsStream("application-prod.yml")) {
            if (in == null) {
                throw new IllegalStateException("application-prod.yml 不在测试 classpath 上");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> loaded = new Yaml().load(in);
            return loaded;
        } catch (Exception e) {
            throw new IllegalStateException("加载 application-prod.yml 失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Object getByPath(final Map<String, Object> root, final String path) {
        Map<String, Object> cur = root;
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            Object v = cur.get(parts[i]);
            if (v == null) {
                return null;
            }
            if (i == parts.length - 1) {
                return v;
            }
            if (!(v instanceof Map)) {
                return null;
            }
            cur = (Map<String, Object>) v;
        }
        return null;
    }

    @Test
    @DisplayName("A3-pre：生产 profile 下副本数默认显式单副本，不回退 K8s 探测")
    void prodReplicasDefaultsToSingleReplica() {
        Map<String, Object> yml = loadProdYml();
        Object replicas = getByPath(yml, "trunkline.cluster.replicas");
        // 占位符默认值必须是 1：确保单副本 K8s Pod 不被误判多副本而 fail-fast
        assertEquals("${TRUNKLINE_REPLICAS:1}", String.valueOf(replicas));
    }

    @Test
    @DisplayName("A3-pre：生产 profile 下共享态自检默认 FAIL")
    void prodSharedStateCheckModeIsFail() {
        Map<String, Object> yml = loadProdYml();
        Object mode = getByPath(yml, "trunkline.cluster.shared-state-check.mode");
        assertEquals("FAIL", String.valueOf(mode));
    }

    @Test
    @DisplayName("A3-pre：显式单副本（replicas=1）+ K8s 环境变量不触发多副本误判")
    void explicitSingleReplicaIgnoresK8sEnv() {
        int replicas = MultiReplicaSharedStateGate.parseReplicas("1");
        Map<String, String> k8sEnv = new LinkedHashMap<>();
        k8sEnv.put("KUBERNETES_SERVICE_HOST", "10.0.0.1");
        k8sEnv.put("POD_NAME", "trunkline-0");
        assertFalse(MultiReplicaSharedStateGate.detectMultiReplica(replicas, k8sEnv),
                "replicas=1 时即便处于 K8s 环境也不应判为多副本");
    }
}
