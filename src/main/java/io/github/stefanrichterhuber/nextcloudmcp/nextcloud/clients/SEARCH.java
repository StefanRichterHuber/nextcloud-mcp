package io.github.stefanrichterhuber.nextcloudmcp.nextcloud.clients;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.ws.rs.HttpMethod;

/**
 * Additional HTTP method for WebDav search queries
 */
@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@HttpMethod("SEARCH")
@Documented
public @interface SEARCH {

}
