package gq.ledo.android.processor;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Database;
import com.google.common.base.CaseFormat;
import com.google.common.base.Joiner;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Ordering;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

import gq.ledo.couchbaseorm.BaseRepository;
import gq.ledo.couchbaseorm.CouchDocument;
import gq.ledo.couchbaseorm.annotations.Document;
import gq.ledo.couchbaseorm.annotations.Index;
import gq.ledo.couchbaseorm.annotations.Property;

@SupportedSourceVersion(SourceVersion.RELEASE_7)
public class DocumentProcessor extends AbstractProcessor {

    private Messager messager;
    private Filer filer;
    private Elements elementUtils;
    private static final String PACKAGE_SUFFIX = ".proxy";
    private static final String REPO_SUFFIX = "Repository";
    private HashMap<String, String[]> primitives = new HashMap<String, String[]>() {{
        put("byte", new String[]{"Byte", "byteValue()"});
        put("short", new String[]{"Short", "shortValue()"});
        put("int", new String[]{"Integer", "intValue()"});
        put("long", new String[]{"Long", "longValue()"});
        put("float", new String[]{"Float", "floatValue()"});
        put("double", new String[]{"Double", "doubleValue()"});
        put("char", new String[]{"Character", "charValue()"});
        put("java.lang.String", new String[]{"java.lang.String", "toString()"});
    }};

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        messager = processingEnv.getMessager();
        filer = processingEnv.getFiler();
        elementUtils = processingEnv.getElementUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

        TreeMap<TypeSpec, String> helpers = new TreeMap<>(new Comparator<TypeSpec>() {
            @Override
            public int compare(TypeSpec o1, TypeSpec o2) {
                return Ordering.natural().compare(o1.name, o2.name);
            }
        });

        TypeSpec.Builder dbHelperBuilder = TypeSpec.classBuilder("DBHelper")
                .addModifiers(Modifier.PUBLIC);
        String repoPackageName = "gq.ledo.couchbaseorm";

        for (Element element : roundEnv.getElementsAnnotatedWith(Document.class)) {
            Set<Element> indexes = new HashSet<>();
            if (element.getKind() != ElementKind.CLASS) {
                messager.printMessage(Diagnostic.Kind.ERROR, "Can be applied to class.");
                return true;
            }
            Document annotation = element.getAnnotation(Document.class);
            TypeElement typeElement = (TypeElement) element;
            // Get package
            String packageName = elementUtils.getPackageOf(typeElement).getQualifiedName().toString();
            // Get public fields
            List<? extends Element> enclosedElements = typeElement.getEnclosedElements();
            String typeVarName = typeElement.getSimpleName().toString().toLowerCase();

            // Create wrapper
            TypeSpec.Builder proxyBuilder = getProxyBuilder(typeElement);

            // Create repository
            TypeSpec.Builder repoBuilder = getRepoBuilder(typeElement);

            CodeBlock.Builder unserializeCode = CodeBlock.builder()
                    .addStatement("$L $L = new $L()",
                            typeElement.getSimpleName(),
                            typeVarName,
                            typeElement.getSimpleName()
                    );
            CodeBlock.Builder serializeCode = CodeBlock.builder()
                    .addStatement("$T document = getDocument($L)",
                            com.couchbase.lite.Document.class,
                            typeElement.getSimpleName().toString().toLowerCase()
                    )
                    .addStatement("$T<$T, Object> properties = new $T<$T,Object>()",
                            HashMap.class,
                            String.class,
                            HashMap.class,
                            String.class
                    );
            String serializeTryCatch = Joiner.on('\n').join(
                    "if (document != null) {",
                    "    try {",
                    "        document.putProperties(properties);",
                    "    } catch ($T e) {",
                    "        e.printStackTrace();",
                    "    }",
                    "}",
                    "");

            MethodSpec helperConstructor = generateHelperConstructor();
            MethodSpec getType = generateHelperGetTypeMethod(annotation);
            if (annotation.indexes().length > 0) {
                indexes.add(element);
            }
            addMethod(repoBuilder, getType);
            addMethod(repoBuilder, helperConstructor);

            for (Element el : enclosedElements) {

                if (el.getKind() == ElementKind.FIELD) {
                    String fieldname = el.getSimpleName().toString();
                    TypeName fieldType = getTypeName(el);

                    FieldSpec fieldSpec = FieldSpec.builder(fieldType, fieldname, Modifier.PRIVATE)
                            .build();

                    MethodSpec getter = generateProxyGetter(fieldname, fieldType);
                    MethodSpec setter = generateProxySetter(fieldname, fieldType);

                    addField(proxyBuilder, fieldSpec);
                    addMethod(proxyBuilder, getter);
                    addMethod(proxyBuilder, setter);


                    String docFieldName = fieldname;
                    Property property = el.getAnnotation(Property.class);
                    if (property != null) {
                        String value = property.value();
                        if (value.trim().length() > 0) {
                            docFieldName = value;
                        }
                    }
                    Index index = el.getAnnotation(Index.class);
                    // TODO: Save unique
                    // TODO: OneToMany
                    // TODO: ManyToOne
                    // TODO: ManyToMany
                    if (index != null) {
                        indexes.add(el);
                    }
                    FieldSpec fieldNameSpec = FieldSpec.builder(String.class, fieldname.toUpperCase())
                            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                            .initializer("$S", docFieldName)
                            .build();
                    addField(proxyBuilder, fieldNameSpec);
                    if (docFieldName.equals("id") || docFieldName.equals("_id")) {
                        unserializeCode.addStatement("$N.$N(document.getId())",
                                typeVarName,
                                setter
                        );
                    } else if (primitives.containsKey(fieldType.toString())) {
                        String st = "$N.$N(document.getProperty($L.$L) == null ? null : (($L) document.getProperty($L.$L)).$L)";
                        String[] strings = primitives.get(fieldType.toString());
                        if (strings != null) {
                            unserializeCode.addStatement(st,
                                    typeVarName,
                                    setter,
                                    typeVarName,
                                    fieldname.toUpperCase(),
                                    strings[0],
                                    typeVarName,
                                    fieldname.toUpperCase(),
                                    strings[1]
                            );
                            serializeCode.addStatement("properties.put($N.$L, $N.$N())",
                                    typeVarName,
                                    fieldname.toUpperCase(),
                                    typeVarName,
                                    getter);
                        }
//                    } else {
//                        unserializeCode.addStatement("$N.$N(document.getProperty($S) == null ? null : $L.unserialize(($T) document.getProperty($S)))",
//                                typeVarName,
//                                setter,
//                                docFieldName,
//                                getProxyFQDN(el),
//                                com.couchbase.lite.Document.class,
//                                docFieldName
//                        );
                    }
                }
            }
            unserializeCode.addStatement("return $L", typeVarName);
            serializeCode.add(serializeTryCatch, CouchbaseLiteException.class);
            serializeCode.addStatement("return document");

            MethodSpec unserialize = generateRepoUnserialize(typeElement, packageName, unserializeCode.build());
            MethodSpec serialize = generateRepoSerialize(typeElement, packageName, serializeCode.build());

            addMethod(repoBuilder, unserialize);
            addMethod(repoBuilder, serialize);

            TypeSpec proxy = proxyBuilder.build();
            TypeVariableName typeVariable = TypeVariableName.get(proxy.name);
            buildFinders(repoBuilder, indexes, typeVariable);

            ParameterizedTypeName t = ParameterizedTypeName.get(ClassName.get(BaseRepository.class), typeVariable);
            repoBuilder.superclass(t);
            TypeSpec helper = repoBuilder.build();

            helpers.put(helper, packageName + PACKAGE_SUFFIX);
            try {
                writeClassToDisk(packageName + PACKAGE_SUFFIX, proxy);
                writeClassToDisk(packageName + PACKAGE_SUFFIX, helper);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        buildDBHelper(dbHelperBuilder, helpers);
        try {
            writeClassToDisk(repoPackageName, dbHelperBuilder.build());
        } catch (IOException e) {
            e.printStackTrace();
        }

        return true;
    }

    private void addMethod(TypeSpec.Builder builder, MethodSpec method) {
        if (!hasMethod(builder, method)) {
            builder.addMethod(method);
        }
    }

    private void addField(TypeSpec.Builder builder, FieldSpec field) {
        if (!hasField(builder, field)) {
            builder.addField(field);
        }
    }

    private boolean hasMethod(TypeSpec.Builder builder, MethodSpec method) {
        for (MethodSpec m : builder.build().methodSpecs) {
            if (method.name.equals(m.name) &&
                    method.parameters.size() == m.parameters.size() &&
                    HashMultiset.create(method.parameters).equals(HashMultiset.create(m.parameters))
                    ) {
                return true;
            }
        }
        return false;
    }

    private boolean hasField(TypeSpec.Builder builder, FieldSpec field) {
        for (FieldSpec f : builder.build().fieldSpecs) {
            if (field.name.equals(f.name)) {
                return true;
            }
        }
        return false;
    }

    private void buildDBHelper(TypeSpec.Builder dbHelperBuilder, Map<TypeSpec, String> repos) {
        String repoName = dbHelperBuilder.build().name;
        MethodSpec.Builder constructorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC);

        TypeVariableName repoType = TypeVariableName.get(repoName);
        MethodSpec.Builder createBuilder = MethodSpec.methodBuilder("create")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(Database.class, "database")
                .returns(repoType);

        String[] reposInit = new String[repos.size()];

        int index = 0;
        for (Map.Entry<TypeSpec, String> item : repos.entrySet()) {
            TypeSpec helper = item.getKey();
            String packageName = item.getValue();
            String name = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_CAMEL, helper.name);
            TypeVariableName type = TypeVariableName.get(packageName + "." + helper.name);
            FieldSpec field = FieldSpec.builder(type, name)
                    .build();

            constructorBuilder.addParameter(type, name);
            constructorBuilder.addStatement("this.$L = $L", name, name);

            MethodSpec getter = MethodSpec.methodBuilder("get" + helper.name)
                    .addModifiers(Modifier.PUBLIC)
                    .addStatement("return $L", name)
                    .returns(type)
                    .build();
            addField(dbHelperBuilder, field);
            addMethod(dbHelperBuilder, getter);

            createBuilder.addStatement("$T $L = new $L(database)",
                    type,
                    name,
                    type
                    );
            reposInit[index] = name;

            index++;
        }
        createBuilder.addStatement("return new $L($L)", repoType, Joiner.on(",\n\t\t").join(reposInit));
        addMethod(dbHelperBuilder, constructorBuilder.build());
        addMethod(dbHelperBuilder, createBuilder.build());
    }

    private void buildFinders(TypeSpec.Builder helperBuilder, Set<Element> fields, TypeVariableName returnType) {
        for (Element el : fields) {
            if (el.getKind() == ElementKind.FIELD) {
                String fieldname = el.getSimpleName().toString();
                Index index = el.getAnnotation(Index.class);
                TypeName typeName = getTypeName(el);
                if (index.unique()) {
                    buildUniqueFinder(helperBuilder, returnType, typeName, fieldname);
                } else {
                    buildFinder(helperBuilder, returnType, typeName, fieldname);
                }
            }

            if (el.getKind() == ElementKind.CLASS) {
                Index[] indexes = el.getAnnotation(Document.class).indexes();
                for (Index index : indexes) {
                    if (index.fields().length == 0) {
                        continue;
                    }
                    if (index.unique()) {
                        helperBuilder.addJavadoc("Unique\n");
                        if (index.fields().length > 1) {
                            buildMultipleUniqueFinder(helperBuilder, returnType, el, index.fields());
                        } else {
                            String fieldname = index.fields()[0];
                            for (Element e : el.getEnclosedElements()) {
                                if (e.getSimpleName().toString().equals(fieldname)) {
                                    TypeName typeName = getTypeName(e);
                                    buildUniqueFinder(helperBuilder, returnType, typeName, fieldname);
                                    break;
                                }
                            }
                        }
                    } else {
                        if (index.fields().length > 1) {
                            buildMultipleFinder(helperBuilder, returnType, el, index.fields());
                        } else {
                            String fieldname = index.fields()[0];
                            for (Element e : el.getEnclosedElements()) {
                                if (e.getKind().equals(ElementKind.FIELD)) {
                                    if (e.getSimpleName().toString().equals(fieldname)) {
                                        TypeName typeName = getTypeName(e);
                                        buildFinder(helperBuilder, returnType, typeName, fieldname);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void buildMultipleFinder(TypeSpec.Builder helperBuilder, TypeVariableName returnType, Element parent, String[] fieldnames) {

        String[] fieldNames = new String[fieldnames.length];
        for (int i = 0; i < fieldnames.length; i++) {
            fieldNames[i] = CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, fieldnames[i]);
        }
        String names = Joiner.on("And").join(fieldNames);
        String findByName = "findBy" + names;

        ParameterizedTypeName returnTypeName = ParameterizedTypeName.get(ClassName.get(List.class), returnType);
        ParameterizedTypeName params = ParameterizedTypeName.get(
                ClassName.get(HashMap.class),
                ClassName.get(String.class),
                ClassName.get(Object.class));
        MethodSpec.Builder findBy = MethodSpec.methodBuilder(findByName)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnTypeName)
                .addStatement("$T keyValueMap = new $T()",
                        params,
                        params
                )
//                .addStatement("return findBy($T.$L, $L)",
//                        returnType,
//                        fieldname.toUpperCase(),
//                        fieldname
//                )
                ;
        for (Element e : parent.getEnclosedElements()) {
            if (e.getKind().equals(ElementKind.FIELD)) {
                for (String fieldname : fieldnames) {
                    if (e.getSimpleName().toString().equals(fieldname)) {
                        findBy.addParameter(getTypeName(e), fieldname);
                        findBy.addStatement("keyValueMap.put($T.$L, $L)",
                                returnType,
                                fieldname.toUpperCase(),
                                fieldname
                        );
                    }
                }
            }
        }
        findBy.addStatement("return findBy(keyValueMap)");
        addMethod(helperBuilder, findBy.build());
    }

    private void buildMultipleUniqueFinder(TypeSpec.Builder helperBuilder, TypeVariableName returnType, Element parent, String[] fieldnames) {

        String[] fieldNames = new String[fieldnames.length];
        for (int i = 0; i < fieldnames.length; i++) {
            fieldNames[i] = CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, fieldnames[i]);
        }
        String names = Joiner.on("And").join(fieldNames);
        String findByName = "findOneBy" + names;

        ParameterizedTypeName params = ParameterizedTypeName.get(
                ClassName.get(HashMap.class),
                ClassName.get(String.class),
                ClassName.get(Object.class));
        MethodSpec.Builder findBy = MethodSpec.methodBuilder(findByName)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType)
                .addStatement("$T keyValueMap = new $T()",
                        params,
                        params
                );
        for (Element e : parent.getEnclosedElements()) {
            if (e.getKind().equals(ElementKind.FIELD)) {
                for (String fieldname : fieldnames) {
                    if (e.getSimpleName().toString().equals(fieldname)) {
                        findBy.addParameter(getTypeName(e), fieldname);
                        findBy.addStatement("keyValueMap.put($T.$L, $L)",
                                returnType,
                                fieldname.toUpperCase(),
                                fieldname
                        );
                    }
                }
            }
        }
        findBy.addStatement("return findOneBy(keyValueMap)");
        addMethod(helperBuilder, findBy.build());
    }

    private void buildFinder(TypeSpec.Builder helperBuilder, TypeVariableName returnType, TypeName typeName, String fieldname) {
        String findByName = "findBy" + CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, fieldname);

        ParameterizedTypeName t = ParameterizedTypeName.get(ClassName.get(List.class), returnType);
        MethodSpec findBy = MethodSpec.methodBuilder(findByName)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(typeName, fieldname)
                .returns(t)
                .addStatement("return findBy($T.$L, $L)",
                        returnType,
                        fieldname.toUpperCase(),
                        fieldname)
                .build();
        addMethod(helperBuilder, findBy);
    }

    private void buildUniqueFinder(TypeSpec.Builder helperBuilder, TypeVariableName returnType, TypeName typeName, String fieldname) {
        String findOneByName = "findOneBy" + CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, fieldname);
        MethodSpec findOneBy = MethodSpec.methodBuilder(findOneByName)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(typeName, fieldname)
                .returns(returnType)
                .addStatement("return findOneBy($T.$L, $L)",
                        returnType,
                        fieldname.toUpperCase(),
                        fieldname)
                .build();
        addMethod(helperBuilder, findOneBy);
    }

    private void writeClassToDisk(String packageName, TypeSpec proxy) throws IOException {
        JavaFile.builder(packageName, proxy)
                .indent("    ")
                .build()
                .writeTo(filer);
    }

    private TypeSpec.Builder getRepoBuilder(TypeElement typeElement) {
        return TypeSpec.classBuilder(typeElement.getSimpleName() + REPO_SUFFIX)
                .addModifiers(Modifier.PUBLIC);
    }

    private TypeSpec.Builder getProxyBuilder(TypeElement typeElement) {
        return TypeSpec.
                classBuilder(typeElement.getSimpleName().toString())
                .superclass(TypeName.get(typeElement.asType()))
                .addSuperinterface(CouchDocument.class)
                .addModifiers(Modifier.PUBLIC);
    }

    private TypeName getTypeName(Element el) {
        return ClassName.get(el.asType());
    }

    private String getProxyFQDN(Element el) {
        String typeName = getTypeName(el).toString();
        String pkg = elementUtils.getPackageOf(el).getQualifiedName().toString();

        return pkg + PACKAGE_SUFFIX + typeName.replace(pkg, "");
    }

    private MethodSpec generateRepoUnserialize(TypeElement typeElement, String packageName, CodeBlock code) {
        return MethodSpec.methodBuilder("unserialize")
                .addModifiers(Modifier.PROTECTED)
                .addAnnotation(Override.class)
                .addParameter(com.couchbase.lite.Document.class, "document")
                .returns(ClassName.get(
                        packageName + PACKAGE_SUFFIX,
                        typeElement.getSimpleName().toString()))
                .addCode(code)
                .build();
    }

    private MethodSpec generateRepoSerialize(TypeElement typeElement, String packageName, CodeBlock code) {
        return MethodSpec.methodBuilder("serialize")
                .addModifiers(Modifier.PROTECTED)
                .addAnnotation(Override.class)
                .addParameter(ClassName.get(
                        packageName + PACKAGE_SUFFIX,
                        typeElement.getSimpleName().toString()),
                        typeElement.getSimpleName().toString().toLowerCase())
                .returns(com.couchbase.lite.Document.class)
                .addCode(code)
                .build();
    }

    private MethodSpec generateProxySetter(String fieldname, TypeName fieldType) {
        String setterName = "set" + fieldname.substring(0, 1).toUpperCase() + fieldname.substring(1);
        return MethodSpec.methodBuilder(setterName)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(fieldType, fieldname)
                .addStatement("this.$L = $L", fieldname, fieldname)
                .build();
    }

    private MethodSpec generateProxyGetter(String fieldname, TypeName fieldType) {
        String getterName = "get" + fieldname.substring(0, 1).toUpperCase() + fieldname.substring(1);
        return MethodSpec.methodBuilder(getterName)
                .addModifiers(Modifier.PUBLIC)
                .returns(fieldType)
                .addStatement("return this.$L", fieldname)
                .build();
    }

    private MethodSpec generateHelperConstructor() {
        ParameterSpec database = ParameterSpec.builder(Database.class, "database")
                .build();
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(database)
                .addStatement("super(database)")
                .build();
    }

    private MethodSpec generateHelperGetTypeMethod(Document annotation) {
        return MethodSpec.methodBuilder("getType")
                .addModifiers(Modifier.PROTECTED)
                .addAnnotation(Override.class)
                .returns(String.class)
                .addStatement("return $S", annotation.type())
                .build();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        HashSet<String> strings = new HashSet<>();

        strings.add(Document.class.getCanonicalName());
        strings.add(Property.class.getCanonicalName());

        return strings;
    }

}
