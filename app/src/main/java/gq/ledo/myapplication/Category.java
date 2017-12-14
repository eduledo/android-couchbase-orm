package gq.ledo.myapplication;

import java.util.Objects;

import gq.ledo.couchbaseorm.annotations.Document;
import gq.ledo.couchbaseorm.annotations.Index;
import gq.ledo.couchbaseorm.annotations.Property;

/**
 * Created by Eduardo Ledo <eduardo.ledo@gmail.com> on 14/11/2017.
 */
@Document(type = "AppBundle.CouchDocument.Category")
public class Category {
    @Property("_id")
    String id;
    @Index(unique = true)
    String name;
    @Index
    String Test;

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj == null) {
            return false;
        }

        if (this.getClass().isInstance(obj)) {
            return true;
        }

        Category category = (Category) obj;
        if (id == category.id) {
            return true;
        }

        return super.equals(obj);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
