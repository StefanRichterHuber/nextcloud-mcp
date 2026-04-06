package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search;

public class Comparison implements Condition {
    public enum Operator {
        LIKE("d", "like"),
        EQUALS("d", "eq"),
        GREATER_THAN("d", "gt"),
        LESS_THAN("d", "lt"),
        GREATER_THAN_OR_EQUALS("d", "gte"),
        LESS_THAN_OR_EQUALS("d", "lte");

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

    private final Value first;
    private final Value second;
    private final Operator operator;

    public Comparison(Value first, Value second, Operator operator) {
        this.first = first;
        this.second = second;
        this.operator = operator;
    }

    @Override
    public StringBuilder render(StringBuilder sb, int indent) {
        sb.append(indent(indent)).append("<").append(operator.getPrefix()).append(":").append(operator.getValue())
                .append(">\n");

        for (Value value : new Value[] { first, second }) {
            if (value instanceof Property) {
                sb.append(indent(indent + 1)).append("<d:prop>\n");
                value.render(sb, indent + 2).append("\n");
                sb.append(indent(indent + 1)).append("</d:prop>\n");
            } else {
                value.render(sb, indent + 1).append("\n");
            }
        }
        sb.append(indent(indent)).append("</").append(operator.getPrefix()).append(":").append(operator.getValue())
                .append(">");

        return sb;
    }

}
