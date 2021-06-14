package io.xstefank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.ScheduledExecution;
import io.xstefank.model.Project;
import io.xstefank.model.Projects;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.json.JsonObject;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;

@ApplicationScoped
public class ProjectWatcher {

    public static final int SECONDS_HOUR = 3600;
    public static final String UPSTREAM = "upstream";
    public static final String ORIGIN = "origin";

    private Logger logger = Logger.getLogger(ProjectWatcher.class);
    private Projects projects;

    @ConfigProperty(name = "repo.list")
    String repoList;

    @ConfigProperty(name = "github.token")
    String githubToken;

    @ConfigProperty(name = "github.username")
    String githubUsername;

    @PostConstruct
    public void init() throws IOException {
        loadConfig(repoList);
    }

    public void loadConfig(String repoList) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());

        try {
            URL repoListURL = new URL(repoList);
            projects = objectMapper.readValue(repoListURL, Projects.class);
        } catch (MalformedURLException e) {
            File repoListFile = new File(repoList);
            projects = objectMapper.readValue(repoListFile, Projects.class);
        }
    }

    @Scheduled(every = "1h")
    public void processComponents(ScheduledExecution scheduledExecution) {
        final Client client = ClientBuilder.newClient();
        projects.projects.forEach(project ->
            processComponent(project, client, scheduledExecution.getFireTime()));

        client.close();
        logger.info("--------- Check for " + scheduledExecution.getFireTime() + " finished");
    }

    private void processComponent(Project project, Client client, Instant fireTime) {
        final String url = String.format("https://api.github.com/repos/%s/commits/%s", project.upstream, project.branch);
        logger.info("Processing URL " + url);

        final Response response = client.target(url)
            .request()
            .header("Authorization", "token " + githubToken)
            .get();
        final JsonObject jsonObject = response.readEntity(JsonObject.class);

        final String commitTimestamp = jsonObject.getJsonObject("commit").getJsonObject("committer").getJsonString("date").getString();
        final Instant commitInstant = Instant.parse(commitTimestamp);
        final long secondsFromLastCommit = Duration.between(commitInstant, fireTime).getSeconds();

        if (secondsFromLastCommit < SECONDS_HOUR) {
            updateDownstream(project);
        } else {
            logger.info("No push in the last interval for " + project.upstream);
        }
    }

    private void updateDownstream(Project project) {
        File tmpGit;
        try {
            tmpGit = Files.createTempDirectory("tmpgit").toFile();
        } catch (IOException e) {
            logger.error("Cannot create tmp directory", e);
            return;
        }

        logger.info("Cloning to " + tmpGit.getAbsolutePath());

        try (Git git = Git.cloneRepository()
            .setDirectory(tmpGit)
            .setURI(String.format("https://github.com/%s.git", project.downstream))
            .setBranch(project.branch)
            .call()) {

            git.remoteAdd()
                .setName("upstream")
                .setUri(new URIish(String.format("https://github.com/%s.git", project.upstream)))
                .call();

            git.pull()
                .setRemote(UPSTREAM)
                .setRemoteBranchName(project.branch)
                .setRebase(true)
                .call();

            git.push()
                .setRemote(ORIGIN)
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(githubUsername, githubToken))
                .setForce(true)
                .call();

        } catch (GitAPIException | URISyntaxException e) {
            logger.error(e);
        } finally {
            try {
                FileUtils.deleteDirectory(tmpGit);
            } catch (IOException e) {
                logger.error("Cannot delete tmp directory", e);
            }
        }
    }

    public void reloadConfig(String newConfig) throws IOException {
        if (newConfig != null) {
            loadConfig(newConfig);
        } else {
            loadConfig(repoList);
        }
    }
}
