package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search;

import java.io.IOException;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;

/**
 * This WriterInterceptor directly renders a Query into the XML string. All
 * other entities are ignored
 */
@Provider
public class QueryIOInterceptor implements WriterInterceptor {

    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
        Object entity = context.getEntity();
        if (entity instanceof Query) {
            final String xml = ((Query) entity).render(new StringBuilder(), 0).toString();
            context.setEntity(xml);
        }
        context.proceed();
    }

}
