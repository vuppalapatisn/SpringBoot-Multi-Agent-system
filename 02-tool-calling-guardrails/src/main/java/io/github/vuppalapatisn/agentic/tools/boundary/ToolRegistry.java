package io.github.vuppalapatisn.agentic.tools.boundary;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Boundary metadata for every tool in the application, built by reflecting over the beans that
 * expose {@code @Tool} methods.
 *
 * <p>The guard resolves a descriptor here on every call. A tool that is not in this registry is
 * not callable — {@link #require(String)} throws — which is the fail-closed default that makes
 * classification mandatory in practice rather than by convention.
 */
public class ToolRegistry {

    private final Map<String, ToolDescriptor> byName;

    /**
     * @param toolTypes the classes that declare {@code @Tool} methods. Types rather than beans, so
     *                  the registry can be built before the tool beans themselves exist — the guard
     *                  they depend on needs the registry.
     */
    public ToolRegistry(List<Class<?>> toolTypes) {
        Map<String, ToolDescriptor> descriptors = new LinkedHashMap<>();
        for (Class<?> type : toolTypes) {
            ReflectionUtils.doWithMethods(type, method -> {
                Tool tool = AnnotationUtils.findAnnotation(method, Tool.class);
                if (tool == null) {
                    return;
                }
                String name = tool.name().isBlank() ? method.getName() : tool.name();
                ToolBoundary boundary = AnnotationUtils.findAnnotation(method, ToolBoundary.class);
                if (boundary == null) {
                    throw new UnclassifiedToolException(type.getSimpleName() + "#" + method.getName()
                            + " is annotated @Tool but not @ToolBoundary. Classify it (see docs/03-TOOL-BOUNDARIES.md).");
                }
                descriptors.put(name, ToolDescriptor.from(name, boundary));
            });
        }
        this.byName = Map.copyOf(descriptors);
    }

    public ToolDescriptor require(String toolName) {
        ToolDescriptor descriptor = byName.get(toolName);
        if (descriptor == null) {
            throw new UnclassifiedToolException("tool '" + toolName + "' has no boundary classification");
        }
        return descriptor;
    }

    public Optional<ToolDescriptor> find(String toolName) {
        return Optional.ofNullable(byName.get(toolName));
    }

    public Map<String, ToolDescriptor> all() {
        return byName;
    }

    public List<ToolDescriptor> irreversible() {
        return byName.values().stream().filter(ToolDescriptor::irreversible).toList();
    }

    /** Thrown at startup for an unclassified tool, and at call time for an unknown one. */
    public static class UnclassifiedToolException extends IllegalStateException {
        public UnclassifiedToolException(String message) {
            super(message);
        }
    }
}
