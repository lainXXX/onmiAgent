package top.javarem.omni.tool.bash;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalServiceTest {

    private ApprovalService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalService();
    }

    @Test
    void shouldCreateTicketAndReturnPending() {
        ApprovalService.CheckResult result = service.createPendingTicket("rm -rf ./src");
        assertNotNull(result.ticketId());
        assertTrue(result.message().contains("待审批"));
    }

    @Test
    void shouldApproveAndConsumeAtomically() {
        ApprovalService.CheckResult pending = service.createPendingTicket("rm -rf ./src");
        String ticketId = pending.ticketId();

        boolean approved = service.submitApproval(ticketId, "rm -rf ./src", true);
        assertTrue(approved);

        ApprovalService.CheckResult consumed = service.checkAndConsume(ticketId, "rm -rf ./src");
        assertEquals(ApprovalService.CheckResult.Status.APPROVED, consumed.status());
    }

    @Test
    void shouldRejectConsumeAfterDenied() {
        ApprovalService.CheckResult pending = service.createPendingTicket("rm -rf ./src");
        service.submitApproval(pending.ticketId(), "rm -rf ./src", false);

        ApprovalService.CheckResult consumed = service.checkAndConsume(
            pending.ticketId(), "rm -rf ./src");
        assertEquals(ApprovalService.CheckResult.Status.REJECTED, consumed.status());
    }

    @Test
    void shouldRejectMismatchedCommand() {
        ApprovalService.CheckResult pending = service.createPendingTicket("rm -rf ./src");
        service.submitApproval(pending.ticketId(), "rm -rf ./src", true);

        ApprovalService.CheckResult consumed = service.checkAndConsume(
            pending.ticketId(), "rm -rf ./different");
        assertEquals(ApprovalService.CheckResult.Status.REJECTED, consumed.status());
    }

    @Test
    void shouldRejectConsumeTwice() {
        ApprovalService.CheckResult pending = service.createPendingTicket("ls");
        service.submitApproval(pending.ticketId(), "ls", true);

        service.checkAndConsume(pending.ticketId(), "ls");
        ApprovalService.CheckResult second = service.checkAndConsume(pending.ticketId(), "ls");
        assertEquals(ApprovalService.CheckResult.Status.EXPIRED, second.status());
    }

    @Test
    void shouldRejectUnknownTicket() {
        ApprovalService.CheckResult result = service.checkAndConsume("fake-ticket", "ls");
        assertEquals(ApprovalService.CheckResult.Status.EXPIRED, result.status());
    }

    @Test
    void shouldCleanUpConsumedTicketsAfterShortTTL() {
        ApprovalService.CheckResult pending = service.createPendingTicket("ls");
        service.submitApproval(pending.ticketId(), "ls", true);
        service.checkAndConsume(pending.ticketId(), "ls");

        ApprovalService.CheckResult second = service.checkAndConsume(pending.ticketId(), "ls");
        assertEquals(ApprovalService.CheckResult.Status.EXPIRED, second.status());
    }
}