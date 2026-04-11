package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients;

import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;

import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.FulltextSearchQuery;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.FulltextSearchResult;
import io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models.search.Query;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

@RegisterClientHeaders(NextcloudAPIClientHeaders.class)
public interface NextcloudRestClient {
    public static record CreateSystemTagRequest(String name, boolean userVisible, boolean userAssignable,
            boolean canAssign) {
    }

    /**
     * Performs webdav search on nextcloud server
     * 
     * @param query XML String containing a MultiStatus response
     * @return
     */
    @SEARCH
    @Produces(MediaType.APPLICATION_XML)
    @Consumes(MediaType.TEXT_XML)
    @Path("remote.php/dav")
    String search(Query query);

    /**
     * Creates a new global system tag with the given configuration
     * 
     * @param req
     */
    @POST
    @Path("remote.php/dav/systemtags")
    void createNewGlobalSystemTag(CreateSystemTagRequest req);

    /**
     * Performs a full-text search on the nextcloud server
     * 
     * @param request JSON string of {@link FulltextSearchQuery}
     * @return Search result
     */
    @GET
    @Path("index.php/apps/fulltextsearch/v1/remote")
    FulltextSearchResult fulltextsearch(@QueryParam("request") FulltextSearchQuery request);
}
