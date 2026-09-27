package com.kcgl.module.user;

/**
 * 三级角色（需求一）：1 管理员 / 2 可编辑 / 3 仅查看。
 * 权限名与 Spring Security hasRole("ADMIN") 直接对应。
 */
public enum UserRole {

    ADMIN(1),
    EDITOR(2),
    VIEWER(3);

    private final int id;

    UserRole(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static UserRole of(int id) {
        for (UserRole role : values()) {
            if (role.id == id) {
                return role;
            }
        }
        throw new IllegalArgumentException("未知角色 id: " + id);
    }
}
