package io.xstefank.rest;

import io.xstefank.ProjectWatcher;
import org.jboss.logging.Logger;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import java.io.IOException;

@Path("/watcher")
public class ProjectWatcherResource {

    private static final Logger logger = Logger.getLogger(ProjectWatcherResource.class);

    @Inject
    ProjectWatcher projectWatcher;

    @GET
    @Path("/reload-config")
    public Response reloadConfig(@QueryParam("repoList") String newConfig) {
        try {
            projectWatcher.reloadConfig(newConfig);
        } catch (IOException e) {
            logger.error("Config cannot be reloaded", e);
            return Response.status(500).entity("Config couldn't be reloaded " + e).build();
        }

        return Response.ok().entity("Config reloaded").build();
    }
}
