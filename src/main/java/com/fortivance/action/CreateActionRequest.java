package com.fortivance.action;

import java.time.LocalDateTime;

public record CreateActionRequest(
        Long incidentId,
        Long vendorId,
        Long evidenceMappingId,
        String title,
        String ownerTeam,
        Long assignedTo,
        String priority,
        LocalDateTime dueAt
) {}
