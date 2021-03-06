/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.plugin.server;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.plugin.common.DeploymentFailureException;
import org.jboss.as.plugin.common.Files;
import org.jboss.as.plugin.common.PropertyNames;
import org.jboss.as.plugin.common.ServerOperations;
import org.jboss.as.plugin.deployment.Deploy;
import org.jboss.as.plugin.deployment.Deployment;
import org.jboss.as.plugin.deployment.standalone.StandaloneDeployment;

/**
 * Starts a standalone instance of JBoss Application Server 7 and deploys the application to the server.
 * <p/>
 * This goal will block until cancelled or a shutdown is invoked from a management client.
 *
 * @author Stuart Douglas
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@Mojo(name = "run", requiresDependencyResolution = ResolutionScope.RUNTIME)
@Execute(phase = LifecyclePhase.PACKAGE)
public class Run extends Deploy {

    public static final String JBOSS_DIR = "jboss-as-run";

    @Component
    private ArtifactResolver artifactResolver;

    /**
     * The JBoss Application Server's home directory. If not used, JBoss Application Server will be downloaded.
     */
    @Parameter(alias = "jboss-home", property = PropertyNames.JBOSS_HOME)
    private String jbossHome;

    /**
     * A string of the form groupId:artifactId:version[:packaging][:classifier]. Any missing portion of the artifact
     * will be replaced with the it's appropriate default property value
     */
    @Parameter(property = PropertyNames.JBOSS_ARTIFACT)
    private String artifact;

    /**
     * The {@code groupId} of the artifact to download. Ignored if {@link #artifact} {@code groupId} portion is used.
     */
    @Parameter(defaultValue = Defaults.JBOSS_AS_GROUP_ID, property = PropertyNames.JBOSS_GROUP_ID)
    private String groupId;

    /**
     * The {@code artifactId} of the artifact to download. Ignored if {@link #artifact} {@code artifactId} portion is
     * used.
     */
    @Parameter(defaultValue = Defaults.JBOSS_AS_ARTIFACT_ID, property = PropertyNames.JBOSS_ARTIFACT_ID)
    private String artifactId;

    /**
     * The {@code classifier} of the artifact to download. Ignored if {@link #artifact} {@code classifier} portion is
     * used.
     */
    @Parameter(property = PropertyNames.JBOSS_CLASSIFIER)
    private String classifier;

    /**
     * The {@code packaging} of the artifact to download. Ignored if {@link #artifact} {@code packing} portion is used.
     */
    @Parameter(property = PropertyNames.JBOSS_PACKAGING, defaultValue = Defaults.JBOSS_AS_PACKAGING)
    private String packaging;

    /**
     * The {@code version} of the artifact to download. Ignored if {@link #artifact} {@code version} portion is used.
     */
    @Parameter(alias = "jboss-as-version", defaultValue = Defaults.JBOSS_AS_TARGET_VERSION, property = PropertyNames.JBOSS_VERSION)
    private String version;

    /**
     * The modules path or paths to use. A single path can be used or multiple paths by enclosing them in a paths
     * element.
     */
    @Parameter(alias = "modules-path", property = PropertyNames.MODULES_PATH)
    private ModulesPath modulesPath;

    /**
     * The bundles path to use.
     */
    @Parameter(alias = "bundles-path", property = PropertyNames.BUNDLES_PATH)
    private String bundlesPath;

    /**
     * A space delimited list of JVM arguments.
     */
    @Parameter(alias = "jvm-args", property = PropertyNames.JVM_ARGS, defaultValue = Defaults.DEFAULT_JVM_ARGS)
    private String jvmArgs;

    /**
     * The {@code JAVA_HOME} to use for launching the server.
     */
    @Parameter(alias = "java-home", property = PropertyNames.JAVA_HOME)
    private String javaHome;

    /**
     * The path to the server configuration to use.
     */
    @Parameter(alias = "server-config", property = PropertyNames.SERVER_CONFIG)
    private String serverConfig;

    /**
     * The path to the system properties file to load.
     */
    @Parameter(alias = "properties-file", property = PropertyNames.PROPERTIES_FILE)
    private String propertiesFile;

    /**
     * The timeout value to use when starting the server.
     */
    @Parameter(alias = "startup-timeout", defaultValue = Defaults.TIMEOUT, property = PropertyNames.STARTUP_TIMEOUT)
    private long startupTimeout;

    @Override
    protected void doExecute() throws MojoExecutionException, MojoFailureException {
        final Log log = getLog();
        final File deploymentFile = file();
        final String deploymentName = deploymentFile.getName();
        final File targetDir = deploymentFile.getParentFile();
        // The deployment must exist before we do anything
        if (!deploymentFile.exists()) {
            throw new MojoExecutionException(String.format("The deployment '%s' could not be found.", deploymentFile.getAbsolutePath()));
        }
        // Validate the environment
        final File jbossHome = extractIfRequired(targetDir);
        if (!jbossHome.isDirectory()) {
            throw new MojoExecutionException(String.format("JBOSS_HOME '%s' is not a valid directory.", jbossHome));
        }
        // JVM arguments should be space delimited
        final String[] jvmArgs = (this.jvmArgs == null ? null : this.jvmArgs.split("\\s+"));
        final String javaHome;
        if (this.javaHome == null) {
            javaHome = SecurityActions.getEnvironmentVariable("JAVA_HOME");
        } else {
            javaHome = this.javaHome;
        }
        final List<String> invalidPaths = modulesPath.validate();
        if (!invalidPaths.isEmpty()) {
            throw new MojoExecutionException("Invalid module path(s). " + invalidPaths);
        }
        final ServerInfo serverInfo = ServerInfo.of(this, javaHome, jbossHome, modulesPath.get(), bundlesPath, jvmArgs, serverConfig, propertiesFile, startupTimeout);

        // Print some server information
        log.info(String.format("JAVA_HOME=%s", javaHome));
        log.info(String.format("JBOSS_HOME=%s%n", jbossHome));
        try {
            // Create the server
            final Server server = new StandaloneServer(serverInfo);
            // Add the shutdown hook
            SecurityActions.registerShutdown(server);
            // Start the server
            log.info("Server is starting up. Press CTRL + C to stop the server.");
            server.start();
            // Deploy the application
            server.checkServerState();
            if (server.isRunning()) {
                log.info(String.format("Deploying application '%s'%n", deploymentFile.getName()));
                final ModelControllerClient client = server.getClient();
                final Deployment deployment = StandaloneDeployment.create(client, deploymentFile, deploymentName, getType(), null, null);
                switch (executeDeployment(client, deployment)) {
                    case REQUIRES_RESTART: {
                        client.execute(ServerOperations.createOperation(ServerOperations.RELOAD));
                        break;
                    }
                    case SUCCESS:
                        break;
                }
            } else {
                throw new DeploymentFailureException("Cannot deploy to a server that is not running.");
            }
            while (server.isRunning()) {
                TimeUnit.SECONDS.sleep(1L);
            }
            server.stop();
        } catch (Exception e) {
            throw new MojoExecutionException("The server failed to start", e);
        }

    }

    private File extractIfRequired(final File buildDir) throws MojoFailureException, MojoExecutionException {
        if (jbossHome != null) {
            //we do not need to download JBoss
            return new File(jbossHome);
        }
        final File result = artifactResolver.resolve(project, createArtifact());
        final File target = new File(buildDir, JBOSS_DIR);
        // Delete the target if it exists
        if (target.exists()) {
            Files.deleteRecursively(target);
        }
        target.mkdirs();
        try {
            Files.unzip(result, target);
        } catch (IOException e) {
            throw new MojoFailureException("Artifact was not successfully extracted: " + result, e);
        }
        final File[] files = target.listFiles();
        if (files == null || files.length != 1) {
            throw new MojoFailureException("Artifact was not successfully extracted: " + result);
        }
        // Assume the first
        return files[0];
    }

    @Override
    public String goal() {
        return "run";
    }

    private String createArtifact() throws MojoFailureException {
        String groupId = this.groupId;
        String artifactId = this.artifactId;
        String classifier = this.classifier;
        String packaging = this.packaging;
        String version = this.version;
        // groupId:artifactId:version[:packaging][:classifier].
        if (artifact != null) {
            final String[] artifactParts = artifact.split(":");
            if (artifactParts.length == 0) {
                throw new MojoFailureException(String.format("Invalid artifact pattern: %s", artifact));
            }
            String value;
            switch (artifactParts.length) {
                case 5:
                    value = artifactParts[4].trim();
                    if (!value.isEmpty()) {
                        classifier = value;
                    }
                case 4:
                    value = artifactParts[3].trim();
                    if (!value.isEmpty()) {
                        packaging = value;
                    }
                case 3:
                    value = artifactParts[2].trim();
                    if (!value.isEmpty()) {
                        version = value;
                    }
                case 2:
                    value = artifactParts[1].trim();
                    if (!value.isEmpty()) {
                        artifactId = value;
                    }
                case 1:
                    value = artifactParts[0].trim();
                    if (!value.isEmpty()) {
                        groupId = value;
                    }
            }
        }
        // Validate the groupId, artifactId and version are not null
        if (groupId == null || artifactId == null || version == null) {
            throw new IllegalStateException("The groupId, artifactId and version parameters are required");
        }
        final StringBuilder result = new StringBuilder();
        result.append(groupId)
                .append(':')
                .append(artifactId)
                .append(':')
                .append(version)
                .append(':');
        if (packaging != null) {
            result.append(packaging);
        }
        result.append(':');
        if (classifier != null) {
            result.append(classifier);
        }
        return result.toString();
    }
}
