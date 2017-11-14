package gq.ledo.android.processor;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

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
            // Get fqcn
            String typeName = typeElement.getQualifiedName().toString();
            // Get public fields
            List<? extends Element> enclosedElements = typeElement.getEnclosedElements();

            // Create wrapper
            TypeSpec.Builder proxyBuilder = TypeSpec.
                    classBuilder(typeElement.getSimpleName().toString())
                    .superclass(TypeName.get(typeElement.asType()));

            // Create repository
            TypeSpec.Builder repoBuilder = TypeSpec.classBuilder(typeElement.getSimpleName() + REPO_SUFFIX);

            String[] str = new String[enclosedElements.size()];
            int i = 0;
            String typeVarName = typeElement.getSimpleName().toString().toLowerCase();
            MethodSpec.Builder deserialize = MethodSpec.methodBuilder("deserialize")
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(com.couchbase.lite.Document.class, "document")
                    .returns(ClassName.get(
                            packageName + PACKAGE_SUFFIX,
                            typeElement.getSimpleName().toString()))
                    .addStatement("$L $L = new $L()",
                            typeElement.getSimpleName(),
                            typeVarName,
                            typeElement.getSimpleName()
                    );

            for (Element el : enclosedElements) {

                if (el.getKind() == ElementKind.FIELD) {
                    String fieldname = el.getSimpleName().toString();
                    TypeName fieldType = ClassName.get(el.asType());

                    FieldSpec fieldSpec = FieldSpec.builder(fieldType, fieldname, Modifier.PRIVATE)
                            .build();

                    String getterName = "get" + fieldname.substring(0, 1).toUpperCase() + fieldname.substring(1);
                    MethodSpec getter = MethodSpec.methodBuilder(getterName)
                            .addModifiers(Modifier.PUBLIC)
                            .returns(fieldType)
                            .addStatement("return this.$L", fieldname)
                            .build();
                    String setterName = "set" + fieldname.substring(0, 1).toUpperCase() + fieldname.substring(1);
                    MethodSpec setter = MethodSpec.methodBuilder(setterName)
                            .addModifiers(Modifier.PUBLIC)
                            .addParameter(fieldType, fieldname)
                            .addStatement("this.$L = $L", fieldname, fieldname)
                            .build();

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
                    if (fieldType.isPrimitive()) {
                        String st = "$N.$N((($L) document.getProperty($S)).$L)";
                        String[] strings = primitives.get(fieldType.toString());
                        if (strings != null) {
                            deserialize.addStatement(st,
                                    typeVarName,
                                    setter,
                                    strings[0],
                                    docFieldName,
                                    strings[1]
                            );
                        }
                    } else {
                        deserialize.addStatement("$N.$N(($T) document.getProperty($S))",
                                typeVarName,
                                setter,
                                ClassName.get(el.asType()),
                                docFieldName
                                );
                    }
                }
            }
            deserialize.addStatement("return $L", typeVarName);
            proxyBuilder.addMethod(deserialize.build());

            try {
                JavaFile.builder(packageName + PACKAGE_SUFFIX, proxyBuilder.build())
                        .indent("    ")
                        .build()
                        .writeTo(filer);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return true;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        HashSet<String> strings = new HashSet<>();

        strings.add(Document.class.getCanonicalName());
        strings.add(Property.class.getCanonicalName());

        return strings;
    }
}
