package io.github.stefanrichterhuber.nextcloudmcp.auth;

import com.github.sardine.Sardine;
import com.github.sardine.SardineFactory;
import com.github.sardine.impl.SardineImpl;

import io.github.stefanrichterhuber.nextcloudlib.runtime.auth.NextcloudAdmin;
import io.github.stefanrichterhuber.nextcloudlib.runtime.auth.NextcloudAuthProvider;
import io.github.stefanrichterhuber.nextcloudmcp.config.NextcloudConfig;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

public class CustomSardineProvider {

    @Inject
    NextcloudAuthProvider auth;

    @Inject
    @NextcloudAdmin
    NextcloudAuthProvider adminAuth;

    @Inject
    NextcloudConfig config;

    @Inject
    SecurityIdentity securityIdentity;

    @Produces
    @RequestScoped
    public Sardine getSardineInstance() {
        final Sardine sardine;

        if (config.userOidc()) {
            final AccessTokenCredential cred = securityIdentity.getCredential(AccessTokenCredential.class);

            sardine = new SardineImpl(cred.getToken());
            sardine.enableCompression();
        } else {
            sardine = SardineFactory.begin(auth.getUser(), auth.getPassword());
            sardine.enablePreemptiveAuthentication(auth.getServer());
            sardine.enableCompression();
            sardine.enablePreemptiveAuthentication(auth.getServer().replace("https://", "").replace("http://", ""));
        }

        return sardine;
    }

    @Produces
    @RequestScoped
    @NextcloudAdmin
    public Sardine getSardineAdminInstance() {
        final Sardine sardine;

        if (config.userOidc()) {
            final AccessTokenCredential cred = securityIdentity.getCredential(AccessTokenCredential.class);
            sardine = new SardineImpl(cred.getToken());
        } else {
            sardine = SardineFactory.begin(adminAuth.getUser(), adminAuth.getPassword());
            sardine.enablePreemptiveAuthentication(
                    adminAuth.getServer().replace("https://", "").replace("http://", ""));
            sardine.enablePreemptiveAuthentication(adminAuth.getServer());
        }

        return sardine;
    }

}
