package org.unreal.modelrouter.config.dependency;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 依赖检查报告：某个待删除对象当前被谁引用、能否删除。
 *
 * <p>{@code blockers} 非空即代表<strong>不允许删除</strong>；{@code warnings} 仅为提示
 * （例如密钥的历史用量），不阻断删除。</p>
 *
 * @author JAiRouter Team
 * @since 3.3.2
 */
public class DependencyReport {

    /** 目标类型：provider / instance / pool / template / apiKey */
    private String targetType;

    /** 目标标识 */
    private String targetId;

    /** 目标展示名 */
    private String targetName;

    /** 阻断项（引用方清单） */
    private List<DependencyBlocker> blockers = new ArrayList<>();

    /** 提示项（不阻断删除） */
    private List<String> warnings = new ArrayList<>();

    public DependencyReport() {
    }

    public DependencyReport(final String targetType, final String targetId, final String targetName) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.targetName = targetName;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(final String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(final String targetId) {
        this.targetId = targetId;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(final String targetName) {
        this.targetName = targetName;
    }

    public List<DependencyBlocker> getBlockers() {
        return blockers;
    }

    public void setBlockers(final List<DependencyBlocker> blockers) {
        this.blockers = blockers == null ? new ArrayList<>() : blockers;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(final List<String> warnings) {
        this.warnings = warnings == null ? new ArrayList<>() : warnings;
    }

    /** 是否允许删除（无阻断项即可删除） */
    public boolean isDeletable() {
        return blockers.isEmpty();
    }

    /** 阻断项总数 */
    public int getBlockerCount() {
        return blockers.size();
    }

    /**
     * 生成可读的阻断提示：按引用方类型分组列出名称与处理建议。
     *
     * @return 面向控制台用户的提示文案
     */
    public String summary() {
        if (blockers.isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        final String label = targetLabel();
        sb.append(label).append("「").append(targetName == null ? targetId : targetName).append("」")
                .append("仍被 ").append(blockers.size()).append(" 项引用，无法删除：");
        sb.append(groupedByType());
        sb.append("。请先处理上述引用后再删除。");
        return sb.toString();
    }

    /** 按引用方类型分组的可读清单 */
    private String groupedByType() {
        return blockers.stream()
                .collect(Collectors.groupingBy(b -> null == b.getType() ? "other" : b.getType(),
                        java.util.LinkedHashMap::new,
                        Collectors.mapping(b -> (b.getName() == null ? b.getId() : b.getName())
                                + (b.getReason() == null ? "" : "（" + b.getReason() + "）"),
                                Collectors.joining("、"))))
                .entrySet().stream()
                .map(e -> typeLabel(e.getKey()) + "：" + e.getValue())
                .collect(Collectors.joining("；"));
    }

    private String targetLabel() {
        switch (targetType == null ? "" : targetType) {
            case "provider":
                return "模型供应商";
            case "instance":
                return "已接入模型";
            case "pool":
                return "模型分组";
            case "template":
                return "供应商模板";
            case "apiKey":
                return "密钥";
            default:
                return "对象";
        }
    }

    private String typeLabel(final String type) {
        switch (type) {
            case "provider":
                return "模型供应商";
            case "instance":
                return "已接入模型";
            case "pool":
                return "模型分组";
            case "apiKey":
                return "密钥";
            default:
                return "其他引用";
        }
    }
}
