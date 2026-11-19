package com.fortivance.action;

import java.time.Instant;
import java.time.LocalDateTime;

public record ActionRecord(
        Long id,
        Long incidentId,
        Long vendorId,
        Long evidenceMappingId,
        String title,
        String ownerTeam,
        Long assignedTo,
        String status,
        String priority,
        String source,
        LocalDateTime dueAt,
        Instant createdAt,
        LocalDateTime closedAt
) {}
