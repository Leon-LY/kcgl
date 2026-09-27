package com.kcgl.common.util;

import java.security.SecureRandom;

/**
 * 一次性初始密码生成：空库引导管理员与管理员重置密码共用同一实现，
 * 保证密码策略（长度/无歧义字母表）全系统单点维护。
 */
public final class PasswordGenerator {

    /** 去掉易混淆字符（0/O/1/l/I）的无歧义字母表 */
    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    static final int LENGTH = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordGenerator() {
    }

    public static String generate() {
        StringBuilder password = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}
