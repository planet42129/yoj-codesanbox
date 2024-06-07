package com.yhh.yojcodesanbox.security;

import java.security.Permission;

/**
 * @author hyh
 * @date 2024/6/6
 */
public class DenySecurityManager extends SecurityManager{
    @Override
    public void checkPermission(Permission perm) {
        System.out.println("无权限");
        super.checkPermission(perm);
    }
}
