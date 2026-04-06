package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search;

import java.util.List;

public class Compound implements Condition {
    public enum Operator {
        AND("d", "and"), OR("d", "or");

        private final String prefix;
        private final String value;

        private Operator(String prefix, String value) {
            this.prefix = prefix;
            this.value = value;
        }

        public String getPrefix() {
            return this.prefix;
        }

        public String getValue() {
            return this.value;
        }
    }

    private final List<Condition> parts;
    private final Operator operator;

    public Compound(List<Condition> parts, Operator operator) {
        this.operator = operator;
        this.parts = parts;
    }

    @Override
    public StringBuilder render(StringBuilder sb, int indent) {
        sb.append(indent(indent)).append("<").append(operator.getPrefix()).append(":").append(operator.getValue())
                .append(">\n");

        for (Condition c : parts) {
            c.render(sb, indent + 1).append("\n");
        }

        sb.append(indent(indent)).append("</").append(operator.getPrefix()).append(":").append(operator.getValue())
                .append(">");
        return sb;
    }

    public static Compound and(Condition... parts) {
        return new Compound(List.of(parts), Operator.AND);
    }

    public static Compound or(Condition... parts) {
        return new Compound(List.of(parts), Operator.OR);
    }
}
