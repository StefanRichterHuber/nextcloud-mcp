package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;

public interface Value extends Renderable {
    /**
     * Converts Strings, Numbers, Dates, LocalDateTime and ZonedDateTime to a Value.
     * If the given object is already a Value is returned as it is
     * 
     * @param value
     * @return
     */
    public static Value of(Object value) {
        if (value instanceof Value) {
            return (Value) value;
        }
        if (value instanceof String) {
            return new Literal((String) value);
        }
        if (value instanceof Number) {
            return new Literal(value.toString());
        }
        if (value instanceof java.util.Date) {
            // Format: 2021-01-01T17:00:00Z
            // Convert value to ISO Date time string
            return new Literal(new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format((Date) value));
        }
        if (value instanceof LocalDateTime) {
            // Convert to java.util.Date
            ZoneId zoneId = ZoneId.systemDefault();
            ZoneOffset offset = zoneId.getRules().getOffset(((LocalDateTime) value));
            return of(new Date(((LocalDateTime) value).toEpochSecond(offset)));
        }
        if (value instanceof ZonedDateTime) {
            return of(new Date(((ZonedDateTime) value).toEpochSecond()));
        }
        return new Literal(value.toString());

    }

    public default Condition like(Object b) {
        return Condition.like(this, b);
    }

    public default Condition notLike(Object b) {
        return Condition.notLike(this, b);
    }

    public default Condition equal(Object b) {
        return Condition.equals(this, b);
    }

    public default Condition notEquals(Object b) {
        return Condition.not(Condition.equals(this, b));
    }

    public default Condition lessThan(Object b) {
        return Condition.lessThan(this, b);
    }

    public default Condition lessThanOrEquals(Object b) {
        return Condition.lessThanOrEquals(this, b);
    }

    public default Condition greaterThan(Object b) {
        return Condition.greaterThan(this, b);
    }

    public default Condition greaterThanOrEquals(Object b) {
        return Condition.greaterThanOrEquals(this, b);
    }

}
