package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search;

public class Not implements Condition {

    private final Condition condition;

    public Not(Condition condition) {
        this.condition = condition;
    }

    public static Not not(Condition condition) {
        return new Not(condition);
    }

    @Override
    public StringBuilder render(StringBuilder sb, int indent) {
        sb.append(indent(indent)).append("<d:not>\n");
        condition.render(sb, indent + 1).append("\n");
        sb.append(indent(indent)).append("</d:not>");
        return sb;
    }

}
