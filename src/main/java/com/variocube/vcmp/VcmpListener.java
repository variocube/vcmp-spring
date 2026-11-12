package com.variocube.vcmp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface VcmpListener {

    /**
     * Additional {@code @type} ids that are accepted for this listener's message class, next to the class's own id.
     * Use it to accept a peer that sends a different type id for the same message. Incoming frames with any of the
     * ids are deserialized to the same class and handled by this listener; outgoing frames always use the class's own
     * id. An alias must be non-blank and must not collide with another type id or alias of the same handler.
     */
    String[] aliases() default {};
}
