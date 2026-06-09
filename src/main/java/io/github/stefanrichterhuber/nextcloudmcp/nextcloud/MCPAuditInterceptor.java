package io.github.stefanrichterhuber.nextcloudmcp.nextcloud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.stefanrichterhuber.nextcloudmcp.config.AppConfig;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

@MCPAudit
@Priority(Integer.MAX_VALUE)
@Interceptor
public class MCPAuditInterceptor {
    @Inject
    MCPAuditService auditService;

    @Inject
    AppConfig config;

    @Inject
    ObjectMapper objectMapper;

    @SuppressWarnings({ "null" })
    @AroundInvoke
    Object logCall(InvocationContext context) throws Exception {
        if (config.audit().enabled()) {
            final Tool tool = context.getMethod().getAnnotation(Tool.class);
            if (tool != null) {
                final List<ToolArg> parameterAnnotations = new ArrayList<>(context.getMethod().getParameterCount());
                final List<Object> parameterValues = new ArrayList<>(context.getMethod().getParameterCount());
                for (int i = 0; i < context.getParameters().length; i++) {
                    // Check for a ToolArg annotation
                    final ToolArg toolArg = Arrays.asList(context.getMethod().getParameterAnnotations()[i]).stream()
                            .filter(a -> a instanceof ToolArg).map(a -> (ToolArg) a).findFirst().orElse(null);
                    if (toolArg != null) {
                        parameterAnnotations.add(toolArg);
                        parameterValues.add(context.getParameters()[i]);
                    }
                }
                final long startTime = System.currentTimeMillis();
                try {
                    final Object result = context.proceed();
                    final long endTime = System.currentTimeMillis();
                    if (result instanceof ToolResponse toolResponse) {
                        auditService.logCall(tool, parameterAnnotations, parameterValues, !toolResponse.isError(),
                                toolResponse.content(),
                                startTime, endTime);
                    } else {
                        auditService.logCall(tool, parameterAnnotations, parameterValues, true,
                                result instanceof Collection<?> resultList ? resultList : List.of(result), startTime,
                                endTime);
                    }
                    return result;
                } catch (Exception e) {
                    final long endTime = System.currentTimeMillis();
                    auditService.logCall(tool, parameterAnnotations, parameterValues, false,
                            List.of(new TextContent(e.getMessage())), startTime,
                            endTime);
                    throw e;
                }
            } else {
                return context.proceed();
            }
        } else {
            return context.proceed();
        }
    }

}
