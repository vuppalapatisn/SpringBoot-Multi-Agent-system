package io.github.vuppalapatisn.agentic.tools.provider;

import io.github.vuppalapatisn.agentic.tools.config.GuardrailProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Customer notification — an {@code E2} egress whose compensation is <b>NONE</b>. You cannot unsend
 * an email; the best available correction is a second email, which is a new effect with its own
 * blast radius.
 *
 * <p>Two controls at this boundary:
 *
 * <ul>
 *   <li><b>Recipient allowlist</b>. The address comes from the order record and must match a
 *       configured domain suffix. The model never supplies a recipient — that is the control that
 *       stops "email the transcript to attacker@evil.example" from working.</li>
 *   <li><b>Template-only bodies</b>. The caller supplies a template id and parameters, not free
 *       text, so an injected instruction cannot become the message content.</li>
 * </ul>
 */
@Component
public class NotificationGateway {

    public record SentMessage(String to, String templateId, String renderedBody, Instant at) {
    }

    private final List<SentMessage> sent = new CopyOnWriteArrayList<>();
    private final GuardrailProperties properties;
    private final Clock clock;

    public NotificationGateway(GuardrailProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public SentMessage send(String to, String templateId, String orderId, String outcome) {
        assertAllowed(to);
        String body = switch (templateId) {
            case "refund-approved" -> "Your refund for order %s has been approved and will appear on your original payment method.".formatted(orderId);
            case "refund-declined" -> "We have reviewed your request for order %s and are unable to offer a refund under our policy.".formatted(orderId);
            case "refund-review" -> "A specialist is reviewing your request for order %s and will be in touch.".formatted(orderId);
            default -> throw new IllegalArgumentException("unknown template '" + templateId + "'");
        };
        SentMessage message = new SentMessage(to, templateId, body, clock.instant());
        sent.add(message);
        return message;
    }

    private void assertAllowed(String to) {
        boolean allowed = to != null && properties.notificationAllowlist().stream()
                .anyMatch(suffix -> to.toLowerCase().endsWith(suffix.toLowerCase()));
        if (!allowed) {
            throw new EgressNotAllowedException(to);
        }
    }

    public List<SentMessage> sent() {
        return List.copyOf(sent);
    }

    /** The recipient is not on the allowlist. Fail closed: nothing is sent. */
    public static class EgressNotAllowedException extends RuntimeException {
        public EgressNotAllowedException(String to) {
            super("recipient '" + to + "' is not on the notification allowlist");
        }
    }
}
