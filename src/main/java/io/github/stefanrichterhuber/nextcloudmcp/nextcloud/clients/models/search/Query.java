package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class Query implements Renderable {
    private final List<Property> select = new ArrayList<>();
    private final List<Order> orderBy = new ArrayList<>();
    private Condition condition = null;

    private String href = null;
    private Integer depth = null; // null -> infinity

    private Integer limit = null; // null -> no limit

    private Query() {

    }

    /**
     * Initializes a new query with the given select list
     * 
     * @param properties Properties to select
     * @return Query
     */
    public static Query select(Property... properties) {
        return select(List.of(properties));
    }

    /**
     * Initializes a new query with the given select list
     * 
     * @param properties List of properties to select
     * @return Query
     */
    public static Query select(Collection<? extends Property> properties) {
        Query q = new Query();
        q.select.addAll(properties);
        return q;
    }

    /**
     * Returns the list of properties
     * 
     * @return List of properties to select
     */
    public List<Property> getSelect() {
        return this.select;
    }

    /**
     * Relative path to start query from (e.g. /files/[user]/[folder])
     * 
     * @param href  Relative path
     * @param depth Depth of query (e.g. 1)
     * @return
     */
    public Query from(String href, int depth) {
        this.href = href;
        this.depth = depth;
        return this;
    }

    /**
     * Relative path to start query from (e.g. /files/[user]/[folder])
     * 
     * @param href Relative path
     * @return
     */
    public Query from(String href) {
        this.href = href;
        this.depth = null;
        return this;
    }

    public String getFrom() {
        return this.href;
    }

    public Query where(Condition condition) {
        this.condition = condition;
        return this;
    }

    public Condition getWhere() {
        return this.condition;
    }

    public Query orderBy(Order... orders) {
        return orderBy(List.of(orders));
    }

    public Query orderBy(Collection<? extends Order> orders) {
        this.orderBy.addAll(orders);
        return this;
    }

    public List<Order> getOrderBy() {
        return this.orderBy;
    }

    public Query limit(int limit) {
        this.limit = limit;
        return this;
    }

    public Integer getLimit() {
        return this.limit;
    }

    @Override
    public StringBuilder render(StringBuilder sb, int indent) {
        sb.append(indent(indent)).append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append(indent(indent)).append(
                "<d:searchrequest xmlns:d=\"DAV:\" xmlns:nc=\"http://nextcloud.org/ns\" xmlns:oc=\"http://owncloud.org/ns\">\n");
        sb.append(indent(indent + 1)).append("<d:basicsearch>\n");

        // Select
        sb.append(indent(indent + 2)).append("<d:select>\n");
        sb.append(indent(indent + 3)).append("<d:prop>\n");
        select.forEach(p -> p.render(sb, indent + 4).append("\n"));
        sb.append(indent(indent + 3)).append("</d:prop>\n");
        sb.append(indent(indent + 2)).append("</d:select>\n");

        // From
        sb.append(indent(indent + 2)).append("<d:from>\n");
        sb.append(indent(indent + 3)).append("<d:scope>\n");
        sb.append(indent(indent + 4)).append("<d:href>").append(this.href).append("</d:href>\n");
        sb.append(indent(indent + 4)).append("<d:depth>").append(depth != null ? depth : "infinity")
                .append("</d:depth>\n");
        sb.append(indent(indent + 3)).append("</d:scope>\n");
        sb.append(indent(indent + 2)).append("</d:from>\n");

        // Where
        if (condition != null) {
            sb.append(indent(indent + 2)).append("<d:where>\n");
            condition.render(sb, indent + 3).append("\n");
            sb.append(indent(indent + 2)).append("</d:where>\n");
        } else {
            sb.append(indent(indent + 2)).append("<d:where/>\n");
        }

        // Optional Order by
        if (orderBy.isEmpty()) {
            sb.append(indent(indent + 2)).append("<d:orderby/>\n");
        } else {
            sb.append(indent(indent + 2)).append("<d:orderby>\n");
            orderBy.forEach(o -> o.render(sb, indent + 3));
            sb.append(indent(indent + 2)).append("</d:orderby>\n");
        }

        // Optional limit
        if (limit != null) {
            sb.append(indent(indent + 2)).append("<d:limit>\n");
            sb.append(indent(indent + 3)).append("<d:nresults>").append(limit).append("</d:nresults>\n");
            sb.append(indent(indent + 2)).append("</d:limit>\n");
        }

        sb.append(indent(indent + 1)).append("</d:basicsearch>\n");
        sb.append(indent(indent)).append("</d:searchrequest>");
        return sb;
    }

    @Override
    public String toString() {
        return this.render(new StringBuilder(), 0).toString();
    }

}
