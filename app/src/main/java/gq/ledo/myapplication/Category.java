package gq.ledo.myapplication;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Database;
import com.couchbase.lite.Manager;
import com.couchbase.lite.QueryEnumerator;
import com.couchbase.lite.QueryRow;
import com.couchbase.lite.util.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import gq.ledo.couchbaseorm.annotations.Document;
import gq.ledo.couchbaseorm.annotations.Property;

/**
 * Created by Eduardo Ledo <eduardo.ledo@gmail.com> on 14/11/2017.
 */
@Document
public class Category {
    private int id;
    @Property("category_name")
    String name;

    public void s() {
        Manager manager = Manager.getSharedInstance();
        try {
            Database database = manager.getDatabase("asd");
            com.couchbase.lite.Document document = database.getDocument("");
            document.getProperty("");

        } catch (CouchbaseLiteException e) {
            e.printStackTrace();
        }
    }

    public List<Category> findAll(Database database) {

        List<Category> items = new ArrayList<>();
        try {
            QueryEnumerator rows = database
                    .getView(Category.class.getCanonicalName())
                    .createQuery()
                    .run();
            for (Iterator<QueryRow> it = rows; it.hasNext(); ) {
                QueryRow row = it.next();
                Log.w("MYAPP", "Widget named %s costs $%f", row.getKey(), ((Double)row.getValue()).doubleValue());
            }
        } catch (CouchbaseLiteException e) {
            e.printStackTrace();
        }

        return items;
    }

}
