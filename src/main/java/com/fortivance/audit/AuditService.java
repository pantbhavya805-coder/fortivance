package com.fortivance.audit;

import java.util.Map;

public interface AuditService {
    void log(String actor, String action, String entityType, Long entityId, Map<String, Object> detail);
}
