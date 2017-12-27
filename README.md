# android-couchbase-orm
A simple annotation processor that generates POJOS extending your own classes, for mapping to Couchbase-Lite documents.


## Installation
In your project's root ```build.gradle``` add [Jitpack](https://jitpack.io) repository
```groovy
allprojects {
    repositories {
        google()
        jcenter()
        maven { url 'https://jitpack.io' }
    }
}
```
Then, in your app's ```build.gradle``` add the dependencies
```groovy
// Couchbase
implementation "com.couchbase.lite:couchbase-lite-android:1.4.1"

// android-couchbase-orm
implementation "gq.ledo.android-couchbase-orm:annotations:0.1.12"
annotationProcessor "gq.ledo.android-couchbase-orm:processor:0.1.12"
```
## Usage

Create your model classes annotated with ```gq.ledo.couchbaseorm.annotations.Document```
```java
package my.namespace.model

@Document(type="MyNamespace.Author")
public class Author {
    private String id;
    private String name;
}
```

This will generate a proxy class ```my.namespace.model.proxy.Author``` extending ```my.namespace.model.Author```
```java
package my.namespace.model.proxy

public class Author extends my.namespace.model.Author {
    public static final String ID = "id";
    public static final String NAME = "name";

    private String id;
    private String name;

    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
```
a repository class ```my.namespace.model.proxy.AuthorRepository``` extending ```gq.ledo.couchbaseorm.BaseRepository``` 
```java
package gq.ledo.myapplication.proxy;

public class AuthorRepository extends BaseRepository<Author> {
    public AuthorRepository(Database database) {
        super(database);
    }

    @Override
    protected String getType() {
        return "AppBundle.CouchDocument.Author";
    }

    @Override
    protected Author unserialize(Document document) {
        Author author = new Author();
        author.setId(document.getId());
        author.setName(document.getProperty(author.NAME) == null ? null : ((java.lang.String) document.getProperty(author.NAME)).toString());
        author.setDescription(document.getProperty(author.DESCRIPTION) == null ? null : ((java.lang.String) document.getProperty(author.DESCRIPTION)).toString());
        return author;
    }

    @Override
    protected Document serialize(Author author) {
        Document document = getDocument(author);
        HashMap<String, Object> properties = new HashMap<String,Object>();
        properties.put(author.NAME, author.getName());
        properties.put(author.DESCRIPTION, author.getDescription());
        if (document != null) {
            try {
                document.putProperties(properties);
            } catch (CouchbaseLiteException e) {
                e.printStackTrace();
            }
        }
        return document;
    }
}

```
and a helper class ```gq.ledo.couchbaseorm.DBHelper``` for accessing all repositories

```java
package gq.ledo.couchbaseorm;

public class DBHelper {
    gq.ledo.myapplication.proxy.AuthorRepository authorRepository;

    public DBHelper(gq.ledo.myapplication.proxy.AuthorRepository authorRepository) {
        this.authorRepository = authorRepository;
    }

    public gq.ledo.myapplication.proxy.AuthorRepository getAuthorRepository() {
        return authorRepository;
    }

    public static DBHelper create(Database database) {
        gq.ledo.myapplication.proxy.AuthorRepository authorRepository = new gq.ledo.myapplication.proxy.AuthorRepository(database);
        return new DBHelper(authorRepository);
    }
}

```

The BaseRepository has a few common methods for handling basic CRUD operations: 
   - findOneById(String id)
   - findAll()
   - findBy(String field, Object value)
   - findBy(final Map<String, Object> keyValueMap)
   - findOneBy(String field, Object value)
   - findOneBy(Map<String, Object> keyValueMap)
   
## Indices
Using the ```gq.ledo.couchbaseorm.annotations.Index``` anotation you have the option of generating additional helper methods (with corresponding views) in the repositories.

```java
package my.namespace.model

@Document(type="MyNamespace.Author")
public class Author {
    private String id;
    @Index(unique = true)
    private String name;
    @Index
    private String description;
}
```
This will generate the following repository
```java
package gq.ledo.myapplication.proxy;

public class AuthorRepository extends BaseRepository<Author> {
    public AuthorRepository(Database database) {
        super(database);
    }

    @Override
    protected String getType() {
        return "AppBundle.CouchDocument.Author";
    }

    @Override
    protected Author unserialize(Document document) {
        Author author = new Author();
        author.setId(document.getId());
        author.setName(document.getProperty(author.NAME) == null ? null : ((java.lang.String) document.getProperty(author.NAME)).toString());
        author.setDescription(document.getProperty(author.DESCRIPTION) == null ? null : ((java.lang.String) document.getProperty(author.DESCRIPTION)).toString());
        return author;
    }

    @Override
    protected Document serialize(Author author) {
        Document document = getDocument(author);
        HashMap<String, Object> properties = new HashMap<String,Object>();
        properties.put(author.NAME, author.getName());
        properties.put(author.DESCRIPTION, author.getDescription());
        if (document != null) {
            try {
                document.putProperties(properties);
            } catch (CouchbaseLiteException e) {
                e.printStackTrace();
            }
        }
        return document;
    }

    public Author findOneByName(String name) {
        return findOneBy(Author.NAME, name);
    }

    public List<Author> findByDescription(String description) {
        return findBy(Author.DESCRIPTION, description);
    }
}
```
If The ```unique``` flag is set to ```true``` then the method will be findOneBy* instead of findBy* and will return a single entity instead of a List.

### Multiple field Indices

You can create multiple field indices adding an Index annotation to the Document annotation:
```java
@Document(type = "AppBundle.CouchDocument.Author", indexes = {
        @Index(fields = {"name", "description"}, unique = true)
})
public class Author {
    private String id;
    @Index(unique = true)
    private String name;
    @Index
    private String description;
}

```
This will generate the following repository class
```java
package gq.ledo.myapplication.proxy;

public class AuthorRepository extends BaseRepository<Author> {
    public AuthorRepository(Database database) {
        super(database);
    }

    @Override
    protected String getType() {
        return "AppBundle.CouchDocument.Author";
    }

    @Override
    protected Author unserialize(Document document) {
        Author author = new Author();
        author.setId(document.getId());
        author.setName(document.getProperty(author.NAME) == null ? null : ((java.lang.String) document.getProperty(author.NAME)).toString());
        author.setDescription(document.getProperty(author.DESCRIPTION) == null ? null : ((java.lang.String) document.getProperty(author.DESCRIPTION)).toString());
        return author;
    }

    @Override
    protected Document serialize(Author author) {
        Document document = getDocument(author);
        HashMap<String, Object> properties = new HashMap<String,Object>();
        properties.put(author.NAME, author.getName());
        properties.put(author.DESCRIPTION, author.getDescription());
        if (document != null) {
            try {
                document.putProperties(properties);
            } catch (CouchbaseLiteException e) {
                e.printStackTrace();
            }
        }
        return document;
    }

    public Author findOneByNameAndDescription(String name, String description) {
        HashMap<String, Object> keyValueMap = new HashMap<String, Object>();
        keyValueMap.put(Author.NAME, name);
        keyValueMap.put(Author.DESCRIPTION, description);
        return findOneBy(keyValueMap);
    }

    public Author findOneByName(String name) {
        return findOneBy(Author.NAME, name);
    }

    public List<Author> findByDescription(String description) {
        return findBy(Author.DESCRIPTION, description);
    }
}

```
# TODO:
- Save unique
- OneToMany/ManyToOne relations
- ManyToMany relations