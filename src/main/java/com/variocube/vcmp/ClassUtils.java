package com.variocube.vcmp;

import org.springframework.aop.support.AopUtils;

public class ClassUtils {
    public static Class<?> getTargetClass(Object handler) {
        Class<?> handlerClass = handler.getClass();
        if (AopUtils.isAopProxy(handler)) {
            handlerClass = AopUtils.getTargetClass(handler);
        }
        return handlerClass;
    }
}
