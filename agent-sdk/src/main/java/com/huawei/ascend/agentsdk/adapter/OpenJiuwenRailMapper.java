package com.huawei.ascend.agentsdk.adapter;

import com.huawei.ascend.agentsdk.spec.rail.RailSpec;
import com.huawei.ascend.agentsdk.support.ValidationException;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

public final class OpenJiuwenRailMapper {

    public AgentRail toAgentRail(RailSpec spec) {
        AgentRail rail = toRail(spec, AgentRail.class);
        applyPriority(spec, rail);
        return rail;
    }

    public AgentRail toDeepAgentRail(RailSpec spec) {
        // DeepAgent currently accepts AgentRail instances; keep a separate entry point for future DeepAgent-only checks.
        AgentRail rail = toRail(spec, AgentRail.class);
        applyPriority(spec, rail);
        return rail;
    }

    private AgentRail toRail(RailSpec spec, Class<? extends AgentRail> expectedType) {
        if ("function".equalsIgnoreCase(spec.type())) {
            return functionRail(spec);
        }
        Object instance = instantiate(spec.className());
        if (!expectedType.isInstance(instance)) {
            throw new ValidationException("Rail class must implement " + expectedType.getSimpleName()
                    + ": " + spec.className());
        }
        return expectedType.cast(instance);
    }

    private static void applyPriority(RailSpec spec, AgentRail rail) {
        if (spec.priority() != null) {
            rail.setPriority(spec.priority());
        }
    }

    private static Object instantiate(String className) {
        try {
            Class<?> type = Class.forName(className);
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException error) {
            throw new ValidationException("Failed to instantiate rail class: " + className, error);
        }
    }

    private static AgentRail functionRail(RailSpec spec) {
        FunctionMethod method = findFunctionMethod(spec);
        AgentCallbackEvent event = event(spec.event());
        return new FunctionRail(event, context -> invoke(method, context));
    }

    private static FunctionMethod findFunctionMethod(RailSpec spec) {
        try {
            Class<?> type = Class.forName(spec.className());
            Method contextMethod = findMethod(type, spec.method(), AgentCallbackContext.class);
            if (contextMethod != null) {
                if (!Modifier.isStatic(contextMethod.getModifiers()) || contextMethod.getReturnType() != Void.TYPE) {
                    throw new ValidationException("Function rail method must be static and return void: "
                            + spec.className() + "#" + spec.method());
                }
                return new FunctionMethod(contextMethod, FunctionMethodKind.CONTEXT_VOID);
            }
            Method mapMethod = findMethod(type, spec.method(), Map.class);
            if (mapMethod != null) {
                if (!Modifier.isStatic(mapMethod.getModifiers()) || !Map.class.isAssignableFrom(mapMethod.getReturnType())) {
                    throw new ValidationException("Function rail method must be static and return Map: "
                            + spec.className() + "#" + spec.method());
                }
                return new FunctionMethod(mapMethod, FunctionMethodKind.EXTRA_MAP);
            }
            throw new ValidationException("Function rail method must accept AgentCallbackContext or Map: "
                    + spec.className() + "#" + spec.method());
        } catch (ClassNotFoundException error) {
            throw new ValidationException("Function rail class not found: " + spec.className(), error);
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?> argumentType) {
        try {
            return type.getMethod(name, argumentType);
        } catch (NoSuchMethodException error) {
            return null;
        }
    }

    private static void invoke(FunctionMethod method, AgentCallbackContext context) {
        try {
            if (method.kind() == FunctionMethodKind.CONTEXT_VOID) {
                method.method().invoke(null, context);
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) method.method()
                    .invoke(null, new LinkedHashMap<>(context.getExtra() == null ? Map.of() : context.getExtra()));
            context.setExtra(result == null ? Map.of() : result);
        } catch (IllegalAccessException error) {
            throw new ValidationException("Function rail method is not accessible: " + method, error);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw new ValidationException("Function rail method failed: " + method, cause);
        }
    }

    private record FunctionMethod(Method method, FunctionMethodKind kind) {
    }

    private enum FunctionMethodKind {
        CONTEXT_VOID,
        EXTRA_MAP
    }

    private static AgentCallbackEvent event(String value) {
        return switch (value) {
            case "beforeModelCall" -> AgentCallbackEvent.BEFORE_MODEL_CALL;
            case "afterModelCall" -> AgentCallbackEvent.AFTER_MODEL_CALL;
            case "beforeToolCall" -> AgentCallbackEvent.BEFORE_TOOL_CALL;
            case "afterToolCall" -> AgentCallbackEvent.AFTER_TOOL_CALL;
            default -> throw new ValidationException("Unsupported function rail event: " + value);
        };
    }

    private static final class FunctionRail extends AgentRail {
        private final AgentCallbackEvent event;
        private final java.util.function.Consumer<AgentCallbackContext> callback;

        private FunctionRail(AgentCallbackEvent event, java.util.function.Consumer<AgentCallbackContext> callback) {
            this.event = event;
            this.callback = callback;
        }

        @Override
        public Map<AgentCallbackEvent, java.util.function.Consumer<AgentCallbackContext>> getCallbacks() {
            return Map.of(event, callback);
        }
    }
}
