package gq.ledo.couchbaseorm.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Created by Eduardo Ledo <eduardo.ledo@gmail.com> on 14/11/2017.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Document {
    String type();
    Index[] indexes() default {};
}
