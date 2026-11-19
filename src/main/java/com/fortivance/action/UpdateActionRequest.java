package com.fortivance.action;

import java.time.LocalDateTime;

public record UpdateActionRequest(
        String status,
        Long assignedTo,
        String ownerTeam,
        LocalDateTime dueAt
) {}
