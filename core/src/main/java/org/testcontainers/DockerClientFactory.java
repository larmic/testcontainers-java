package org.testcontainers;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.DockerClientDelegate;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.Version;
import com.github.dockerjava.api.model.Volume;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.testcontainers.dockerclient.DockerClientProviderStrategy;
import org.testcontainers.dockerclient.DockerMachineClientProviderStrategy;
import org.testcontainers.dockerclient.TransportConfig;
import org.testcontainers.images.RemoteDockerImage;
import org.testcontainers.images.TimeLimitedLoggedPullImageResultCallback;
import org.testcontainers.utility.ComparableVersion;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.utility.ResourceReaper;
import org.testcontainers.utility.RyukResourceReaper;
import org.testcontainers.utility.TestcontainersConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Singleton class that provides initialized Docker clients.
 * <p>
 * The correct client configuration to use will be determined on first use, and cached thereafter.
 */
@Slf4j
public class DockerClientFactory {

    public static final ThreadGroup TESTCONTAINERS_THREAD_GROUP = new ThreadGroup("testcontainers");
    public static final String TESTCONTAINERS_LABEL = DockerClientFactory.class.getPackage().getName();
    public static final String TESTCONTAINERS_SESSION_ID_LABEL = TESTCONTAINERS_LABEL + ".sessionId";

    public static final String SESSION_ID = UUID.randomUUID().toString();

    public static final Map<String, String> DEFAULT_LABELS = ImmutableMap.of(
            TESTCONTAINERS_LABEL, "true"
    );

    private static final DockerImageName TINY_IMAGE = DockerImageName.parse("alpine:3.14");
    private static DockerClientFactory instance;

    // Cached client configuration
    @VisibleForTesting
    DockerClientProviderStrategy strategy;

    @VisibleForTesting
    DockerClient client;

    @VisibleForTesting
    RuntimeException cachedClientFailure;

    private String activeApiVersion;
    private String activeExecutionDriver;

    @Getter(lazy = true)
    private final boolean fileMountingSupported = checkMountableFile();


    static {
        System.setProperty("org.testcontainers.shaded.io.netty.packagePrefix", "org.testcontainers.shaded.");
    }

    @VisibleForTesting
    DockerClientFactory() {

    }

    public static DockerClient lazyClient() {
        return new DockerClientDelegate() {
            @Override
            protected DockerClient getDockerClient() {
                return instance().client();
            }

            @Override
            public String toString() {
                return "LazyDockerClient";
            }
        };
    }

    /**
     * Obtain an instance of the DockerClientFactory.
     *
     * @return the singleton instance of DockerClientFactory
     */
    public synchronized static DockerClientFactory instance() {
        if (instance == null) {
            instance = new DockerClientFactory();
        }

        return instance;
    }

    /**
     * Checks whether Docker is accessible and {@link #client()} is able to produce a client.
     * @return true if Docker is available, false if not.
     */
    public synchronized boolean isDockerAvailable() {
        try {
            client();
            return true;
        } catch (IllegalStateException ex) {
            return false;
        }
    }

    @Synchronized
    private DockerClientProviderStrategy getOrInitializeStrategy() {
        if (strategy != null) {
            return strategy;
        }

        List<DockerClientProviderStrategy> configurationStrategies = new ArrayList<>();
        ServiceLoader.load(DockerClientProviderStrategy.class).forEach(configurationStrategies::add);

        strategy = DockerClientProviderStrategy.getFirstValidStrategy(configurationStrategies);
        return strategy;
    }

    @UnstableAPI
    public TransportConfig getTransportConfig() {
        return getOrInitializeStrategy().getTransportConfig();
    }

    @UnstableAPI
    public String getRemoteDockerUnixSocketPath() {
        String dockerSocketOverride = System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE");
        if (!StringUtils.isBlank(dockerSocketOverride)) {
            return dockerSocketOverride;
        }

        URI dockerHost = getTransportConfig().getDockerHost();
        String path = "unix".equals(dockerHost.getScheme())
            ? dockerHost.getRawPath()
            : "/var/run/docker.sock";
        return SystemUtils.IS_OS_WINDOWS
               ? "/" + path
               : path;
    }

    /**
     *
     * @return a new initialized Docker client
     */
    @Synchronized
    public DockerClient client() {
        // fail-fast if checks have failed previously
        if (cachedClientFailure != null) {
            log.debug("There is a cached checks failure - throwing", cachedClientFailure);
            throw cachedClientFailure;
        }

        if (client != null) {
            return client;
        }

        final DockerClientProviderStrategy strategy = getOrInitializeStrategy();

        log.info("Docker host IP address is {}", strategy.getDockerHostIpAddress());
        client = new DockerClientDelegate() {

            @Getter
            final DockerClient dockerClient = strategy.getDockerClient();

            @Override
            public void close() {
                throw new IllegalStateException("You should never close the global DockerClient!");
            }
        };

        Info dockerInfo = client.infoCmd().exec();
        Version version = client.versionCmd().exec();
        activeApiVersion = version.getApiVersion();
        activeExecutionDriver = dockerInfo.getExecutionDriver();
        log.info("Connected to docker: \n" +
                "  Server Version: " + dockerInfo.getServerVersion() + "\n" +
                "  API Version: " + activeApiVersion + "\n" +
                "  Operating System: " + dockerInfo.getOperatingSystem() + "\n" +
                "  Total Memory: " + dockerInfo.getMemTotal() / (1024 * 1024) + " MB");

        final ResourceReaper resourceReaper;
        try {
            resourceReaper = ResourceReaper.instance();
            //noinspection deprecation
            resourceReaper.init();
        } catch (RuntimeException e) {
            cachedClientFailure = e;
            throw e;
        }

        boolean checksEnabled = !TestcontainersConfiguration.getInstance().isDisableChecks();
        if (checksEnabled) {
            log.debug("Checks are enabled");

            try {
                log.info("Checking the system...");
                checkDockerVersion(version.getVersion());

                //noinspection deprecation
                String ryukContainerId = resourceReaper instanceof RyukResourceReaper
                    ? ((RyukResourceReaper) resourceReaper).getContainerId()
                    : null;

                if (ryukContainerId != null) {
                    checkDiskSpace(client, ryukContainerId);
                } else {
                    runInsideDocker(
                        createContainerCmd -> {
                            createContainerCmd.withName("testcontainers-checks-" + SESSION_ID);
                            createContainerCmd.getHostConfig().withAutoRemove(true);
                            createContainerCmd.withCmd("tail", "-f", "/dev/null");
                        },
                        (__, containerId) -> {
                            checkDiskSpace(client, containerId);
                            return "";
                        }
                    );
                }
            } catch (RuntimeException e) {
                cachedClientFailure = e;
                throw e;
            }
        } else {
            log.debug("Checks are disabled");
        }

        return client;
    }

    private void checkDockerVersion(String dockerVersion) {
        boolean versionIsSufficient = new ComparableVersion(dockerVersion).compareTo(new ComparableVersion("1.6.0")) >= 0;
        check("Docker server version should be at least 1.6.0", versionIsSufficient);
    }

    private void checkDiskSpace(DockerClient dockerClient, String id) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try {
            dockerClient
                    .execStartCmd(dockerClient.execCreateCmd(id).withAttachStdout(true).withCmd("df", "-P").exec().getId())
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            if (frame == null) {
                                return;
                            }
                            switch (frame.getStreamType()) {
                                case RAW:
                                case STDOUT:
                                    try {
                                        outputStream.write(frame.getPayload());
                                        outputStream.flush();
                                    } catch (IOException e) {
                                        onError(e);
                                    }
                            }
                        }
                    })
                    .awaitCompletion();
        } catch (Exception e) {
            log.debug("Can't exec disk checking command", e);
        }

        DiskSpaceUsage df = parseAvailableDiskSpace(outputStream.toString());

        check(
                "Docker environment should have more than 2GB free disk space",
                df.availableMB.map(it -> it >= 2048).orElse(true)
        );
    }

    private void check(String message, boolean isSuccessful) {
        if (isSuccessful) {
            log.info("\u2714\ufe0e {}", message);
        } else {
            log.error("\u274c {}", message);
            throw new IllegalStateException("Check failed: " + message);
        }
    }

    private boolean checkMountableFile() {
        DockerClient dockerClient = client();

        MountableFile mountableFile = MountableFile.forClasspathResource(ResourceReaper.class.getName().replace(".", "/") + ".class");

        Volume volume = new Volume("/dummy");
        try {
            return runInsideDocker(
                createContainerCmd -> createContainerCmd.withBinds(new Bind(mountableFile.getResolvedPath(), volume, AccessMode.ro)),
                (__, containerId) -> {
                    try (InputStream stream = dockerClient.copyArchiveFromContainerCmd(containerId, volume.getPath()).exec()) {
                        stream.read();
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                }
            );
        } catch (Exception e) {
            log.debug("Failure while checking for mountable file support", e);
            return false;
        }
    }

    /**
     * Check whether the image is available locally and pull it otherwise
     *
     * @deprecated use {@link RemoteDockerImage}
     */
    @SneakyThrows
    @Deprecated
    public void checkAndPullImage(DockerClient client, String image) {
        try {
            client.inspectImageCmd(image).exec();
        } catch (NotFoundException notFoundException) {
            PullImageCmd pullImageCmd = client.pullImageCmd(image);
            try {
                pullImageCmd.exec(new TimeLimitedLoggedPullImageResultCallback(log)).awaitCompletion();
            } catch (DockerClientException e) {
                // Try to fallback to x86
                pullImageCmd
                    .withPlatform("linux/amd64")
                    .exec(new TimeLimitedLoggedPullImageResultCallback(log))
                    .awaitCompletion();
            }
        }
    }

    /**
     * @return the IP address of the host running Docker
     */
    public String dockerHostIpAddress() {
        return getOrInitializeStrategy().getDockerHostIpAddress();
    }

    public <T> T runInsideDocker(Consumer<CreateContainerCmd> createContainerCmdConsumer, BiFunction<DockerClient, String, T> block) {
        return runInsideDocker(TINY_IMAGE, createContainerCmdConsumer, block);
    }

    <T> T runInsideDocker(DockerImageName imageName, Consumer<CreateContainerCmd> createContainerCmdConsumer, BiFunction<DockerClient, String, T> block) {
        RemoteDockerImage dockerImage = new RemoteDockerImage(imageName);
        HashMap<String, String> labels = new HashMap<>(DEFAULT_LABELS);
        labels.putAll(ResourceReaper.instance().getLabels());
        CreateContainerCmd createContainerCmd = client.createContainerCmd(dockerImage.get())
                .withLabels(labels);
        createContainerCmdConsumer.accept(createContainerCmd);
        String id = createContainerCmd.exec().getId();

        try {
            client.startContainerCmd(id).exec();
            return block.apply(client, id);
        } finally {
            try {
                client.removeContainerCmd(id).withRemoveVolumes(true).withForce(true).exec();
            } catch (NotFoundException | InternalServerErrorException e) {
                log.debug("Swallowed exception while removing container", e);
            }
        }
    }

    @VisibleForTesting
    static class DiskSpaceUsage {
        Optional<Long> availableMB = Optional.empty();
        Optional<Integer> usedPercent = Optional.empty();
    }

    @VisibleForTesting
    DiskSpaceUsage parseAvailableDiskSpace(String dfOutput) {
        DiskSpaceUsage df = new DiskSpaceUsage();
        String[] lines = dfOutput.split("\n");
        for (String line : lines) {
            String[] fields = line.split("\\s+");
            if (fields.length > 5 && fields[5].equals("/")) {
                long availableKB = Long.parseLong(fields[3]);
                df.availableMB = Optional.of(availableKB / 1024L);
                df.usedPercent = Optional.of(Integer.valueOf(fields[4].replace("%", "")));
                break;
            }
        }
        return df;
    }

    /**
     * @return the docker API version of the daemon that we have connected to
     */
    public String getActiveApiVersion() {
        client();
        return activeApiVersion;
    }

    /**
     * @return the docker execution driver of the daemon that we have connected to
     */
    public String getActiveExecutionDriver() {
        client();
        return activeExecutionDriver;
    }

    /**
     * @param providerStrategyClass a class that extends {@link DockerMachineClientProviderStrategy}
     * @return whether or not the currently active strategy is of the provided type
     */
    public boolean isUsing(Class<? extends DockerClientProviderStrategy> providerStrategyClass) {
        return strategy != null && providerStrategyClass.isAssignableFrom(this.strategy.getClass());
    }
}
