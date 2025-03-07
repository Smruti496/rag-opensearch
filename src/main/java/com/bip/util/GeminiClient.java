package com.bip.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import java.util.List;
import java.util.Map;

public class GeminiClient {
    private final String geminiApiUrl;
    private final String geminiApiKey;
    private final RestTemplate restTemplate;

    public GeminiClient(String geminiApiUrl, String geminiApiKey, RestTemplate restTemplate) {
        this.geminiApiUrl = geminiApiUrl;
        this.geminiApiKey = geminiApiKey;
        this.restTemplate = restTemplate;
    }

    public String generateResponse(String prompt) {
        try {
            // Create JSON request body
            ObjectMapper objectMapper = new ObjectMapper();
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt))))
            ));

            // Set Headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Build request
            HttpEntity<String> requestEntity = new HttpEntity<>(requestBody, headers);
            String url = geminiApiUrl + "?key=" + geminiApiKey;

            // Send POST request
            ResponseEntity<String> response = restTemplate.postForEntity(url, requestEntity, String.class);

            // Parse response using Jackson
            JsonNode jsonResponse = objectMapper.readTree(response.getBody());

            // Extract the generated response correctly
            JsonNode candidatesNode = jsonResponse.path("candidates");
            if (candidatesNode.isArray() && candidatesNode.size() > 0) {
                JsonNode contentNode = candidatesNode.get(0).path("content").path("parts");
                if (contentNode.isArray() && contentNode.size() > 0) {
                    return contentNode.get(0).path("text").asText();
                }
            }

            return "Error: No valid response from Gemini AI.";
        } catch (Exception e) {
            return "Error processing Gemini AI response: " + e.getMessage();
        }
    }
}