package gq.ledo.couchbaseorm;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Database;
import com.couchbase.lite.Document;
import com.couchbase.lite.Emitter;
import com.couchbase.lite.Mapper;
import com.couchbase.lite.Query;
import com.couchbase.lite.QueryEnumerator;
import com.couchbase.lite.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Created by Eduardo Ledo <eduardo.ledo@gmail.com> on 24/11/2017.
 */

public abstract class BaseRepository<T extends CouchDocument> {

    private static final String TYPE_FIELD = "type";
    protected final Database database;
    private final View typeView;

    public BaseRepository(Database database) {
        this.database = database;
        typeView = createView(TYPE_FIELD, getType());
    }

    public T findOneById(String id) {

        Document document = database.getDocument(id);
        if (document != null) {
            return unserialize(document);
        }

        return null;
    }

    public List<T> findAll() {
        List<T> items = new ArrayList<T>();
        try {
            QueryEnumerator rows = typeView.createQuery().run();
            if (rows != null) {
                for (int i = 0; i < rows.getCount(); i++) {
                    Document document = rows.getRow(i).getDocument();
                    if (document.getProperty(TYPE_FIELD).equals(getType())) {
                        items.add(unserialize(document));
                    }
                }
            }
        } catch (CouchbaseLiteException e) {
            //e.printStackTrace();
        }
        return items;
    }

    public List<T> findBy(String field, Object value) {
        ArrayList<T> result = new ArrayList<>();
        Query query = createView(TYPE_FIELD, getType()).createQuery();
        try {
            QueryEnumerator rows = query.run();
            if (rows != null) {
                for (int i = 0; i < rows.getCount(); i++) {
                    Document document = rows.getRow(i).getDocument();
                    if (document.getProperty(field) != null && document.getProperty(field).equals(value)) {
                        result.add(unserialize(document));
                    }
                }
            }
        } catch (CouchbaseLiteException e) {
            //e.printStackTrace();
        }
        return result;
    }

    public T findOneBy(String field, Object value) {
        List<T> ts = findBy(field, value);
        if (ts.size() > 0) {
            return ts.get(0);
        }

        return null;
    }

    public T save(T object) {
        Document document = serialize(object);

        return unserialize(document);
    }

    protected View createView(final String key, final String value) {
        String viewName = "view." + key;
        View view = database.getView(viewName);
        view.setMap(new Mapper() {
            @Override
            public void map(Map<String, Object> document, Emitter emitter) {
                if (document.containsKey(key)) {
                    String val = (String) document.get(key);
                    if (val.equals(value)) {
                        HashMap<String, String> res = new HashMap<>();
                        res.put("_id", (String) document.get("_id"));
                        emitter.emit(val, res);
                    }
                }
            }
        }, "1");

        return view;
    }

    protected Document createDocument() {
        Document document = database.createDocument();
        Map<String, Object> properties = new HashMap<>();
        properties.put(TYPE_FIELD, getType());
        try {
            document.putProperties(properties);
            return document;
        } catch (CouchbaseLiteException e) {
            e.printStackTrace();
        }

        return null;
    }

    protected Document getDocument(T object) {
        Document document;
        if (object.getId() != null) {
            document = database.getDocument(object.getId());
        } else {
            document = createDocument();
        }

        return document;
    }

    abstract protected T unserialize(Document document);

    abstract protected Document serialize(T object);

    abstract protected String getType();
}
