package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * @see https://github.com/nextcloud/fulltextsearch/wiki/Include-Full-text-search-in-your-client
 */
@RegisterForReflection
public record FulltextSearchQuery(String providers, String search, int page, int size, Options options) {
    public record Options(String files_local, String files_external, String files_extension) {
    }

    /**
     * Creates a FulltextSearchQuery with some reasonable defaults
     * 
     * @param query Query string to search for
     * @return FulltextSearchQuery
     */
    public static FulltextSearchQuery search(String query) {
        return search(query, 1, 20);
    }

    /**
     * Creates a FulltextSearchQuery with some reasonable defaults
     * 
     * @param query Query string to search for
     * @param page  search result page
     * @param size  number of results per page
     * @return FulltextSearchQuery
     */
    public static FulltextSearchQuery search(String query, int page, int size) {
        final FulltextSearchQuery q = new FulltextSearchQuery("all", query, page, size, new Options("", "", ""));
        return q;
    }

    /**
     * This is called by JAX-RS client to serialize this object. Therefore create
     * the required JSON document
     */
    public String toString() {
        final ObjectMapper om = new ObjectMapper();
        try {
            final String request = om.writeValueAsString(this);
            return request;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
