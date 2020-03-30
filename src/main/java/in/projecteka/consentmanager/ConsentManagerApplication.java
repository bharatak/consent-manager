package in.projecteka.consentmanager;

import in.projecteka.consentmanager.clients.properties.ClientRegistryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(ClientRegistryProperties.class)
@EnableScheduling
@EnableAsync
public class ConsentManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsentManagerApplication.class, args);
    }
}
