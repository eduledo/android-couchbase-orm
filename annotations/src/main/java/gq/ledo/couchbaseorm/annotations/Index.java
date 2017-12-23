package gq.ledo.couchbaseorm.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Created by Eduardo Ledo <eduardo.ledo@gmail.com> on 03/12/2017.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.FIELD)
public @interface Index {
    boolean unique() default false;
    String[] fields() default {};
}
