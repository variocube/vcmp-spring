package com.variocube.vcmp;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class MethodAnnotationUtils {

    public static <T extends Annotation> void invokeMethodWithAnnotation(Object target, Class<T> annotationClass, Object... args) throws InvocationTargetException, IllegalAccessException {
        Class<?> targetClass = target.getClass();
        while (targetClass != null) {
            for (Method method : targetClass.getMethods()) {
                for (Annotation annotation : method.getDeclaredAnnotations()) {
                    if (annotationClass.isAssignableFrom(annotation.annotationType())) {
                        method.invoke(target, args);
                    }
                }
            }
            targetClass = targetClass.getSuperclass();
        }
    }
}
