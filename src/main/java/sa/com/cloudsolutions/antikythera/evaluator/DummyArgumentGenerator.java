package sa.com.cloudsolutions.antikythera.evaluator;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.NodeList;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Optional;

public class DummyArgumentGenerator extends ArgumentGenerator {

    public DummyArgumentGenerator() {
        super();
    }

    @Override
    public void generateArgument(Parameter param) throws ReflectiveOperationException {
        Variable v = mockParameter(param);
        if (v.getValue() == null) {
            v = mockNonPrimitiveParameter(param);
        }

        /*
         * Pushed to be popped later in the callee
         */
        arguments.put(param.getNameAsString(), v);
        AntikytheraRunTime.push(v);
    }

    private Variable mockNonPrimitiveParameter(Parameter param) throws ReflectiveOperationException {
        Variable v = null;
        Type t = param.getType();

        if (t.isClassOrInterfaceType() && param.findCompilationUnit().isPresent()) {
            TypeWrapper wrapper = AbstractCompiler.findType(param.findCompilationUnit().orElseThrow(), t);
            String fullClassName = wrapper.getFullyQualifiedName();
            if (t.asClassOrInterfaceType().isBoxedType()) {
                return mockParameter(param);
            }
            if (wrapper.getClazz() != null) {
                return mockNonPrimitiveParameter(param, wrapper);
            }
            Evaluator o = EvaluatorFactory.create(fullClassName, MockingEvaluator.class);
            v = new Variable(o);
            v.setType(t);
            Optional<TypeDeclaration<?>> opt = AntikytheraRunTime.getTypeDeclaration(fullClassName);
            if (opt.isPresent()) {
                String init = ArgumentGenerator.instantiateClass(
                        opt.get().asClassOrInterfaceDeclaration(),
                        param.getNameAsString()
                ).replace(";","");
                String[] parts = init.split("=");
                v.setInitializer(List.of(StaticJavaParser.parseExpression(parts[1])));
            }
        }
        return v;
    }

    private Variable mockNonPrimitiveParameter(Parameter param, TypeWrapper wrapper) throws InstantiationException, IllegalAccessException, InvocationTargetException {
        String fullClassName = wrapper.getFullyQualifiedName();
        Type t = param.getType();


        if (fullClassName.startsWith("java.util")) {
            return Reflect.variableFactory(fullClassName);
        }

        Class<?> clazz = wrapper.getClazz();
        // Try to find a no-arg constructor first
        try {
            return new Variable(clazz.getDeclaredConstructor().newInstance());
        } catch (NoSuchMethodException e) {
            // No no-arg constructor, find the simplest one
            Constructor<?> simplest = findConstructor(clazz);
            if (simplest != null) {
                Object[] args = new Object[simplest.getParameterCount()];
                Class<?>[] paramTypes = simplest.getParameterTypes();
                NodeList<com.github.javaparser.ast.expr.Expression> argExprs = new NodeList<>();
                for (int i = 0; i < paramTypes.length; i++) {
                    if (paramTypes[i].equals(String.class)) {
                        args[i] = "Antikythera";
                        argExprs.add(new StringLiteralExpr("Antikythera"));
                    } else {
                        args[i] = Reflect.getDefault(paramTypes[i]);
                        argExprs.add(Reflect.createLiteralExpression(args[i]));
                    }
                }
                Variable v = new Variable(simplest.newInstance(args));
                // Set initializer
                ObjectCreationExpr oce =
                    new ObjectCreationExpr()
                        .setType(t.asString())
                        .setArguments(argExprs);
                v.setInitializer(List.of(oce));
                return v;
            }
        }

        return new Variable((Object) null);
    }

    private static Constructor<?> findConstructor(Class<?> clazz) {
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        Constructor<?> simplest = null;
        int minParams = Integer.MAX_VALUE;
        for (Constructor<?> ctor : constructors) {
            Class<?>[] paramTypes = ctor.getParameterTypes();
            boolean allSimple = true;
            for (Class<?> pt : paramTypes) {
                if (!(Reflect.isPrimitiveOrBoxed(pt) || pt.equals(String.class))) {
                    allSimple = false;
                    break;
                }
            }
            if (allSimple && paramTypes.length < minParams) {
                minParams = paramTypes.length;
                simplest = ctor;
            }
        }
        return simplest;
    }

    protected Variable mockParameter(Parameter param) {
        Type t = param.getType();
        if (t.isClassOrInterfaceType()) {
            return Reflect.variableFactory(t.asClassOrInterfaceType().getName().asString());
        }
        return Reflect.variableFactory(param.getType().asString());
    }
}
