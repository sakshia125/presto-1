/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.operator.scalar.annotations;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Primitives;
import io.prestosql.metadata.BoundVariables;
import io.prestosql.metadata.LongVariableConstraint;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.Signature;
import io.prestosql.operator.ParametricImplementation;
import io.prestosql.operator.annotations.FunctionsParserHelper;
import io.prestosql.operator.annotations.ImplementationDependency;
import io.prestosql.operator.scalar.ScalarFunctionImplementation;
import io.prestosql.operator.scalar.ScalarFunctionImplementation.ArgumentProperty;
import io.prestosql.operator.scalar.ScalarFunctionImplementation.NullConvention;
import io.prestosql.operator.scalar.ScalarFunctionImplementation.ScalarImplementationChoice;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.function.BlockIndex;
import io.prestosql.spi.function.BlockPosition;
import io.prestosql.spi.function.IsNull;
import io.prestosql.spi.function.SqlNullable;
import io.prestosql.spi.function.SqlType;
import io.prestosql.spi.function.TypeParameter;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeSignature;
import io.prestosql.type.FunctionType;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.prestosql.metadata.FunctionKind.SCALAR;
import static io.prestosql.operator.ParametricFunctionHelpers.bindDependencies;
import static io.prestosql.operator.annotations.FunctionsParserHelper.containsImplementationDependencyAnnotation;
import static io.prestosql.operator.annotations.FunctionsParserHelper.containsLegacyNullable;
import static io.prestosql.operator.annotations.FunctionsParserHelper.createTypeVariableConstraints;
import static io.prestosql.operator.annotations.FunctionsParserHelper.getDeclaredSpecializedTypeParameters;
import static io.prestosql.operator.annotations.FunctionsParserHelper.parseLiteralParameters;
import static io.prestosql.operator.annotations.FunctionsParserHelper.parseLongVariableConstraints;
import static io.prestosql.operator.annotations.ImplementationDependency.Factory.createDependency;
import static io.prestosql.operator.annotations.ImplementationDependency.checkTypeParameters;
import static io.prestosql.operator.annotations.ImplementationDependency.getImplementationDependencyAnnotation;
import static io.prestosql.operator.annotations.ImplementationDependency.validateImplementationDependencyAnnotation;
import static io.prestosql.operator.scalar.ScalarFunctionImplementation.ArgumentProperty.functionTypeArgumentProperty;
import static io.prestosql.operator.scalar.ScalarFunctionImplementation.ArgumentProperty.valueTypeArgumentProperty;
import static io.prestosql.operator.scalar.ScalarFunctionImplementation.ArgumentType.VALUE_TYPE;
import static io.prestosql.operator.scalar.ScalarFunctionImplementation.NullConvention.BLOCK_AND_POSITION;
import static io.prestosql.operator.scalar.ScalarFunctionImplementation.NullConvention.RETURN_NULL_ON_NULL;
import static io.prestosql.operator.scalar.ScalarFunctionImplementation.NullConvention.USE_NULL_FLAG;
import static io.prestosql.spi.StandardErrorCode.FUNCTION_IMPLEMENTATION_ERROR;
import static io.prestosql.spi.type.TypeSignature.parseTypeSignature;
import static io.prestosql.util.Failures.checkCondition;
import static io.prestosql.util.Reflection.constructorMethodHandle;
import static io.prestosql.util.Reflection.methodHandle;
import static java.lang.String.format;
import static java.lang.invoke.MethodHandles.permuteArguments;
import static java.lang.reflect.Modifier.isStatic;
import static java.util.Objects.requireNonNull;

public class ParametricScalarImplementation
        implements ParametricImplementation
{
    private final Signature signature;
    private final List<Optional<Class<?>>> argumentNativeContainerTypes; // argument native container type is Optional.empty() for function type
    private final Map<String, Class<?>> specializedTypeParameters;
    private final Class<?> returnNativeContainerType;
    private final List<ParametricScalarImplementationChoice> choices;

    private ParametricScalarImplementation(
            Signature signature,
            List<Optional<Class<?>>> argumentNativeContainerTypes,
            Map<String, Class<?>> specializedTypeParameters,
            List<ParametricScalarImplementationChoice> choices,
            Class<?> returnContainerType)
    {
        this.signature = requireNonNull(signature, "signature is null");
        this.argumentNativeContainerTypes = ImmutableList.copyOf(requireNonNull(argumentNativeContainerTypes, "argumentNativeContainerTypes is null"));
        this.specializedTypeParameters = ImmutableMap.copyOf(requireNonNull(specializedTypeParameters, "specializedTypeParameters is null"));
        this.choices = requireNonNull(choices, "choices is null");
        this.returnNativeContainerType = requireNonNull(returnContainerType, "return native container type is null");

        for (Class<?> specializedJavaType : specializedTypeParameters.values()) {
            checkArgument(specializedJavaType != Object.class, "specializedTypeParameter must not contain Object.class entries");
            checkArgument(!Primitives.isWrapperType(specializedJavaType), "specializedTypeParameter must not contain boxed primitive types");
        }
    }

    public Optional<ScalarFunctionImplementation> specialize(Signature boundSignature, BoundVariables boundVariables, Metadata metadata, boolean isDeterministic)
    {
        List<ScalarImplementationChoice> implementationChoices = new ArrayList<>();
        for (Map.Entry<String, Class<?>> entry : specializedTypeParameters.entrySet()) {
            if (entry.getValue() != boundVariables.getTypeVariable(entry.getKey()).getJavaType()) {
                return Optional.empty();
            }
        }

        if (returnNativeContainerType != Object.class && returnNativeContainerType != metadata.getType(boundSignature.getReturnType()).getJavaType()) {
            return Optional.empty();
        }

        for (int i = 0; i < boundSignature.getArgumentTypes().size(); i++) {
            if (boundSignature.getArgumentTypes().get(i).getBase().equals(FunctionType.NAME)) {
                if (argumentNativeContainerTypes.get(i).isPresent()) {
                    return Optional.empty();
                }
            }
            else {
                if (!argumentNativeContainerTypes.get(i).isPresent()) {
                    return Optional.empty();
                }

                Class<?> argumentType = metadata.getType(boundSignature.getArgumentTypes().get(i)).getJavaType();
                Class<?> argumentNativeContainerType = argumentNativeContainerTypes.get(i).get();
                if (argumentNativeContainerType != Object.class && argumentNativeContainerType != argumentType) {
                    return Optional.empty();
                }
            }
        }

        for (ParametricScalarImplementationChoice choice : choices) {
            MethodHandle boundMethodHandle = bindDependencies(choice.getMethodHandle(), choice.getDependencies(), boundVariables, metadata);
            Optional<MethodHandle> boundConstructor = choice.getConstructor().map(constructor -> {
                MethodHandle result = bindDependencies(constructor, choice.getConstructorDependencies(), boundVariables, metadata);
                checkCondition(
                        result.type().parameterList().isEmpty(),
                        FUNCTION_IMPLEMENTATION_ERROR,
                        "All parameters of a constructor in a function definition class must be Dependencies. Signature: %s",
                        boundSignature);
                return result;
            });

            implementationChoices.add(new ScalarImplementationChoice(
                    choice.nullable,
                    choice.argumentProperties,
                    boundMethodHandle.asType(javaMethodType(choice, boundSignature, metadata)),
                    boundConstructor));
        }
        return Optional.of(new ScalarFunctionImplementation(implementationChoices, isDeterministic));
    }

    @Override
    public boolean hasSpecializedTypeParameters()
    {
        return !specializedTypeParameters.isEmpty();
    }

    Map<String, Class<?>> getSpecializedTypeParameters()
    {
        return specializedTypeParameters;
    }

    @Override
    public Signature getSignature()
    {
        return signature;
    }

    List<Optional<Class<?>>> getArgumentNativeContainerTypes()
    {
        return argumentNativeContainerTypes;
    }

    @VisibleForTesting
    public List<ParametricScalarImplementationChoice> getChoices()
    {
        return choices;
    }

    Class<?> getReturnNativeContainerType()
    {
        return returnNativeContainerType;
    }

    SpecializedSignature getSpecializedSignature()
    {
        return new SpecializedSignature(
                signature,
                argumentNativeContainerTypes,
                specializedTypeParameters,
                returnNativeContainerType);
    }

    private static MethodType javaMethodType(ParametricScalarImplementationChoice choice, Signature signature, Metadata metadata)
    {
        // This method accomplishes two purposes:
        // * Assert that the method signature is as expected.
        //   This catches errors that would otherwise surface during bytecode generation and class loading.
        // * Adapt the method signature when necessary (for example, when the parameter type or return type is declared as Object).
        ImmutableList.Builder<Class<?>> methodHandleParameterTypes = ImmutableList.builder();
        if (choice.getConstructor().isPresent()) {
            methodHandleParameterTypes.add(Object.class);
        }
        if (choice.hasConnectorSession()) {
            methodHandleParameterTypes.add(ConnectorSession.class);
        }

        List<ArgumentProperty> argumentProperties = choice.getArgumentProperties();
        for (int i = 0; i < argumentProperties.size(); i++) {
            ArgumentProperty argumentProperty = argumentProperties.get(i);
            switch (argumentProperty.getArgumentType()) {
                case VALUE_TYPE:
                    Type signatureType = metadata.getType(signature.getArgumentTypes().get(i));
                    switch (argumentProperty.getNullConvention()) {
                        case RETURN_NULL_ON_NULL:
                            methodHandleParameterTypes.add(signatureType.getJavaType());
                            break;
                        case USE_NULL_FLAG:
                            methodHandleParameterTypes.add(signatureType.getJavaType());
                            methodHandleParameterTypes.add(boolean.class);
                            break;
                        case USE_BOXED_TYPE:
                            methodHandleParameterTypes.add(Primitives.wrap(signatureType.getJavaType()));
                            break;
                        case BLOCK_AND_POSITION:
                            methodHandleParameterTypes.add(Block.class);
                            methodHandleParameterTypes.add(int.class);
                            break;
                        default:
                            throw new UnsupportedOperationException("unknown NullConvention");
                    }
                    break;
                case FUNCTION_TYPE:
                    methodHandleParameterTypes.add(argumentProperty.getLambdaInterface());
                    break;
                default:
                    throw new UnsupportedOperationException("unknown ArgumentType");
            }
        }

        Class<?> methodHandleReturnType = metadata.getType(signature.getReturnType()).getJavaType();
        if (choice.isNullable()) {
            methodHandleReturnType = Primitives.wrap(methodHandleReturnType);
        }

        return MethodType.methodType(methodHandleReturnType, methodHandleParameterTypes.build());
    }

    public static final class Builder
    {
        private final Signature signature;
        private final List<Optional<Class<?>>> argumentNativeContainerTypes; // argument native container type is Optional.empty() for function type
        private final Map<String, Class<?>> specializedTypeParameters;
        private final Class<?> returnNativeContainerType;
        private final List<ParametricScalarImplementationChoice> choices;

        public Builder(
                Signature signature,
                List<Optional<Class<?>>> argumentNativeContainerTypes,
                Map<String, Class<?>> specializedTypeParameters,
                Class<?> returnNativeContainerType)
        {
            this.signature = requireNonNull(signature, "signature is null");
            this.argumentNativeContainerTypes = ImmutableList.copyOf(requireNonNull(argumentNativeContainerTypes, "argumentNativeContainerTypes is null"));
            this.specializedTypeParameters = ImmutableMap.copyOf(requireNonNull(specializedTypeParameters, "specializedTypeParameters is null"));
            this.choices = new ArrayList<>();
            this.returnNativeContainerType = requireNonNull(returnNativeContainerType, "return native container type is null");
        }

        void addChoices(ParametricScalarImplementation implementation)
        {
            this.choices.addAll(implementation.choices);
        }

        public ParametricScalarImplementation build()
        {
            choices.sort(ParametricScalarImplementationChoice::compareTo);
            return new ParametricScalarImplementation(signature, argumentNativeContainerTypes, specializedTypeParameters, choices, returnNativeContainerType);
        }
    }

    public static final class ParametricScalarImplementationChoice
            implements Comparable<ParametricScalarImplementationChoice>
    {
        private final boolean nullable;
        private final List<ArgumentProperty> argumentProperties;
        private final MethodHandle methodHandle;
        private final Optional<MethodHandle> constructor;
        private final List<ImplementationDependency> dependencies;
        private final List<ImplementationDependency> constructorDependencies;
        private final int numberOfBlockPositionArguments;
        private final boolean hasConnectorSession;

        private ParametricScalarImplementationChoice(
                boolean nullable,
                boolean hasConnectorSession,
                List<ArgumentProperty> argumentProperties,
                MethodHandle methodHandle,
                Optional<MethodHandle> constructor,
                List<ImplementationDependency> dependencies,
                List<ImplementationDependency> constructorDependencies)
        {
            this.nullable = nullable;
            this.hasConnectorSession = hasConnectorSession;
            this.argumentProperties = ImmutableList.copyOf(requireNonNull(argumentProperties, "argumentProperties is null"));
            this.methodHandle = requireNonNull(methodHandle, "methodHandle is null");
            this.constructor = requireNonNull(constructor, "constructor is null");
            this.dependencies = ImmutableList.copyOf(requireNonNull(dependencies, "dependencies is null"));
            this.constructorDependencies = ImmutableList.copyOf(requireNonNull(constructorDependencies, "constructorDependencies is null"));

            int numberOfBlockPositionArguments = 0;
            for (ArgumentProperty argumentProperty : argumentProperties) {
                if (argumentProperty.getArgumentType() == VALUE_TYPE && argumentProperty.getNullConvention().equals(BLOCK_AND_POSITION)) {
                    numberOfBlockPositionArguments++;
                }
            }
            this.numberOfBlockPositionArguments = numberOfBlockPositionArguments;
        }

        public boolean isNullable()
        {
            return nullable;
        }

        public boolean hasConnectorSession()
        {
            return hasConnectorSession;
        }

        public MethodHandle getMethodHandle()
        {
            return methodHandle;
        }

        @VisibleForTesting
        public List<ImplementationDependency> getDependencies()
        {
            return dependencies;
        }

        public List<ArgumentProperty> getArgumentProperties()
        {
            return argumentProperties;
        }

        public boolean checkDependencies()
        {
            for (int i = 1; i < getDependencies().size(); i++) {
                if (!getDependencies().get(i).equals(getDependencies().get(0))) {
                    return false;
                }
            }
            return true;
        }

        @VisibleForTesting
        public List<ImplementationDependency> getConstructorDependencies()
        {
            return constructorDependencies;
        }

        public Optional<MethodHandle> getConstructor()
        {
            return constructor;
        }

        @Override
        public int compareTo(ParametricScalarImplementationChoice choice)
        {
            if (choice.numberOfBlockPositionArguments < this.numberOfBlockPositionArguments) {
                return 1;
            }
            return -1;
        }
    }

    public static final class SpecializedSignature
    {
        private final Signature signature;
        private final List<Optional<Class<?>>> argumentNativeContainerTypes;
        private final Map<String, Class<?>> specializedTypeParameters;
        private final Class<?> returnNativeContainerType;

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            SpecializedSignature that = (SpecializedSignature) o;
            return Objects.equals(signature, that.signature) &&
                    Objects.equals(argumentNativeContainerTypes, that.argumentNativeContainerTypes) &&
                    Objects.equals(specializedTypeParameters, that.specializedTypeParameters) &&
                    Objects.equals(returnNativeContainerType, that.returnNativeContainerType);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(signature, argumentNativeContainerTypes, specializedTypeParameters, returnNativeContainerType);
        }

        private SpecializedSignature(
                Signature signature,
                List<Optional<Class<?>>> argumentNativeContainerTypes,
                Map<String, Class<?>> specializedTypeParameters,
                Class<?> returnNativeContainerType)
        {
            this.signature = signature;
            this.argumentNativeContainerTypes = argumentNativeContainerTypes;
            this.specializedTypeParameters = specializedTypeParameters;
            this.returnNativeContainerType = returnNativeContainerType;
        }
    }

    public static final class Parser
    {
        private final String functionName;
        private final boolean nullable;
        private final List<ArgumentProperty> argumentProperties = new ArrayList<>();
        private final TypeSignature returnType;
        private final List<TypeSignature> argumentTypes = new ArrayList<>();
        private final List<Optional<Class<?>>> argumentNativeContainerTypes = new ArrayList<>();
        private final MethodHandle methodHandle;
        private final List<ImplementationDependency> dependencies = new ArrayList<>();
        private final Set<TypeParameter> typeParameters = new LinkedHashSet<>();
        private final Set<String> literalParameters;
        private final ImmutableSet<String> typeParameterNames;
        private final Map<String, Class<?>> specializedTypeParameters;
        private final Optional<MethodHandle> constructorMethodHandle;
        private final List<ImplementationDependency> constructorDependencies = new ArrayList<>();
        private final List<LongVariableConstraint> longVariableConstraints;
        private final Class<?> returnNativeContainerType;
        private boolean hasConnectorSession;

        private final List<ParametricScalarImplementationChoice> choices = new ArrayList<>();

        private Parser(String functionName, Method method, Optional<Constructor<?>> constructor)
        {
            this.functionName = requireNonNull(functionName, "functionName is null");
            this.nullable = method.getAnnotation(SqlNullable.class) != null;
            checkArgument(nullable || !containsLegacyNullable(method.getAnnotations()), "Method [%s] is annotated with @Nullable but not @SqlNullable", method);

            typeParameters.addAll(Arrays.asList(method.getAnnotationsByType(TypeParameter.class)));

            literalParameters = parseLiteralParameters(method);
            typeParameterNames = typeParameters.stream()
                    .map(TypeParameter::value)
                    .collect(toImmutableSet());

            SqlType returnType = method.getAnnotation(SqlType.class);
            checkArgument(returnType != null, "Method [%s] is missing @SqlType annotation", method);
            this.returnType = parseTypeSignature(returnType.value(), literalParameters);

            Class<?> actualReturnType = method.getReturnType();
            this.returnNativeContainerType = Primitives.unwrap(actualReturnType);

            if (Primitives.isWrapperType(actualReturnType)) {
                checkArgument(nullable, "Method [%s] has wrapper return type %s but is missing @SqlNullable", method, actualReturnType.getSimpleName());
            }
            else if (actualReturnType.isPrimitive()) {
                checkArgument(!nullable, "Method [%s] annotated with @SqlNullable has primitive return type %s", method, actualReturnType.getSimpleName());
            }

            longVariableConstraints = parseLongVariableConstraints(method);

            this.specializedTypeParameters = getDeclaredSpecializedTypeParameters(method, typeParameters);

            for (TypeParameter typeParameter : typeParameters) {
                checkArgument(
                        typeParameter.value().matches("[A-Z][A-Z0-9]*"),
                        "Expected type parameter to only contain A-Z and 0-9 (starting with A-Z), but got %s on method [%s]", typeParameter.value(), method);
            }

            inferSpecialization(method, actualReturnType, returnType.value());
            parseArguments(method);

            this.constructorMethodHandle = getConstructor(method, constructor);

            this.methodHandle = getMethodHandle(method);

            ParametricScalarImplementationChoice choice = new ParametricScalarImplementationChoice(nullable, hasConnectorSession, argumentProperties, methodHandle, constructorMethodHandle, dependencies, constructorDependencies);
            choices.add(choice);
        }

        private void parseArguments(Method method)
        {
            boolean encounteredNonDependencyAnnotation = false;
            int i = 0;
            while (i < method.getParameterCount()) {
                Parameter parameter = method.getParameters()[i];
                Class<?> parameterType = parameter.getType();

                // Skip injected parameters
                if (parameterType == ConnectorSession.class) {
                    checkCondition(!hasConnectorSession, FUNCTION_IMPLEMENTATION_ERROR, "Method [%s] has more than 1 ConnectorSession in the parameter list", method);
                    hasConnectorSession = true;
                    i++;
                    continue;
                }

                Optional<Annotation> implementationDependency = getImplementationDependencyAnnotation(parameter);
                if (implementationDependency.isPresent()) {
                    checkCondition(!encounteredNonDependencyAnnotation, FUNCTION_IMPLEMENTATION_ERROR, "Method [%s] has parameters annotated with Dependency annotations that appears after other parameters", method);

                    // check if only declared typeParameters and literalParameters are used
                    validateImplementationDependencyAnnotation(method, implementationDependency.get(), typeParameterNames, literalParameters);
                    dependencies.add(createDependency(implementationDependency.get(), literalParameters));

                    i++;
                }
                else {
                    encounteredNonDependencyAnnotation = true;

                    Annotation[] annotations = parameter.getAnnotations();
                    checkArgument(Stream.of(annotations).noneMatch(IsNull.class::isInstance), "Method [%s] has @IsNull parameter that does not follow a @SqlType parameter", method);

                    SqlType type = Stream.of(annotations)
                            .filter(SqlType.class::isInstance)
                            .map(SqlType.class::cast)
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException(format("Method [%s] is missing @SqlType annotation for parameter", method)));
                    TypeSignature typeSignature = parseTypeSignature(type.value(), literalParameters);
                    argumentTypes.add(typeSignature);

                    if (typeSignature.getBase().equals(FunctionType.NAME)) {
                        // function type
                        checkCondition(parameterType.isAnnotationPresent(FunctionalInterface.class), FUNCTION_IMPLEMENTATION_ERROR, "argument %s is marked as lambda but the function interface class is not annotated: %s", i, methodHandle);
                        argumentProperties.add(functionTypeArgumentProperty(parameterType));
                        argumentNativeContainerTypes.add(Optional.empty());
                        i++;
                    }
                    else {
                        // value type
                        NullConvention nullConvention;
                        if (Stream.of(annotations).anyMatch(SqlNullable.class::isInstance)) {
                            checkCondition(!parameterType.isPrimitive(), FUNCTION_IMPLEMENTATION_ERROR, "Method [%s] has parameter with primitive type %s annotated with @SqlNullable", method, parameterType.getSimpleName());

                            nullConvention = NullConvention.USE_BOXED_TYPE;
                        }
                        else if (Stream.of(annotations).anyMatch(BlockPosition.class::isInstance)) {
                            checkState(method.getParameterCount() > (i + 1));
                            checkState(parameterType == Block.class);

                            nullConvention = NullConvention.BLOCK_AND_POSITION;
                            Annotation[] parameterAnnotations = method.getParameterAnnotations()[i + 1];
                            checkState(Stream.of(parameterAnnotations).anyMatch(BlockIndex.class::isInstance));
                        }
                        else {
                            // USE_NULL_FLAG or RETURN_NULL_ON_NULL
                            checkCondition(parameterType == Void.class || !Primitives.isWrapperType(parameterType), FUNCTION_IMPLEMENTATION_ERROR, "A parameter with USE_NULL_FLAG or RETURN_NULL_ON_NULL convention must not use wrapper type. Found in method [%s]", method);

                            boolean useNullFlag = false;
                            if (method.getParameterCount() > (i + 1)) {
                                Annotation[] parameterAnnotations = method.getParameterAnnotations()[i + 1];
                                if (Stream.of(parameterAnnotations).anyMatch(IsNull.class::isInstance)) {
                                    Class<?> isNullType = method.getParameterTypes()[i + 1];

                                    checkArgument(Stream.of(parameterAnnotations).filter(FunctionsParserHelper::isPrestoAnnotation).allMatch(IsNull.class::isInstance), "Method [%s] has @IsNull parameter that has other annotations", method);
                                    checkArgument(isNullType == boolean.class, "Method [%s] has non-boolean parameter with @IsNull", method);
                                    checkArgument((parameterType == Void.class) || !Primitives.isWrapperType(parameterType), "Method [%s] uses @IsNull following a parameter with boxed primitive type: %s", method, parameterType.getSimpleName());

                                    useNullFlag = true;
                                }
                            }

                            if (useNullFlag) {
                                nullConvention = USE_NULL_FLAG;
                            }
                            else {
                                nullConvention = RETURN_NULL_ON_NULL;
                            }
                        }

                        if (nullConvention == BLOCK_AND_POSITION) {
                            argumentNativeContainerTypes.add(Optional.of(type.nativeContainerType()));
                        }
                        else {
                            inferSpecialization(method, parameterType, type.value());

                            checkCondition(type.nativeContainerType().equals(Object.class), FUNCTION_IMPLEMENTATION_ERROR, "@SqlType can only contain an explicitly specified nativeContainerType when using @BlockPosition");
                            argumentNativeContainerTypes.add(Optional.of(Primitives.unwrap(parameterType)));
                        }

                        argumentProperties.add(valueTypeArgumentProperty(nullConvention));
                        i += nullConvention.getParameterCount();
                    }
                }
            }
        }

        private void inferSpecialization(Method method, Class<?> parameterType, String typeParameterName)
        {
            if (typeParameterNames.contains(typeParameterName) && parameterType != Object.class) {
                // Infer specialization on this type parameter.
                // We don't do this for Object because it could match any type.
                Class<?> specialization = specializedTypeParameters.get(typeParameterName);
                Class<?> nativeParameterType = Primitives.unwrap(parameterType);
                checkArgument(specialization == null || specialization.equals(nativeParameterType), "Method [%s] type %s has conflicting specializations %s and %s", method, typeParameterName, specialization, nativeParameterType);
                specializedTypeParameters.put(typeParameterName, nativeParameterType);
            }
        }

        // Find matching constructor, if this is an instance method, and populate constructorDependencies
        private Optional<MethodHandle> getConstructor(Method method, Optional<Constructor<?>> optionalConstructor)
        {
            if (isStatic(method.getModifiers())) {
                return Optional.empty();
            }

            checkArgument(optionalConstructor.isPresent(), "Method [%s] is an instance method. It must be in a class annotated with @ScalarFunction or @ScalarOperator, and the class is required to have a public constructor.", method);
            Constructor<?> constructor = optionalConstructor.get();
            Set<TypeParameter> constructorTypeParameters = Stream.of(constructor.getAnnotationsByType(TypeParameter.class))
                    .collect(ImmutableSet.toImmutableSet());
            checkArgument(constructorTypeParameters.containsAll(typeParameters), "Method [%s] is an instance method and requires a public constructor containing all type parameters: %s", method, typeParameters);

            for (int i = 0; i < constructor.getParameterCount(); i++) {
                Annotation[] annotations = constructor.getParameterAnnotations()[i];
                checkArgument(containsImplementationDependencyAnnotation(annotations), "Constructors may only have meta parameters [%s]", constructor);
                checkArgument(annotations.length == 1, "Meta parameters may only have a single annotation [%s]", constructor);
                Annotation annotation = annotations[0];
                if (annotation instanceof TypeParameter) {
                    checkTypeParameters(parseTypeSignature(((TypeParameter) annotation).value()), typeParameterNames, method);
                }
                constructorDependencies.add(createDependency(annotation, literalParameters));
            }
            MethodHandle result = constructorMethodHandle(FUNCTION_IMPLEMENTATION_ERROR, constructor);
            // Change type of return value to Object to make sure callers won't have classloader issues
            return Optional.of(result.asType(result.type().changeReturnType(Object.class)));
        }

        private MethodHandle getMethodHandle(Method method)
        {
            MethodHandle methodHandle = methodHandle(FUNCTION_IMPLEMENTATION_ERROR, method);
            if (!isStatic(method.getModifiers())) {
                // Change type of "this" argument to Object to make sure callers won't have classloader issues
                methodHandle = methodHandle.asType(methodHandle.type().changeParameterType(0, Object.class));
                // Re-arrange the parameters, so that the "this" parameter is after the meta parameters
                int[] permutedIndices = new int[methodHandle.type().parameterCount()];
                permutedIndices[0] = dependencies.size();
                MethodType newType = methodHandle.type().changeParameterType(dependencies.size(), methodHandle.type().parameterType(0));
                for (int i = 0; i < dependencies.size(); i++) {
                    permutedIndices[i + 1] = i;
                    newType = newType.changeParameterType(i, methodHandle.type().parameterType(i + 1));
                }
                for (int i = dependencies.size() + 1; i < permutedIndices.length; i++) {
                    permutedIndices[i] = i;
                }
                methodHandle = permuteArguments(methodHandle, newType, permutedIndices);
            }
            return methodHandle;
        }

        public ParametricScalarImplementation get()
        {
            Signature signature = new Signature(
                    functionName,
                    SCALAR,
                    createTypeVariableConstraints(typeParameters, dependencies),
                    longVariableConstraints,
                    returnType,
                    argumentTypes,
                    false);

            return new ParametricScalarImplementation(
                    signature,
                    argumentNativeContainerTypes,
                    specializedTypeParameters,
                    choices,
                    returnNativeContainerType);
        }

        static ParametricScalarImplementation parseImplementation(String functionName, Method method, Optional<Constructor<?>> constructor)
        {
            return new Parser(functionName, method, constructor).get();
        }
    }
}
