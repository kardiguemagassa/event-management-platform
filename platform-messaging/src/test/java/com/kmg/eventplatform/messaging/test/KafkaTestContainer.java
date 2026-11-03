package com.kmg.eventplatform.messaging.test;

import com.github.dockerjava.api.command.InspectContainerResponse;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;

/**
 * Kafka for the integration tests of the services, same version as in compose.
 * <p>
 * Works around a race of Testcontainers 2.0.5: the container runs its start script as soon as the file appears, while
 * Docker may still be writing it ("text file busy", exit code 126, seen on a loaded machine). The script is run only
 * once a marker file is written after it. Remove this class when Testcontainers ships the fix
 * (testcontainers-java#11957) and use {@link KafkaContainer} directly.
 */
public class KafkaTestContainer extends KafkaContainer {

    public static final DockerImageName IMAGE = DockerImageName.parse("apache/kafka:4.3.2");

    private static final String STARTER_SCRIPT = "/tmp/testcontainers_start.sh";
    private static final String STARTER_SCRIPT_WRITTEN = STARTER_SCRIPT + ".ready";

    public KafkaTestContainer() {
        super(IMAGE);
        withCommand("sh", "-c",
                "while [ ! -f " + STARTER_SCRIPT_WRITTEN + " ]; do sleep 0.1; done; " + STARTER_SCRIPT);
    }

    @Override
    protected void containerIsStarting(InspectContainerResponse containerInfo) {
        super.containerIsStarting(containerInfo);
        try {
            execInContainer("touch", STARTER_SCRIPT_WRITTEN);
        } catch (IOException failure) {
            throw new IllegalStateException("Kafka start script not released", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting Kafka", interrupted);
        }
    }
}
