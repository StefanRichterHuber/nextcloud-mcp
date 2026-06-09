package io.github.stefanrichterhuber.nextcloudmcp.audit;

/**
 * Implementations of this interface for logging AuditLogEntry objects
 */
public interface AuditLogger {
    void log(AuditLogEntry auditLogEntry);
}
