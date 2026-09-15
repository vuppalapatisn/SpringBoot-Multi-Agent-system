package io.github.vuppalapatisn.agentic.rag.web;

import io.github.vuppalapatisn.agentic.rag.domain.GroundedAnswer;
import io.github.vuppalapatisn.agentic.rag.service.PolicyAnswerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/policy")
class PolicyController {

    /**
     * In a real deployment the tenant comes from the authenticated principal, not the request body.
     * It is a field here so the isolation behaviour is demonstrable with {@code curl} — the
     * validation pattern is what stops it being a filter-injection point either way.
     */
    record AskRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{1,40}") String tenant,
            @NotBlank @Size(max = 2_000) String question) {
    }

    private final PolicyAnswerService answers;

    PolicyController(PolicyAnswerService answers) {
        this.answers = answers;
    }

    @PostMapping("/ask")
    GroundedAnswer ask(@Valid @RequestBody AskRequest request) {
        return answers.answer(request.tenant(), request.question());
    }
}
