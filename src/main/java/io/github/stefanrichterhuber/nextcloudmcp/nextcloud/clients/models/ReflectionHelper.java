package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients.models;

import org.apache.http.auth.AuthScheme;
import org.apache.http.auth.ContextAwareAuthScheme;
import org.apache.http.impl.auth.AuthSchemeBase;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.auth.RFC2617Scheme;

import com.github.sardine.DavResource;
import com.github.sardine.model.Ace;
import com.github.sardine.model.Acl;
import com.github.sardine.model.Activelock;
import com.github.sardine.model.All;
import com.github.sardine.model.Allprop;
import com.github.sardine.model.Authenticated;
import com.github.sardine.model.Bind;
import com.github.sardine.model.Collection;
import com.github.sardine.model.Creationdate;
import com.github.sardine.model.Deny;
import com.github.sardine.model.Displayname;
import com.github.sardine.model.Error;
import com.github.sardine.model.Exclusive;
import com.github.sardine.model.Getcontentlanguage;
import com.github.sardine.model.Getcontentlength;
import com.github.sardine.model.Getcontenttype;
import com.github.sardine.model.Getetag;
import com.github.sardine.model.Getlastmodified;
import com.github.sardine.model.Grant;
import com.github.sardine.model.Group;
import com.github.sardine.model.Inherited;
import com.github.sardine.model.Keepalive;
import com.github.sardine.model.Limit;
import com.github.sardine.model.Link;
import com.github.sardine.model.Location;
import com.github.sardine.model.Lockdiscovery;
import com.github.sardine.model.Lockentry;
import com.github.sardine.model.Lockinfo;
import com.github.sardine.model.Lockscope;
import com.github.sardine.model.Locktoken;
import com.github.sardine.model.Locktype;
import com.github.sardine.model.Multistatus;
import com.github.sardine.model.ObjectFactory;
import com.github.sardine.model.Omit;
import com.github.sardine.model.Owner;
import com.github.sardine.model.Principal;
import com.github.sardine.model.PrincipalCollectionSet;
import com.github.sardine.model.PrincipalURL;
import com.github.sardine.model.Privilege;
import com.github.sardine.model.Prop;
import com.github.sardine.model.Propertybehavior;
import com.github.sardine.model.Propertyupdate;
import com.github.sardine.model.Propfind;
import com.github.sardine.model.Propname;
import com.github.sardine.model.Propstat;
import com.github.sardine.model.Protected;
import com.github.sardine.model.QuotaAvailableBytes;
import com.github.sardine.model.QuotaUsedBytes;
import com.github.sardine.model.Read;
import com.github.sardine.model.ReadAcl;
import com.github.sardine.model.ReadCurrentUserPrivilegeSet;
import com.github.sardine.model.Remove;
import com.github.sardine.model.Report;
import com.github.sardine.model.Resourcetype;
import com.github.sardine.model.Response;
import com.github.sardine.model.SearchRequest;
import com.github.sardine.model.Self;
import com.github.sardine.model.Set;
import com.github.sardine.model.Shared;
import com.github.sardine.model.SimplePrivilege;
import com.github.sardine.model.Source;
import com.github.sardine.model.SupportedReport;
import com.github.sardine.model.SupportedReportSet;
import com.github.sardine.model.Supportedlock;
import com.github.sardine.model.SyncCollection;
import com.github.sardine.model.UnBind;
import com.github.sardine.model.Unauthenticated;
import com.github.sardine.model.Unlock;
import com.github.sardine.model.Write;
import com.github.sardine.model.WriteContent;
import com.github.sardine.model.WriteProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * This utility class solely exists to register 3rd party libraries (espcially
 * Sardine) for
 * reflection, which is necessary for native image build.
 */
@RegisterForReflection(targets = {
        /*
         * Sardine models
         */
        Multistatus.class, Response.class, DavResource.class, Propstat.class, Report.class,
        Collection.class, Ace.class, Acl.class, Activelock.class, All.class, Allprop.class, Authenticated.class,
        Bind.class, Creationdate.class, Deny.class, Displayname.class, Error.class, Exclusive.class,
        Getcontentlanguage.class, Getcontentlength.class, Getetag.class, Getlastmodified.class, Grant.class,
        Group.class, Inherited.class, Keepalive.class, Limit.class, Link.class, Location.class,
        Lockdiscovery.class, Lockentry.class, Lockinfo.class, Lockscope.class, Locktoken.class, Locktype.class,
        Multistatus.class, ObjectFactory.class, Omit.class, Owner.class, Principal.class,
        PrincipalCollectionSet.class, PrincipalURL.class, Privilege.class, Prop.class, Propertybehavior.class,
        Propertyupdate.class, Propfind.class, Propname.class, Propstat.class, Protected.class,
        QuotaAvailableBytes.class, QuotaUsedBytes.class, Read.class, ReadAcl.class,
        ReadCurrentUserPrivilegeSet.class, Remove.class, Report.class, Resourcetype.class, SearchRequest.class,
        Self.class, Set.class, Shared.class, SimplePrivilege.class, Source.class, Supportedlock.class,
        SupportedReport.class, SupportedReportSet.class, SyncCollection.class, Unauthenticated.class,
        UnBind.class, Unlock.class, Write.class, WriteContent.class, WriteProperties.class,
        Getcontenttype.class, BasicScheme.class, AuthScheme.class, RFC2617Scheme.class, AuthSchemeBase.class,
        ContextAwareAuthScheme.class,
        /*
         * Additional model to ensure that qute can render templates
         */
        java.sql.Timestamp.class,
        /**
         * Misc objects
         */

})

public class ReflectionHelper {

}
