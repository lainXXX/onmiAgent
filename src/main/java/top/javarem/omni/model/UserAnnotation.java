package top.javarem.omni.model;

/**
 * 用户批注结构
 *
 * @param preview 选中的 preview 内容
 * @param notes   用户手写备注
 */
public record UserAnnotation(
    String preview,
    String notes
) {}
