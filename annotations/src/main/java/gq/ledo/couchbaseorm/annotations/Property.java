package gq.ledo.couchbaseorm.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Created by Eduardo Ledo <eduardo.ledo@gmail.com> on 14/11/2017.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.FIELD)
public @interface Property {
    String value() default "";
    boolean cast() default false;
    String method() default "";
}
