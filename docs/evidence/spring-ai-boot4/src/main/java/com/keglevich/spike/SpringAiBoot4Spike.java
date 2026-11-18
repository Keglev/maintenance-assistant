package com.keglevich.spike;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Answers three questions and stops:
 * <ol>
 *   <li>Does Spring AI 2.0.0 auto-configure at all under Boot 4.1?</li>
 *   <li>Does it reach the IONOS OpenAI-compatible gateway?</li>
 *   <li>Does it survive the ADR-002 {@code encoding_format} caveat — the gateway returns 500 for
 *       the base64 encoding the OpenAI Python SDK sends by default?</li>
 * </ol>
 */
@SpringBootApplication
public class SpringAiBoot4Spike {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(SpringAiBoot4Spike.class, args)));
    }

    @Bean
    CommandLineRunner probe(EmbeddingModel embeddingModel) {
        return args -> {
            System.out.println("=== Spring AI on Boot 4.1 — IONOS bge-m3 probe ===");
            System.out.println("EmbeddingModel implementation: " + embeddingModel.getClass().getName());

            List<String> input = List.of(
                    "Presse kommt nicht auf Druck, Fehler E-47",
                    "Belt tracking off to the right, product falling off the edge");

            long start = System.nanoTime();
            EmbeddingResponse response = embeddingModel.call(new org.springframework.ai.embedding.EmbeddingRequest(input, null));
            long ms = (System.nanoTime() - start) / 1_000_000;

            System.out.println("vectors returned : " + response.getResults().size());
            System.out.println("dimensions       : " + response.getResults().getFirst().getOutput().length);
            System.out.println("latency ms       : " + ms);
            System.out.println("usage            : " + response.getMetadata().getUsage());
            System.out.println("first 5 floats   : "
                    + java.util.Arrays.toString(java.util.Arrays.copyOf(response.getResults().getFirst().getOutput(), 5)));
            System.out.println("=== probe finished without error ===");
        };
    }
}
