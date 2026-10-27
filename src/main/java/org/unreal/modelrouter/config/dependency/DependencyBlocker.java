package org.unreal.modelrouter.config.dependency;

import java.util.Objects;

/**
 * 依赖阻断项：描述「待删除对象被谁引用」。
 *
 * <p>用于在删除前把「还有谁在用我」这件事讲清楚，而不是只丢一句「存在关联，无法删除」。</p>
 *
 * @author JAiRouter Team
 * @since 3.3.2
 */
public class DependencyBlocker {

    /** 引用方类型：provider / instance / pool / apiKey */
    private String type;

    /** 引用方主键（实例为数据库主键、分组为 poolName、密钥为 keyId） */
    private String id;

    /** 引用方展示名 */
    private String name;

    /** 引用原因（人类可读） */
    private String reason;

    public DependencyBlocker() {
    }

    public DependencyBlocker(final String type, final String id, final String name, final String reason) {
        this.type = type;
        this.id = id;
        this.name = name;
        this.reason = reason;
    }

    public String getType() {
        return type;
    }

    public void setType(final String type) {
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(final String reason) {
        this.reason = reason;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof DependencyBlocker)) {
            return false;
        }
        final DependencyBlocker other = (DependencyBlocker) obj;
        return Objects.equals(type, other.type) && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, id);
    }

    @Override
    public String toString() {
        return type + ":" + id;
    }
}
