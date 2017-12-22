package gq.ledo.android.processor;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Database;
import com.google.common.base.CaseFormat;
import com.google.common.base.Joiner;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        List<Element> indexes = new ArrayList<>();
        HashMap<TypeSpec, String> helpers = new HashMap<>();

        TypeSpec.Builder dbHelperBuilder = TypeSpec.classBuilder("DBHelper")
                .addModifiers(Modifier.PUBLIC);
        String repoPackageName = "gq.ledo.couchbaseorm";

        for (Element element : roundEnv.getElementsAnnotatedWith(Document.class)) {
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
            repoBuilder.addMethod(getType)
                    .addMethod(helperConstructor);

            for (Element el : enclosedElements) {

                if (el.getKind() == ElementKind.FIELD) {
                    String fieldname = el.getSimpleName().toString();
                    TypeName fieldType = getTypeName(el);

                    FieldSpec fieldSpec = FieldSpec.builder(fieldType, fieldname, Modifier.PRIVATE)
                            .build();

                    MethodSpec getter = generateProxyGetter(fieldname, fieldType);
                    MethodSpec setter = generateProxySetter(fieldname, fieldType);

                    proxyBuilder.addField(fieldSpec)
                            .addMethod(getter)
                            .addMethod(setter);


                    String docFieldName = fieldname;
                    Property property = el.getAnnotation(Property.class);
                    if (property != null) {
                        String value = property.value();
                        if (value.trim().length() > 0) {
                            docFieldName = value;
                        }
                    }
                    Index index = el.getAnnotation(Index.class);
                    if (index != null) {
                        indexes.add(el);
                    }
                    FieldSpec fieldNameSpec = FieldSpec.builder(fieldType, fieldname.toUpperCase())
                            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                            .initializer("$S", docFieldName)
                            .build();
                    proxyBuilder.addField(fieldNameSpec);
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
            repoBuilder.addMethod(unserialize);
            MethodSpec serialize = generateRepoSerialize(typeElement, packageName, serializeCode.build());
            repoBuilder.addMethod(serialize);

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

    private void buildDBHelper(TypeSpec.Builder dbHelperBuilder, HashMap<TypeSpec, String> repos) {
        String repoName = dbHelperBuilder.build().name;
        MethodSpec.Builder constructorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC);

        TypeVariableName repoType = TypeVariableName.get(repoName);

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
            dbHelperBuilder.addMethod(getter)
                    .addField(field);
        }
        dbHelperBuilder.addMethod(constructorBuilder.build());
    }

    private void buildFinders(TypeSpec.Builder helperBuilder, List<Element> fields, TypeVariableName returnType) {
        for (Element el : fields) {
            String fieldname = el.getSimpleName().toString();
            Index index = el.getAnnotation(Index.class);
            if (index.unique()) {
                // TODO: Save unique
                String findOneByName = "findOneBy" + CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, fieldname);
                MethodSpec findOneBy = MethodSpec.methodBuilder(findOneByName)
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(getTypeName(el), fieldname)
                        .returns(returnType)
                        .addStatement("return findOneBy($N.$L, $L)",
                                getTypeName(el),
                                fieldname.toUpperCase(),
                                fieldname)
                        .build();
                helperBuilder.addMethod(findOneBy);
            }
            String findByName = "findBy" + CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, fieldname);

            ParameterizedTypeName t = ParameterizedTypeName.get(ClassName.get(List.class), returnType);
            MethodSpec findBy = MethodSpec.methodBuilder(findByName)
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(getTypeName(el), fieldname)
                    .returns(t)
                    .addStatement("return findBy($T.$L, $L)",
                            returnType,
                            fieldname.toUpperCase(),
                            fieldname)
                    .build();
            helperBuilder.addMethod(findBy);
        }
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
