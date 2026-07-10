package com.tasksphere.service;

import com.tasksphere.entity.AuditLog;
import com.tasksphere.repository.AuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AuditLogService {

    @Autowired private AuditLogRepository auditLogRepo;

    public void log(String actorEmail, String actorName, AuditLog.AuditAction action,
                     String entityType, String entityId, String details) {
        try {
            auditLogRepo.save(AuditLog.builder()
                    .actorEmail(actorEmail)
                    .actorName(actorName)
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .details(details)
                    .build());
        } catch (Exception ignored) {
            // Auditing must never break the primary operation
        }
    }
}
