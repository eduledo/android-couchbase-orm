package gq.ledo.android.processor;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

import gq.ledo.couchbaseorm.annotations.Document;
import gq.ledo.couchbaseorm.annotations.Property;

@SupportedSourceVersion(SourceVersion.RELEASE_7)
public class DocumentProcessor extends AbstractProcessor {

    private Messager messager;
    private Filer filer;
    private Elements elementUtils;
    private static final String PACKAGE_SUFFIX = ".proxy";
    private static final String REPO_SUFFIX = "_Repository";
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

        for (Element element : roundEnv.getElementsAnnotatedWith(Document.class)) {
            if (element.getKind() != ElementKind.CLASS) {
                messager.printMessage(Diagnostic.Kind.ERROR, "Can be applied to class.");
                return true;
            }
            TypeElement typeElement = (TypeElement) element;
            // Get package
            String packageName = elementUtils.getPackageOf(typeElement).getQualifiedName().toString();
            // Get public fields
            List<? extends Element> enclosedElements = typeElement.getEnclosedElements();
            String typeVarName = typeElement.getSimpleName().toString().toLowerCase();

            // Create wrapper
            TypeSpec.Builder proxyBuilder = TypeSpec.
                    classBuilder(typeElement.getSimpleName().toString())
                    .superclass(TypeName.get(typeElement.asType()));

            // Create repository
            TypeSpec.Builder repoBuilder = TypeSpec.classBuilder(typeElement.getSimpleName() + REPO_SUFFIX);

            CodeBlock.Builder deserializeCode = CodeBlock.builder()
                    .addStatement("$L $L = new $L()",
                            typeElement.getSimpleName(),
                            typeVarName,
                            typeElement.getSimpleName()
                    );

            MethodSpec getAll = generateRepoFindAllMethod();
            MethodSpec findOneBy = generateRepoFindOneByMethod();
            MethodSpec repoConstructor = generateRepoConstructor();
            repoBuilder.addMethod(findOneBy);
            repoBuilder.addMethod(getAll);
            repoBuilder.addMethod(repoConstructor);

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
                    if (primitives.containsKey(fieldType.toString())) {
                        String st = "$N.$N((($L) document.getProperty($S)).$L)";
                        String[] strings = primitives.get(fieldType.toString());
                        if (strings != null) {
                            deserializeCode.addStatement(st,
                                    typeVarName,
                                    setter,
                                    strings[0],
                                    docFieldName,
                                    strings[1]
                            );
                        }
                    } else {
                        deserializeCode.addStatement("$N.$N($L.deserialize(($T) document.getProperty($S)))",
                                typeVarName,
                                setter,
                                getProxyFQDN(el),
                                com.couchbase.lite.Document.class,
                                docFieldName
                        );

                    }
                }
            }
            deserializeCode.addStatement("return $L", typeVarName);
            MethodSpec deserialize = generateProxyDeserialize(typeElement, packageName, deserializeCode.build());
            proxyBuilder.addMethod(deserialize);

            try {
                JavaFile.builder(packageName + PACKAGE_SUFFIX, proxyBuilder.build())
                        .indent("    ")
                        .build()
                        .writeTo(filer);
                JavaFile.builder(packageName + PACKAGE_SUFFIX, repoBuilder.build())
                        .indent("    ")
                        .build()
                        .writeTo(filer);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return true;
    }

    private TypeName getTypeName(Element el) {
        return ClassName.get(el.asType());
    }

    private String getProxyFQDN(Element el) {
        String typeName = getTypeName(el).toString();
        String pkg = elementUtils.getPackageOf(el).getQualifiedName().toString();

        return pkg + PACKAGE_SUFFIX + typeName.replace(pkg, "");
    }

    private MethodSpec generateProxyDeserialize(TypeElement typeElement, String packageName, CodeBlock code) {
        return MethodSpec.methodBuilder("deserialize")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(com.couchbase.lite.Document.class, "document")
                .returns(ClassName.get(
                        packageName + PACKAGE_SUFFIX,
                        typeElement.getSimpleName().toString()))
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

    private MethodSpec generateRepoConstructor() {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addStatement("// TODO: Everything")
                .build();
    }

    private MethodSpec generateRepoFindOneByMethod() {
        TypeVariableName typeVariable = TypeVariableName.get("T", Object.class);
        ParameterizedTypeName t = ParameterizedTypeName.get(ClassName.get(List.class), typeVariable);
        return MethodSpec.methodBuilder("findOneBy")
                .addModifiers(Modifier.PUBLIC)
                .addTypeVariable(typeVariable)
                .addParameter(typeVariable, "tClass")
                .returns(t)
                .addStatement("return null")
                .build();
    }

    private MethodSpec generateRepoFindAllMethod() {
        TypeVariableName typeVariable = TypeVariableName.get("T", Object.class);
        ParameterizedTypeName t = ParameterizedTypeName.get(ClassName.get(List.class), typeVariable);
        return MethodSpec.methodBuilder("findAll")
                .addModifiers(Modifier.PUBLIC)
                .addTypeVariable(typeVariable)
                .addParameter(typeVariable, "tClass")
                .returns(t)
                .addStatement("return null")
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
