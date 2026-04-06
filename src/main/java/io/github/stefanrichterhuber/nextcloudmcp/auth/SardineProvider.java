package io.github.stefanrichterhuber.nextcloudmcp.auth;

import com.github.sardine.Sardine;
import com.github.sardine.SardineFactory;

import io.github.stefanrichterhuber.nextcloudmcp.config.NextcloudConfig;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Produces;

public class SardineProvider {

    @Inject
    NextcloudAuthProvider auth;

    @Inject
    NextcloudConfig config;

    @Produces
    @RequestScoped
    public Sardine getSardineInstance() {
        Sardine sardine = SardineFactory.begin(auth.getUser(), auth.getPassword());
        sardine.enablePreemptiveAuthentication(auth.getUrl());
        return sardine;
    }

}
