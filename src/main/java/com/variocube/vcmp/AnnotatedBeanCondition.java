package com.variocube.vcmp;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.lang.annotation.Annotation;

@RequiredArgsConstructor
public class AnnotatedBeanCondition implements Condition {

    private final Class<? extends Annotation> annotationType;

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        if (beanFactory != null) {
            return beanFactory.getBeanNamesForAnnotation(annotationType).length > 0;
        }
        return false;
    }
}
