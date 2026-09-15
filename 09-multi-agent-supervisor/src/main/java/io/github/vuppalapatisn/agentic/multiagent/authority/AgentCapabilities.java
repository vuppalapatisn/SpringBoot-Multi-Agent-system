package io.github.vuppalapatisn.agentic.multiagent.authority;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The capability register: which tool each agent holds, and its boundary class.
 *
 * <p>Checked at construction, so an agent handed a capability its role forbids is a <b>startup
 * failure</b>. That is the difference between least authority as a principle and least authority as
 * a property of the running system.
 *
 * <p>In a deployment where each agent is a separate process, this register is also the manifest for
 * which credentials each one receives — the fraud agent gets no payment key at all, rather than
 * getting one and being asked not to use it.
 */
public class AgentCapabilities {

    private static final Logger log = LoggerFactory.getLogger(AgentCapabilities.class);

    /** One capability an agent holds. */
    public record Capability(String tool, AgentRole.BoundaryClass boundaryClass) {
    }

    /** Thrown when an agent declares a capability its role forbids. */
    public static class AuthorityViolationException extends IllegalStateException {
        public AuthorityViolationException(String message) {
            super(message);
        }
    }

    private final Map<AgentRole, List<Capability>> byRole;

    public AgentCapabilities(Map<AgentRole, List<Capability>> declared) {
        Map<AgentRole, List<Capability>> copy = new LinkedHashMap<>();
        declared.forEach((role, capabilities) -> {
            capabilities.forEach(capability -> {
                if (!role.may(capability.boundaryClass())) {
                    throw new AuthorityViolationException(
                            ("agent %s declares tool '%s' of class %s, which its role does not "
                                    + "permit (allowed: %s)")
                                    .formatted(role, capability.tool(), capability.boundaryClass(),
                                            role.allowedClasses()));
                }
            });
            copy.put(role, List.copyOf(capabilities));
        });
        this.byRole = Map.copyOf(copy);

        long effectfulRoles = this.byRole.entrySet().stream()
                .filter(entry -> entry.getValue().stream()
                        .anyMatch(capability -> capability.boundaryClass().effectful()))
                .count();
        if (effectfulRoles > 1) {
            throw new AuthorityViolationException(
                    "more than one agent role holds an effectful capability; the blast radius of a "
                            + "compromise is no longer contained to one agent");
        }

        log.info("agent capability register:\n{}", describe());
    }

    public List<Capability> of(AgentRole role) {
        return byRole.getOrDefault(role, List.of());
    }

    public Set<AgentRole> roles() {
        return byRole.keySet();
    }

    /** Roles that can change the world. Exactly one, by construction. */
    public List<AgentRole> effectfulRoles() {
        return byRole.entrySet().stream()
                .filter(entry -> entry.getValue().stream()
                        .anyMatch(capability -> capability.boundaryClass().effectful()))
                .map(Map.Entry::getKey)
                .toList();
    }

    public String describe() {
        StringBuilder text = new StringBuilder();
        byRole.forEach((role, capabilities) -> {
            text.append("  %-8s allowed=%s%n".formatted(role, role.allowedClasses()));
            capabilities.forEach(capability -> text.append("           %-20s %s%n"
                    .formatted(capability.tool(), capability.boundaryClass())));
        });
        return text.toString();
    }
}
