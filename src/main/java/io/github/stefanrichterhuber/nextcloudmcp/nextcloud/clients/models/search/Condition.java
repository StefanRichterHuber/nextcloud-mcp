package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search;

import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search.Comparison.Operator;

public interface Condition extends Renderable {

    /**
     * This or the given condition must match
     * 
     * @param other
     * @return
     */
    public default Condition or(Condition other) {
        return or(this, other);
    }

    /**
     * This and the given condition must match
     * 
     * @param other
     * @return
     */
    public default Condition and(Condition other) {
        return and(this, other);
    }

    /**
     * All given conditions must match
     * 
     * @param conditions
     * @return
     */
    public static Condition and(Condition... conditions) {
        return Compound.and(conditions);
    }

    /*
     * Any of the given conditions must match
     */
    public static Condition or(Condition... conditions) {
        return Compound.or(conditions);
    }

    /*
     * Negates the given condition
     */
    public static Condition not(Condition condition) {
        return Not.not(condition);
    }

    /**
     * Is the object a collection (= folder)?
     * 
     * @return
     */
    public static Condition isCollection() {
        return IsCollection.isCollection();
    }

    /**
     * Is the object a file (= not a collection)?
     * 
     * @return
     */
    public static Condition isFile() {
        return not(isCollection());
    }

    public static Condition like(Object a, Object b) {
        return new Comparison(Value.of(a), Value.of(b), Operator.LIKE);
    }

    public static Condition notLike(Object a, Object b) {
        return not(like(a, b));
    }

    public static Condition equals(Object a, Object b) {
        return new Comparison(Value.of(a), Value.of(b), Operator.EQUALS);
    }

    public static Condition notEquals(Object a, Object b) {
        return not(equals(a, b));
    }

    public static Condition isNotNull(Object value) {
        return new IsDefined(Value.of(value));
    }

    public static Condition isNull(Object value) {
        return not((isNotNull(value)));
    }

    public static Condition lessThan(Object a, Object b) {
        return new Comparison(Value.of(a), Value.of(b), Operator.LESS_THAN);
    }

    public static Condition lessThanOrEquals(Object a, Object b) {
        return new Comparison(Value.of(a), Value.of(b), Operator.LESS_THAN_OR_EQUALS);
    }

    public static Condition greaterThan(Object a, Object b) {
        return new Comparison(Value.of(a), Value.of(b), Operator.GREATER_THAN);
    }

    public static Condition greaterThanOrEquals(Object a, Object b) {
        return new Comparison(Value.of(a), Value.of(b), Operator.GREATER_THAN_OR_EQUALS);
    }

}
