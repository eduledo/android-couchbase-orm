package gq.ledo.couchbaseorm;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Database;
import com.couchbase.lite.Document;
import com.couchbase.lite.Emitter;
import com.couchbase.lite.Mapper;
import com.couchbase.lite.Query;
import com.couchbase.lite.QueryEnumerator;
import com.couchbase.lite.QueryRow;
import com.couchbase.lite.View;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;


/**
 * Created by Eduardo Ledo <eduardo.ledo@gmail.com> on 24/11/2017.
 */

public abstract class BaseRepository<T extends CouchDocument> {

    public static final String TYPE_FIELD = "type";
    protected static final String REVISION_FIELD = "_rev";
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

    public Collection<T> findAll() {
        Collection<T> items = new ArrayList<T>();
        try {
            QueryEnumerator rows = typeView.createQuery().run();
            if (rows != null) {
                items = Collections2.transform(Lists.newArrayList((Iterable<QueryRow>) rows), new Function<QueryRow, T>() {
                    @Override
                    public T apply(QueryRow input) {
                        return unserialize(input.getDocument());
                    }
                });
            }
        } catch (CouchbaseLiteException e) {
            //e.printStackTrace();
        }
        return items;
    }

    public Collection<T> findBy(String field, Object value) {
        HashMap<String, Object> filter = new HashMap<>();
        filter.put(field, value);

        return findBy(filter);
    }

    public Collection<T> findBy(final Map<String, Object> keyValueMap) {
        Collection<T> result = new ArrayList<>();
        Query query = createView(keyValueMap).createQuery();
        try {
            QueryEnumerator rows = query.run();
            if (rows != null) {
                Iterable<QueryRow> filteredRows = Iterables.filter(rows, new Predicate<QueryRow>() {
                    @Override
                    public boolean apply(QueryRow input) {
                        return validateRow(input.getDocument(), keyValueMap);
                    }
                });
                Collection<QueryRow> items = Lists.newArrayList(filteredRows);
                result = Collections2.transform(items, new Function<QueryRow, T>() {
                    @Override
                    public T apply(QueryRow input) {
                        return unserialize(input.getDocument());
                    }
                });
            }
        } catch (CouchbaseLiteException e) {
            //e.printStackTrace();
        }
        return result;
    }

    public T findOneBy(String field, Object value) {
        HashMap<String, Object> filter = new HashMap<>();
        filter.put(field, value);

        return findOneBy(filter);
    }

    public T findOneBy(Map<String, Object> keyValueMap) {
        Collection<T> ts = findBy(keyValueMap);
        if (ts.size() > 0) {
            return (T) ts.toArray()[0];
        }

        return null;
    }

    public T save(T object) {
        Document document = serialize(object);

        return unserialize(document);
    }

    public boolean delete(T object) {
        Document document = database.getDocument(object.getId());
        try {
            return document.delete();
        } catch (CouchbaseLiteException e) {
            e.printStackTrace();
        }
        return false;
    }

    protected View createView(final String key, final Object value) {
        String viewName = "view.{type}.{key}"
                .replace("{type}", getType())
                .replace("{key}", key);

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
        }, String.valueOf(new Random().nextLong()));

        return view;
    }

    protected View createView(final Map<String, Object> keyValueMap) {
        String random = String.valueOf(new Random().nextLong());
        String viewName = "view." + getType() + "." + random;
        View view = database.getView(viewName);
        view.setMap(new Mapper() {
            @Override
            public void map(Map<String, Object> document, Emitter emitter) {
                HashMap<String, String> res = new HashMap<>();
                int matchCount = 0;
                for (String key : keyValueMap.keySet()) {
                    Object value = keyValueMap.get(key);
                    if (document.containsKey(key)) {
                        if (value.equals(document.get(key))) {
                            matchCount++;
                        }
                    }
                }
                if (matchCount == keyValueMap.size()) {
                    res.put("_id", (String) document.get("_id"));
                    emitter.emit(keyValueMap, res);
                }
            }
        }, random);

        return view;
    }

    protected Document createDocument() {
        return database.createDocument();
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

    private boolean validateRow(Document document, Map<String, Object> keyValueMap) {
        for (String key : keyValueMap.keySet()) {
            Object value = keyValueMap.get(key);
            Object property = document.getProperty(key);
            if (property == null) {
                return false;
            }
            if (!property.equals(value)) {
                return false;
            }
        }

        return true;
    }

    abstract protected T unserialize(Document document);

    abstract protected Document serialize(T object);

    abstract protected String getType();
}
