package com.yhh.yojcodesanbox.security;

import java.security.Permission;

/**
 * @author hyh
 * @date 2024/6/6
 */
public class DefaultSecurityManager extends SecurityManager{
    @Override
    public void checkPermission(Permission perm) {
        System.out.println("不做任何权限校验");
        super.checkPermission(perm);
    }
}
