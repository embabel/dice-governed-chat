package example.dice.governed;

import com.embabel.dice.audit.AuditCoverageException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;

/** Exposes one authorized workspace through REST and AG-UI. */
@RestController
@RequestMapping("/api/workspaces/{workspace}")
final class ChatHttp {
    private final Workflow workflow;
    private final Storage storage;
    private final DemoAccess access;
    private final AgUi agUi;

    ChatHttp(Workflow workflow, Storage storage, DemoAccess access, JsonMapper json) {
        this.workflow = workflow;
        this.storage = storage;
        this.access = access;
        this.agUi = new AgUi(json);
    }

    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<String> chat(@PathVariable String workspace,
                                @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                @RequestBody AgUi.Run run) {
        access.require(workspace, authorization, false);
        var message = run.userMessage();
        var turn = workflow.tell(workspace, message.id(), message.content());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(agUi.success(run, turn.reply(), workflow.state(workspace)));
    }

    @GetMapping("/state")
    Workflow.State state(@PathVariable String workspace,
                         @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        access.require(workspace, authorization, false);
        return workflow.state(workspace);
    }

    @PostMapping("/reviews/{reviewId}/approve")
    Workflow.State approve(@PathVariable String workspace, @PathVariable String reviewId,
                           @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        access.require(workspace, authorization, true);
        return workflow.approve(workspace, reviewId);
    }

    @GetMapping("/history")
    Workflow.History history(@PathVariable String workspace, @RequestParam Instant asOf,
                             @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        access.require(workspace, authorization, false);
        return workflow.history(workspace, asOf);
    }

    @GetMapping("/sources/{sourceId}")
    Source source(@PathVariable String workspace, @PathVariable String sourceId,
                  @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        access.require(workspace, authorization, false);
        return storage.source(workspace, sourceId)
                .map(text -> new Source(sourceId, text))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source unavailable"));
    }

    @ExceptionHandler(AuditCoverageException.class)
    ResponseEntity<Map<String, String>> incompleteAudit() {
        return ResponseEntity.unprocessableContent()
                .body(Map.of("message", "History is unavailable at the requested time."));
    }

    record Source(String id, String text) {
    }
}
