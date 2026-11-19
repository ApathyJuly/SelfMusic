package com.selfmusic.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Media media = new Media();
    private Storage storage = new Storage();
    private Cors cors = new Cors();

    @Data
    public static class Jwt {
        private String secret = "";
        private Duration accessTokenTtl = Duration.ofMinutes(30);
        private Duration refreshTokenTtl = Duration.ofDays(30);
    }

    @Data
    public static class Media {
        private String tokenSecret = "";
        private Duration tokenTtl = Duration.ofHours(6);
        private Duration coverTokenTtl = Duration.ofDays(7);
    }

    @Data
    public static class Storage {
        private String type = "s3";
        private S3 s3 = new S3();
        private Local local = new Local();
    }

    @Data
    public static class S3 {
        private String endpoint = "";
        private String region = "us-east-1";
        private String accessKey = "";
        private String secretKey = "";
        private String bucket = "selfmusic";
        private boolean pathStyle = true;
        private boolean createBucket = true;
    }

    @Data
    public static class Local {
        private String root = "./data/uploads";
    }

    @Data
    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>(List.of("*"));
    }
}
