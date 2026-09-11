package com.nforce.onehr.repository;

import com.nforce.onehr.entity.HelpdeskTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Extends {@link JpaSpecificationExecutor} — same precedent as AuditLogRepository — because
 * the ticket queue/mine endpoints have several independently-optional filters (status, search,
 * assignee) that must be combined at the DB level under real pagination, unlike Leave/
 * Onboarding's simpler in-memory list filtering.
 */
@Repository
public interface HelpdeskTicketRepository extends JpaRepository<HelpdeskTicket, UUID>, JpaSpecificationExecutor<HelpdeskTicket> {

    Optional<HelpdeskTicket> findByIdAndEmployeeUserId(UUID id, UUID employeeUserId);

    @Query(value = "SELECT nextval('helpdesk_ticket_no_seq')", nativeQuery = true)
    long nextTicketSequence();
}
