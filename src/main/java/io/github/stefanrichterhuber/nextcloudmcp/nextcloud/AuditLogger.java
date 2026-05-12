package io.github.stefanrichterhuber.nextcloudmcp.nextcloud;

/**
 * Implementations of this interface for logging AuditLogEntry objects
 */
public interface AuditLogger {
    void log(AuditLogEntry auditLogEntry);
}
