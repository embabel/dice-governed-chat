package example.dice.governed;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Maps the local demo personas to their server-owned workspace and permissions. */
@Component
final class DemoAccess {
    private final String desk;
    private final String reviewer;
    private final String beta;

    DemoAccess(DemoAccessProperties properties) {
        var desk = properties.deskToken();
        var reviewer = properties.reviewerToken();
        var beta = properties.betaToken();
        if (desk.isBlank() || reviewer.isBlank() || beta.isBlank()
                || desk.equals(reviewer) || desk.equals(beta) || reviewer.equals(beta)) {
            throw new IllegalArgumentException("Demo persona keys must be distinct and nonempty");
        }
        this.desk = "Bearer " + desk;
        this.reviewer = "Bearer " + reviewer;
        this.beta = "Bearer " + beta;
    }

    void require(String workspace, String authorization, boolean approval) {
        boolean isReviewer = reviewer.equals(authorization);
        boolean inWorkspace = switch (workspace) {
            case "acme" -> desk.equals(authorization) || isReviewer;
            case "beta" -> beta.equals(authorization);
            default -> false;
        };
        if (!inWorkspace) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace unavailable");
        }
        if (approval && !isReviewer) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Approval requires a reviewer");
        }
    }
}
