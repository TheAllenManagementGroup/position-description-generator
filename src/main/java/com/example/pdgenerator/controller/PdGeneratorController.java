package com.example.pdgenerator.controller;

import com.example.pdgenerator.request.PdRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.example.pdgenerator.service.PdService;
import com.example.pdgenerator.jobseries.JobSeriesService;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.LinkedHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * REST controller responsible for handling requests related to
 * position description generation using OpenAI API.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class PdGeneratorController {

    private final PdService pdService;
    private final JobSeriesService jobSeriesService;

    /**
     * Constructor injection for the services.
     */
    public PdGeneratorController(PdService pdService, JobSeriesService jobSeriesService) throws Exception {
        this.pdService = pdService;
        this.jobSeriesService = jobSeriesService;
        // Fetch and process job series data at startup
        this.jobSeriesService.fetchAndProcessJobSeries();
    }

    /**
     * Inner class representing the JSON structure of the request
     * sent to the OpenAI API backend.
     */
    class OpenAIRequest {
        private String model;
        private List<Message> messages;
        private boolean stream;
        private int max_tokens; // <-- update to match OpenAI API
        private double temperature;

        public OpenAIRequest(String model, List<Message> messages, boolean stream) {
            this.model = model;
            this.messages = messages;
            this.stream = stream;
            this.max_tokens = 4000; // <-- update field name
            this.temperature = 0.3;
        }

        // Getters and setters
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public List<Message> getMessages() { return messages; }
        public void setMessages(List<Message> messages) { this.messages = messages; }
        public boolean isStream() { return stream; }
        public void setStream(boolean stream) { this.stream = stream; }
        public int getMax_tokens() { return max_tokens; } // <-- update getter name
        public void setMax_tokens(int max_tokens) { this.max_tokens = max_tokens; } // <-- update setter name
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
    }

    class Message {
        private String role;
        private String content;
        
        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
        
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    /**
     * POST endpoint to generate a position description (PD) based on
     * input parameters. Enhanced error handling and debugging.
     */
    @PostMapping("/generate")
    public void generatePd(@RequestBody PdRequest request, HttpServletResponse response) throws Exception {
        System.out.println("=== PD GENERATION STARTED ===");
        
        // Set response headers for streaming
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("Access-Control-Allow-Origin", "*");

        PrintWriter writer = response.getWriter();

        try {
            // Enhanced request validation
            if (request == null) {
                System.err.println("ERROR: Request object is null");
                writer.println("data: {\"error\":\"Request is missing\"}\n");
                writer.flush();
                return;
            }

            // Log request details
            System.out.println("Job Series: " + request.getJobSeries());
            System.out.println("Sub Job Series: " + request.getSubJobSeries());
            System.out.println("Federal Agency: " + request.getFederalAgency());
            System.out.println("GS Grade: " + request.getGsGrade());
            System.out.println("Historical Data: " + (request.getHistoricalData() != null ? 
                request.getHistoricalData().length() + " characters" : "null"));

            // Validate historical data
            if (request.getHistoricalData() == null || request.getHistoricalData().trim().isEmpty()) {
                System.err.println("ERROR: Historical data is missing or empty");
                writer.println("data: {\"error\":\"Job duties are required to generate a position description\"}\n");
                writer.flush();
                return;
            }

            // Validate API key
            String apiKey = pdService.getOpenaiApiKey();
            if (apiKey == null || apiKey.trim().isEmpty() || !apiKey.startsWith("sk-")) {
                System.err.println("ERROR: Invalid or missing OpenAI API key");
                writer.println("data: {\"error\":\"OpenAI API configuration is invalid\"}\n");
                writer.flush();
                return;
            }

            System.out.println("Validation passed, generating position description...");

            // Test OpenAI connection first
            try {
                boolean connectionOk = testOpenAIConnection();
                if (!connectionOk) {
                    System.err.println("ERROR: OpenAI connection test failed");
                    writer.println("data: {\"error\":\"Cannot connect to OpenAI API. Please check your API key and internet connection.\"}\n");
                    writer.flush();
                    return;
                }
                System.out.println("OpenAI connection test passed");
            } catch (Exception e) {
                System.err.println("ERROR: OpenAI connection test exception: " + e.getMessage());
                writer.println("data: {\"error\":\"OpenAI API connection failed: " + escapeJson(e.getMessage()) + "\"}\n");
                writer.flush();
                return;
            }

            // Build the AI prompt
            String prompt;
            try {
                prompt = pdService.buildPrompt(request);
                System.out.println("Prompt built successfully. Length: " + prompt.length());
            } catch (Exception e) {
                System.err.println("Error building prompt: " + e.getMessage());
                writer.println("data: {\"error\":\"Error preparing the request: " + escapeJson(e.getMessage()) + "\"}\n");
                writer.flush();
                return;
            }

            // Send status update
            writer.println("data: {\"status\":\"Generating position description...\"}\n");
            writer.flush();

            // Prepare OpenAI API request with streaming
            List<Message> messages = new ArrayList<>();
            messages.add(new Message("system", 
                "You are an expert federal HR classification specialist. " +
                "Create complete, professional position descriptions using real content, never placeholders. " +
                "Always use specific values, actual duties, and concrete point assignments. " +
                "Never use brackets, XXX, or template text."
            ));
            messages.add(new Message("user", prompt));

            OpenAIRequest openaiRequest = new OpenAIRequest("gpt-4", messages, true);
            
            ObjectMapper objectMapper = new ObjectMapper();
            String requestBody = objectMapper.writeValueAsString(openaiRequest);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
                
            HttpResponse<InputStream> openaiResponse = client.send(httpRequest, 
                HttpResponse.BodyHandlers.ofInputStream());

            System.out.println("OpenAI API response status: " + openaiResponse.statusCode());

            if (openaiResponse.statusCode() != 200) {
                // Read error response
                String errorResponse = "";
                try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(openaiResponse.body()))) {
                    errorResponse = errorReader.lines().collect(Collectors.joining("\n"));
                }
                
                System.err.println("OpenAI API Error: " + errorResponse);
                writer.println("data: {\"error\":\"OpenAI API Error (Status " + openaiResponse.statusCode() + 
                            "): " + escapeJson(errorResponse) + "\"}\n");
                writer.flush();
                return;
            }

            // Stream response to client
            StringBuilder fullText = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(openaiResponse.body()))) {
                String line;
                boolean contentSent = false;

                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6);
                        if (!"[DONE]".equals(data.trim()) && !data.trim().isEmpty()) {
                            try {
                                JsonNode jsonNode = objectMapper.readTree(data);
                                if (jsonNode.has("choices") && jsonNode.get("choices").size() > 0) {
                                    JsonNode choice = jsonNode.get("choices").get(0);
                                    if (choice.has("delta") && choice.get("delta").has("content")) {
                                        String content = choice.get("delta").get("content").asText();
                                        if (!content.trim().isEmpty()) {
                                            contentSent = true;
                                            writer.println("data: {\"response\":\"" + escapeJson(content) + "\"}\n");
                                            writer.flush();
                                        }
                                        // Append content to full text
                                        fullText.append(content);
                                    }
                                }
                            } catch (Exception e) {
                                System.err.println("Error parsing streaming response: " + e.getMessage());
                                // Continue processing other chunks
                            }
                        }
                    }
                }

                // At the end, if no content was sent, send an error
                if (!contentSent) {
                    writer.println("data: {\"error\":\"No content generated by OpenAI. Please try again.\"}\n");
                    writer.flush();
                }
                writer.println("data: [DONE]\n");
            }
            
            // After assembling the full PD text from the stream:
            String formattedPD = fixPDFormatting(fullText.toString());

            // Send the fully formatted PD as a final event
            writer.println("{\"fullPD\": \"" + escapeJson(formattedPD) + "\"}");
            writer.flush();
            System.out.println("Position description generation completed successfully");
    } catch (Exception e) {
        System.err.println("EXCEPTION in generatePd: " + e.getMessage());
        e.printStackTrace();
        writer.println("data: {\"error\":\"Generation failed: " + escapeJson(e.getMessage()) + "\"}\n");
    } finally {
        try {
            writer.flush();
            writer.close();
        } catch (Exception e) {
            System.err.println("Error closing writer: " + e.getMessage());
        }
        System.out.println("=== PD GENERATION COMPLETE ===");
    }
}
    /**
     * Enhanced OpenAI connection test with better error reporting
     */
    private boolean testOpenAIConnection() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();

            // Simple test request to OpenAI
            List<Message> testMessages = new ArrayList<>();
            testMessages.add(new Message("user", "Test"));

            OpenAIRequest testRequest = new OpenAIRequest("gpt-3.5-turbo", testMessages, false);
            testRequest.setMax_tokens(5); // <-- update method name
            testRequest.setTemperature(0.1);

            ObjectMapper objectMapper = new ObjectMapper();
            String requestBody = objectMapper.writeValueAsString(testRequest);
                    
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                    .header("Authorization", "Bearer " + pdService.getOpenaiApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            System.out.println("OpenAI connection test - Status: " + response.statusCode());
            
            if (response.statusCode() == 401) {
                System.err.println("OpenAI API Key is invalid or expired");
                return false;
            } else if (response.statusCode() == 429) {
                System.err.println("OpenAI API rate limit exceeded");
                return false;
            } else if (response.statusCode() != 200) {
                System.err.println("OpenAI API returned error: " + response.body());
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            System.err.println("OpenAI connection test failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Enhanced debugging endpoint to test prompts and API connection
     */
    @PostMapping("/test-prompt")
    public ResponseEntity<Map<String, Object>> testPrompt(@RequestBody PdRequest request) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            System.out.println("=== PROMPT TEST STARTED ===");
            
            // Test API key first
            String apiKey = pdService.getOpenaiApiKey();
            result.put("hasApiKey", apiKey != null && !apiKey.trim().isEmpty());
            result.put("apiKeyValid", apiKey != null && apiKey.startsWith("sk-"));
            
            if (request == null) {
                result.put("status", "error");
                result.put("message", "Request is null");
                return ResponseEntity.badRequest().body(result);
            }
            
            // Test request validation
            result.put("hasHistoricalData", request.getHistoricalData() != null);
            result.put("historicalDataEmpty", request.getHistoricalData() == null || 
                    request.getHistoricalData().trim().isEmpty());
            
            if (request.getHistoricalData() == null || request.getHistoricalData().trim().isEmpty()) {
                result.put("status", "error");
                result.put("message", "Historical data is required");
                return ResponseEntity.badRequest().body(result);
            }
            
            // Test prompt building
            String prompt = pdService.buildPrompt(request);
            result.put("status", "success");
            result.put("promptLength", prompt.length());
            result.put("promptPreview", prompt.substring(0, Math.min(500, prompt.length())));
            
            // Check for common issues
            List<String> warnings = new ArrayList<>();
            if (prompt.contains("[") || prompt.contains("XXX") || prompt.contains("placeholder")) {
                warnings.add("Prompt may contain placeholders");
            }
            if (prompt.length() > 4000) {
                warnings.add("Prompt is very long - may be truncated");
            }
            
            result.put("warnings", warnings);
            
            // Test OpenAI connection
            try {
                boolean connectionOk = testOpenAIConnection();
                result.put("openaiConnectionOk", connectionOk);
            } catch (Exception e) {
                result.put("openaiConnectionOk", false);
                result.put("connectionError", e.getMessage());
            }
            
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
            result.put("stackTrace", java.util.Arrays.toString(e.getStackTrace()));
        }
        
        return ResponseEntity.ok(result);
    }

    /**
     * Updated POST endpoint to rewrite duties/responsibilities using OpenAI API with streaming.
     */
    @PostMapping("/rewrite-duties")
    public void rewriteDutiesStreaming(@RequestBody Map<String, String> body, HttpServletResponse response) throws Exception {
        String duties = body.getOrDefault("duties", "");
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        PrintWriter writer = response.getWriter();

        if (duties.isBlank()) {
            writer.write("data: {\"error\":\"No duties provided.\"}\n\n");
            writer.flush();
            return;
        }

        try {
            String prompt = buildOptimizedRewritePrompt(duties);

            List<Message> messages = new ArrayList<>();
            messages.add(new Message("system", "You are a federal HR specialist expert in writing professional job descriptions."));
            messages.add(new Message("user", prompt));

            OpenAIRequest openaiRequest = new OpenAIRequest("gpt-4", messages, true);
            openaiRequest.setMax_tokens(1500);

            ObjectMapper objectMapper = new ObjectMapper();
            String requestBody = objectMapper.writeValueAsString(openaiRequest);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                    .header("Authorization", "Bearer " + pdService.getOpenaiApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<InputStream> openaiResponse = client.send(httpRequest,
                HttpResponse.BodyHandlers.ofInputStream());

            if (openaiResponse.statusCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(openaiResponse.body()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6);
                            if (!"[DONE]".equals(data.trim())) {
                                try {
                                    JsonNode jsonNode = objectMapper.readTree(data);
                                    if (jsonNode.has("choices") && jsonNode.get("choices").size() > 0) {
                                        JsonNode choice = jsonNode.get("choices").get(0);
                                        if (choice.has("delta") && choice.get("delta").has("content")) {
                                            String content = choice.get("delta").get("content").asText();
                                            writer.println("data: {\"rewritten\":\"" + escapeJson(content) + "\"}\n");
                                            writer.flush();
                                        }
                                    }
                                } catch (Exception e) {
                                    // Skip invalid JSON lines
                                }
                            }
                        }
                    }
                }
                writer.println("data: [DONE]\n");
            } else {
                writer.write("data: {\"error\":\"OpenAI API returned status " + openaiResponse.statusCode() + "\"}\n\n");
            }
        } catch (Exception e) {
            writer.write("data: {\"error\":\"" + escapeJson(e.getMessage()) + "\"}\n\n");
        } finally {
            writer.flush();
            writer.close();
        }
    }

    /**
     * Alternative non-streaming version with OpenAI API
     */
    @PostMapping("/rewrite-duties-sync")
    public Map<String, String> rewriteDutiesOptimized(@RequestBody Map<String, String> body) throws Exception {
        String duties = body.getOrDefault("duties", "");
        if (duties.isBlank()) {
            return Map.of("rewritten", "No duties provided.", "error", "true");
        }

        // Truncate if too long
        if (duties.length() > 2000) {
            duties = duties.substring(0, 2000) + "...";
        }

        try {
            String prompt = buildOptimizedRewritePrompt(duties);
            String rewritten = callOpenAIWithTimeout(prompt, 60);
            
            return Map.of("rewritten", rewritten.trim());
        } catch (Exception e) {
            return Map.of("rewritten", "Error: " + e.getMessage(), "error", "true");
        }
    }

    /**
     * Optimized prompt for rewriting duties
     */
    private String buildOptimizedRewritePrompt(String duties) {
        return String.format("""
            Use the following links as guidelines and references: https://www.opm.gov/policy-data-oversight/classification-qualifications/classifying-general-schedule-positions/
            Use this as a reference and guide: https://www.opm.gov/policy-data-oversight/classification-qualifications/classifying-general-schedule-positions/
            Use this as a reference and guide: https://www.opm.gov/policy-data-oversight/classification-qualifications/classifying-general-schedule-positions/factor-evaluation-system/

            Rewrite the following federal job duties to be more professional and clear.
            Keep the same meaning, but improve the language.
            Return ONLY the improved duties as a numbered or bulleted list.
            DO NOT include any explanation, reasoning, commentary, or thinking steps.
            DO NOT repeat the original duties.
            DO NOT include any text except the rewritten duties list.
            
            %s
            """, duties);
    }

    /**
     * Call OpenAI API with configurable timeout and better error handling
     */
    private String callOpenAIWithTimeout(String prompt, int timeoutSeconds) throws Exception {
    try {
        // Prepare OpenAI API request
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "You are a federal HR specialist expert in writing professional job descriptions."));
        messages.add(new Message("user", prompt));

        OpenAIRequest openaiRequest = new OpenAIRequest("gpt-3.5-turbo", messages, false);
        openaiRequest.setMax_tokens(3000);
        openaiRequest.setTemperature(0.3);

        ObjectMapper objectMapper = new ObjectMapper();
        String requestBody = objectMapper.writeValueAsString(openaiRequest);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + pdService.getOpenaiApiKey())
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> openaiResponse = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (openaiResponse.statusCode() != 200) {
            throw new Exception("OpenAI API returned status: " + openaiResponse.statusCode() + " - " + openaiResponse.body());
        }

        JsonNode responseJson = objectMapper.readTree(openaiResponse.body());
        if (responseJson.has("choices") && responseJson.get("choices").size() > 0) {
            JsonNode choice = responseJson.get("choices").get(0);
            if (choice.has("message") && choice.get("message").has("content")) {
                String content = choice.get("message").get("content").asText().trim();
                if (content.isEmpty()) {
                    throw new Exception("Empty response from OpenAI API");
                }
                return content;
            }
        }

        throw new Exception("Invalid response format from OpenAI API");
    } catch (Exception e) {
        System.err.println("OpenAI API call failed: " + e.getMessage());
        throw new Exception("Failed to get AI analysis: " + e.getMessage());
    }
}

    /**
     * Async version using CompletableFuture for non-blocking execution
     */
    @PostMapping("/rewrite-duties-async")
    public CompletableFuture<Map<String, String>> rewriteDutiesAsync(@RequestBody Map<String, String> body) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return rewriteDutiesOptimized(body);
            } catch (Exception e) {
                return Map.of("rewritten", "Error: " + e.getMessage(), "error", "true");
            }
        });
    }

    /**
     * POST endpoint to recommend job series based on duties/responsibilities using OpenAI API.
     */
    @PostMapping("/recommend-series")
public Map<String, Object> recommendSeries(@RequestBody Map<String, String> body) throws Exception {
    String duties = body.getOrDefault("duties", "");
    if (duties.isBlank()) {
        return Map.of("recommendations", new ArrayList<>(), "error", "No duties provided");
    }

    try {
        // --- Use OpenAI classification for top 3 series ---
        List<Map<String, String>> classifications = classifySeries(body);

        List<Map<String, Object>> recommendations = new ArrayList<>();
        for (Map<String, String> classification : classifications) {
            String code = classification.getOrDefault("seriesCode", "");
            String title = classification.getOrDefault("seriesTitle", "");

            Map<String, Object> recommendation = new HashMap<>();
            recommendation.put("code", code);
            recommendation.put("title", title);
            recommendation.put("confidence", 0.99); // Direct from OpenAI
            recommendation.put("topPosition", title);

            recommendations.add(recommendation);
        }

        // Get GS grade relevancy from AI
        List<Map<String, Object>> gradeRelevancy = getAIGSGradeRelevancy(duties);

        String gsGrade = gradeRelevancy != null && !gradeRelevancy.isEmpty()
            ? (String) gradeRelevancy.get(0).get("grade")
            : "GS-13";

        Map<String, Object> result = new HashMap<>();
        result.put("recommendations", recommendations);
        result.put("gsGrade", gsGrade);
        result.put("gradeRelevancy", gradeRelevancy);

        if (!result.containsKey("gradeRelevancy") || result.get("gradeRelevancy") == null) {
            result.put("gradeRelevancy", new ArrayList<>());
        }

        return result;
    } catch (Exception e) {
        System.err.println("OpenAI classification failed: " + e.getMessage());
        return Map.of("recommendations", new ArrayList<>(), "error", "OpenAI classification failed");
    }
}

    /**
     * Escapes special JSON characters in a string.
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * GET endpoint to fetch fresh position data for a specific job series
     */
    @GetMapping("/job-series/{code}/positions")
    public ResponseEntity<List<String>> getPositionsForSeries(@PathVariable String code) {
        try {
            // Get the job series data from service
            Map<String, Map<String, Object>> allSeries = jobSeriesService.getJobSeriesData();
            
            if (allSeries.containsKey(code)) {
                Map<String, Object> seriesData = allSeries.get(code);
                
                @SuppressWarnings("unchecked")
                List<String> positions = (List<String>) seriesData.get("positions");
                
                if (positions != null && !positions.isEmpty()) {
                    // Filter out any null or empty positions
                    List<String> validPositions = positions.stream()
                        .filter(pos -> pos != null && !pos.trim().isEmpty())
                        .distinct()
                        .collect(Collectors.toList());
                    
                    return ResponseEntity.ok(validPositions);
                }
            }
            
            // Return empty list if no positions found
            return ResponseEntity.ok(new ArrayList<>());
            
        } catch (Exception e) {
            System.err.println("Error fetching positions for series " + code + ": " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(new ArrayList<>());
        }
    }

    /**
     * Alternative endpoint that gets fresh data directly from JobSeriesService
     */
    @GetMapping("/job-series/{code}/fresh-positions")
    public ResponseEntity<List<String>> getFreshPositionsForSeries(@PathVariable String code) {
        try {
            return getPositionsForSeries(code);
            
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(new ArrayList<>());
        }
    }

    /**
     * Add this helper method (calls OpenAI or your logic)
     */
    @SuppressWarnings("unused")
    private String getAIGSGrade(String duties) throws Exception {
        String prompt = String.format("""
            Use the following links as guidelines and references: https://www.opm.gov/policy-data-oversight/classification-qualifications/classifying-general-schedule-positions/
            Use this as a reference and guide: https://www.opm.gov/policy-data-oversight/classification-qualifications/classifying-general-schedule-positions/
            Use this as a reference and guide: https://www.opm.gov/policy-data-oversight/classification-qualifications/classifying-general-schedule-positions/factor-evaluation-system/
    
            Based on the following federal job duties, recommend the most appropriate GS grade level (e.g., GS-11, GS-12, GS-13, GS-14).
            Only return the grade code (e.g., GS-13) and nothing else.

            Duties:
            %s
            """, duties);

        String response = callOpenAIWithTimeout(prompt, 10);
        // Extract GS grade from response (e.g., "GS-13")
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("GS-\\d{2}").matcher(response);
        if (m.find()) return m.group();
        return response.trim();
    }

    private List<Map<String, Object>> getAIGSGradeRelevancy(String duties) throws Exception {
    String prompt = String.format("""
        Use the official OPM two-grade interval system for professional and administrative positions as described here:
        https://www.opm.gov/policy-data-oversight/classification-qualifications/classifying-general-schedule-positions/positionclassificationintro.pdf

        Based on the following federal job duties, list the top 5 most likely GS grade levels for this position.
        Only consider two-grade interval grades (GS-5, GS-7, GS-9, GS-11, GS-12, GS-13, GS-14, GS-15) unless the duties clearly fit a one-grade interval series.
        For each grade, provide a percentage likelihood (total should sum to 100).
        Respond ONLY in this JSON format:
        [
        {"grade": "GS-13", "percentage": 40},
        {"grade": "GS-12", "percentage": 25},
        {"grade": "GS-11", "percentage": 20},
        {"grade": "GS-09", "percentage": 10},
        {"grade": "GS-07", "percentage": 5}
        ]

        Duties:
        %s
        """, duties);

    String response = callOpenAIWithTimeout(prompt, 15);

    ObjectMapper mapper = new ObjectMapper();
    List<Map<String, Object>> grades;
    try {
        grades = mapper.readValue(response, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
    } catch (Exception e) {
        // Fallback: try to extract grades with regex if AI returns text
        grades = new ArrayList<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("GS-\\d{2}");
        java.util.regex.Matcher m = p.matcher(response);
        while (m.find() && grades.size() < 5) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("grade", m.group());
            entry.put("percentage", 100 / (grades.size() + 1));
            grades.add(entry);
        }
    }

    // --- Sanitize and normalize percentages ---
    double total = 0;
    for (Map<String, Object> entry : grades) {
        Object pctObj = entry.get("percentage");
        double pct = 0;
        if (pctObj instanceof Number) {
            pct = ((Number) pctObj).doubleValue();
        } else if (pctObj instanceof String) {
            try {
                pct = Double.parseDouble((String) pctObj);
            } catch (Exception ignore) {}
        }
        // Clamp to [0, 100]
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        entry.put("percentage", pct);
        total += pct;
    }
    // Normalize so total is 100
    if (total > 0 && Math.abs(total - 100) > 0.01) {
        for (Map<String, Object> entry : grades) {
            double pct = ((Number) entry.get("percentage")).doubleValue();
            double norm = Math.round((pct / total) * 100);
            entry.put("percentage", norm);
        }
        // Fix rounding error on last item
        double newTotal = grades.stream().mapToDouble(e -> ((Number) e.get("percentage")).doubleValue()).sum();
        if (Math.abs(newTotal - 100) > 0.01 && !grades.isEmpty()) {
            double diff = 100 - newTotal;
            Map<String, Object> last = grades.get(grades.size() - 1);
            last.put("percentage", ((Number) last.get("percentage")).doubleValue() + diff);
        }
    }
    
    List<String> allowedGrades = List.of("GS-5", "GS-7", "GS-9", "GS-11", "GS-12", "GS-13", "GS-14", "GS-15");
    grades = grades.stream()
        .filter(entry -> allowedGrades.contains(String.valueOf(entry.get("grade"))))
        .collect(Collectors.toList());

    return grades;
}

@PostMapping("/generate-evaluation-statement")
public Map<String, String> generateEvaluationStatement(@RequestBody Map<String, String> body) throws Exception {
    String duties = body.getOrDefault("duties", "");
    String requirements = body.getOrDefault("requirements", "");
    String gsGrade = body.getOrDefault("gsGrade", "");
    String jobSeries = body.getOrDefault("jobSeries", "");
    String jobTitle = body.getOrDefault("jobTitle", "");
    String positionTitle = body.getOrDefault("positionTitle", "");
    String supervisoryLevel = body.getOrDefault("supervisoryLevel", "Non-Supervisory");

    // Define the official OMP factor point values for validation
    final Map<String, Map<String, Integer>> FACTOR_POINTS = new HashMap<>();
    FACTOR_POINTS.put("1", Map.of(
        "1-1", 50, "1-2", 200, "1-3", 350, "1-4", 550, "1-5", 750, 
        "1-6", 950, "1-7", 1250, "1-8", 1550, "1-9", 1850
    ));
    FACTOR_POINTS.put("2", Map.of(
        "2-1", 25, "2-2", 125, "2-3", 275, "2-4", 450, "2-5", 650
    ));
    FACTOR_POINTS.put("3", Map.of(
        "3-1", 25, "3-2", 125, "3-3", 275, "3-4", 450, "3-5", 650
    ));
    FACTOR_POINTS.put("4", Map.of(
        "4-1", 25, "4-2", 75, "4-3", 150, "4-4", 225, "4-5", 325, "4-6", 450
    ));
    FACTOR_POINTS.put("5", Map.of(
        "5-1", 25, "5-2", 75, "5-3", 150, "5-4", 225, "5-5", 325, "5-6", 450
    ));
    FACTOR_POINTS.put("6", Map.of(
        "6-1", 10, "6-2", 25, "6-3", 60, "6-4", 110
    ));
    FACTOR_POINTS.put("7", Map.of(
        "7-1", 20, "7-2", 50, "7-3", 120, "7-4", 220
    ));
    FACTOR_POINTS.put("8", Map.of(
        "8-1", 5, "8-2", 20, "8-3", 50
    ));
    FACTOR_POINTS.put("9", Map.of(
        "9-1", 5, "9-2", 20, "9-3", 50
    ));

    // Build enhanced prompt with explicit factor constraints and official point values
    String prompt = String.format("""
        You are a federal HR classification specialist creating an official OPM factor evaluation.
        
        CRITICAL: Use ONLY these exact factor levels and points:
        
        Factor 1 (Knowledge Required): 1-1 (50), 1-2 (200), 1-3 (350), 1-4 (550), 1-5 (750), 1-6 (950), 1-7 (1250), 1-8 (1550), 1-9 (1850)
        Factor 2 (Supervisory Controls): 2-1 (25), 2-2 (125), 2-3 (275), 2-4 (450), 2-5 (650)
        Factor 3 (Guidelines): 3-1 (25), 3-2 (125), 3-3 (275), 3-4 (450), 3-5 (650)
        Factor 4 (Complexity): 4-1 (25), 4-2 (75), 4-3 (150), 4-4 (225), 4-5 (325), 4-6 (450)
        Factor 5 (Scope and Effect): 5-1 (25), 5-2 (75), 5-3 (150), 5-4 (225), 5-5 (325), 5-6 (450)
        Factor 6 (Personal Contacts): 6-1 (10), 6-2 (25), 6-3 (60), 6-4 (110)
        Factor 7 (Purpose of Contacts): 7-1 (20), 7-2 (50), 7-3 (120), 7-4 (220)
        Factor 8 (Physical Demands): 8-1 (5), 8-2 (20), 8-3 (50)
        Factor 9 (Work Environment): 9-1 (5), 9-2 (20), 9-3 (50)

        MANDATORY REQUIREMENTS:
        - Factor levels MUST total to achieve %s grade classification
        - Use ONLY the level-point combinations listed above
        - Each factor MUST use its own factor number (Factor 2 can ONLY use 2-1, 2-2, etc.)
        - Provide 4-5 sentence rationale for each factor explaining why it meets the assigned level

        Target Grade: %s
        Target Point Range: %s

        Position Details:
        - Job Series: %s
        - Job Title: %s
        - Position Title: %s
        - Supervisory Level: %s
        - Duties: %s
        - Requirements: %s

        Generate a complete evaluation statement with all 9 factors using this format:

        **Factor 1 – Knowledge Required by the Position Level 1-X, XXX Points**
        [4-5 sentences explaining why this position requires this level of knowledge and why it meets this specific level but not higher or lower levels]

        **Factor 2 – Supervisory Controls Level 2-X, XXX Points**
        [4-5 sentences explaining the nature of supervision and independence level]

        [Continue for all 9 factors...]

        **Total Points: [Sum of all factor points]**
        **Final Grade: %s**
        **Grade Range: %s**
        """,
        gsGrade, gsGrade, getPointRangeForGrade(gsGrade), jobSeries, jobTitle, positionTitle, 
        supervisoryLevel, duties, requirements, gsGrade, getPointRangeForGrade(gsGrade)
    );

    String response = callOpenAIWithTimeout(prompt, 60);
    
    if (response == null || response.trim().isEmpty()) {
        throw new RuntimeException("OpenAI response was null or empty");
    }

    // Validate and correct the response
    String validatedResponse = validateAndCorrectEvaluationResponse(response, gsGrade, FACTOR_POINTS);
    
    return Map.of("evaluationStatement", validatedResponse.trim());
}

private String validateAndCorrectEvaluationResponse(String response, String expectedGrade, 
                                                Map<String, Map<String, Integer>> factorPoints) {
    System.out.println("Validating evaluation statement...");
    
    // Extract factor assignments from the response
    Pattern factorPattern = Pattern.compile("Factor\\s+(\\d+)\\s*[–-]\\s*[^\\n]*?Level\\s+(\\d+-\\d+),\\s*(\\d+)\\s*Points");
    Matcher matcher = factorPattern.matcher(response);
    
    StringBuilder correctedResponse = new StringBuilder(response);
    List<String> corrections = new ArrayList<>();
    int totalCalculatedPoints = 0;
    
    while (matcher.find()) {
        String factorNum = matcher.group(1);
        String assignedLevel = matcher.group(2);
        int assignedPoints = Integer.parseInt(matcher.group(3));
        
        // Validate that the factor level exists and points match
        Map<String, Integer> validLevelsForFactor = factorPoints.get(factorNum);
        if (validLevelsForFactor == null) {
            corrections.add("Unknown factor number: " + factorNum);
            continue;
        }
        
        // Check if assigned level is valid for this factor
        Integer correctPoints = validLevelsForFactor.get(assignedLevel);
        if (correctPoints == null) {
            // Find the closest valid level for this factor
            String correctedLevel = findClosestValidLevel(factorNum, assignedLevel, validLevelsForFactor);
            correctPoints = validLevelsForFactor.get(correctedLevel);
            
            // Replace in response
            String originalText = "Level " + assignedLevel + ", " + assignedPoints + " Points";
            String correctedText = "Level " + correctedLevel + ", " + correctPoints + " Points";
            correctedResponse = new StringBuilder(correctedResponse.toString().replace(originalText, correctedText));
            
            corrections.add("Factor " + factorNum + ": Corrected invalid level " + assignedLevel + " to " + correctedLevel);
            totalCalculatedPoints += correctPoints;
        } else if (!correctPoints.equals(assignedPoints)) {
            // Level is valid but points are wrong
            String originalText = "Level " + assignedLevel + ", " + assignedPoints + " Points";
            String correctedText = "Level " + assignedLevel + ", " + correctPoints + " Points";
            correctedResponse = new StringBuilder(correctedResponse.toString().replace(originalText, correctedText));
            
            corrections.add("Factor " + factorNum + ": Corrected points from " + assignedPoints + " to " + correctPoints);
            totalCalculatedPoints += correctPoints;
        } else {
            totalCalculatedPoints += assignedPoints;
        }
    }
    
    // Choose the most appropriate allowed two-grade interval and ensure totals align
    String finalGrade = calculateFinalGrade(totalCalculatedPoints);
    String gradeRange = calculateGradeRange(totalCalculatedPoints);

    // Always ensure the total points fall within the selected grade's official min/max.
    try {
        int min = getMinPointsForGrade(finalGrade);
        int max = getMaxPointsForGrade(finalGrade);
        if (min > 0 && max >= min) {
            if (totalCalculatedPoints < min) {
                corrections.add("Adjusted total points up to minimum for " + finalGrade + " (" + min + ")");
                totalCalculatedPoints = min;
            }
            if (totalCalculatedPoints > max) {
                corrections.add("Adjusted total points down to maximum for " + finalGrade + " (" + max + ")");
                totalCalculatedPoints = max;
            }
            // Update gradeRange after potential clamp
            gradeRange = getPointRangeForGrade(finalGrade);
        }
    } catch (Exception ignore) {}
    
    // Replace summary section
    correctedResponse = new StringBuilder(correctedResponse.toString().replaceAll(
        "\\*\\*Total Points:\\s*\\d+\\*\\*", 
        "**Total Points: " + totalCalculatedPoints + "**"
    ));
    correctedResponse = new StringBuilder(correctedResponse.toString().replaceAll(
        "\\*\\*Final Grade:\\s*GS-\\d+\\*\\*", 
        "**Final Grade: " + finalGrade + "**"
    ));
    correctedResponse = new StringBuilder(correctedResponse.toString().replaceAll(
        "\\*\\*Grade Range:\\s*[\\d-]+\\*\\*", 
        "**Grade Range: " + gradeRange + "**"
    ));
    
    // Log corrections made
    if (!corrections.isEmpty()) {
        System.out.println("Corrections made to evaluation statement:");
        corrections.forEach(System.out::println);
    }
    
    System.out.println("Final validation: " + totalCalculatedPoints + " points = " + finalGrade + 
                      " (expected: " + expectedGrade + ")");
    
    return correctedResponse.toString();
}

/**
 * Finds the closest valid level for a factor when AI assigns an invalid level
 */
private String findClosestValidLevel(String factorNum, String invalidLevel, Map<String, Integer> validLevels) {
    // Try to extract level number from invalid assignment
    try {
        String[] parts = invalidLevel.split("-");
        if (parts.length == 2) {
            int levelValue = Integer.parseInt(parts[1]);
            
            // Find the closest valid level for this factor
            String closestLevel = factorNum + "-1"; // default
            int maxValidLevel = 0;
            
            for (String validLevel : validLevels.keySet()) {
                String[] validParts = validLevel.split("-");
                if (validParts.length == 2 && validParts[0].equals(factorNum)) {
                    int validLevelValue = Integer.parseInt(validParts[1]);
                    maxValidLevel = Math.max(maxValidLevel, validLevelValue);
                    
                    if (validLevelValue <= levelValue) {
                        closestLevel = validLevel;
                    }
                }
            }
            
            // If requested level is higher than max, use max
            if (levelValue > maxValidLevel) {
                closestLevel = factorNum + "-" + maxValidLevel;
            }
            
            return closestLevel;
        }
    } catch (NumberFormatException e) {
        System.err.println("Could not parse invalid level: " + invalidLevel);
    }
    
    // Default to lowest level for this factor
    return factorNum + "-1";
}

/**
 * Complete updateFactorPoints method with proper error handling and level validation
 */
@PostMapping("/update-factor-points")
public Map<String, Object> updateFactorPoints(@RequestBody Map<String, Object> factors) {
    String supervisoryLevel = (String) factors.getOrDefault("supervisoryLevel", "Non-Supervisory");
    String expectedGrade = (String) factors.getOrDefault("expectedGrade", null); // Accept expected grade
    
    // Define the OPM factor point values according to official standards
    final Map<String, Map<String, Integer>> FACTOR_POINTS = new HashMap<>();
    FACTOR_POINTS.put("1", Map.of(
        "1-1", 50, "1-2", 200, "1-3", 350, "1-4", 550, "1-5", 750, 
        "1-6", 950, "1-7", 1250, "1-8", 1550, "1-9", 1850
    ));
    FACTOR_POINTS.put("2", Map.of(
        "2-1", 25, "2-2", 125, "2-3", 275, "2-4", 450, "2-5", 650
    ));
    FACTOR_POINTS.put("3", Map.of(
        "3-1", 25, "3-2", 125, "3-3", 275, "3-4", 450, "3-5", 650
    ));
    FACTOR_POINTS.put("4", Map.of(
        "4-1", 25, "4-2", 75, "4-3", 150, "4-4", 225, "4-5", 325, "4-6", 450
    ));
    FACTOR_POINTS.put("5", Map.of(
        "5-1", 25, "5-2", 75, "5-3", 150, "5-4", 225, "5-5", 325, "5-6", 450
    ));
    FACTOR_POINTS.put("6", Map.of(
        "6-1", 10, "6-2", 25, "6-3", 60, "6-4", 110
    ));
    FACTOR_POINTS.put("7", Map.of(
        "7-1", 20, "7-2", 50, "7-3", 120, "7-4", 220
    ));
    FACTOR_POINTS.put("8", Map.of(
        "8-1", 5, "8-2", 20, "8-3", 50
    ));
    FACTOR_POINTS.put("9", Map.of(
        "9-1", 5, "9-2", 20, "9-3", 50
    ));

    try {
        // Build AI prompt with specific level format instructions and validation
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an OPM HR expert specializing in federal position classification. ");
        prompt.append("Supervisory Level: ").append(supervisoryLevel).append("\n");
        
        // Add expected grade constraint if provided
        if (expectedGrade != null && !expectedGrade.isEmpty()) {
            int minPoints = getMinPointsForGrade(expectedGrade);
            int maxPoints = getMaxPointsForGrade(expectedGrade);
            prompt.append("CRITICAL: Factor levels MUST total between ").append(minPoints)
                .append(" and ").append(maxPoints).append(" points to achieve ")
                .append(expectedGrade).append(" classification.\n");
        }
        
        prompt.append("Review each factor content and assign the correct level based on OPM standards.\n\n");
        
        prompt.append("CRITICAL: Each factor has ONLY these valid levels:\n");
        prompt.append("Factor 1 (Knowledge Required): 1-1, 1-2, 1-3, 1-4, 1-5, 1-6, 1-7, 1-8, 1-9\n");
        prompt.append("Factor 2 (Supervisory Controls): 2-1, 2-2, 2-3, 2-4, 2-5\n");
        prompt.append("Factor 3 (Guidelines): 3-1, 3-2, 3-3, 3-4, 3-5\n");
        prompt.append("Factor 4 (Complexity): 4-1, 4-2, 4-3, 4-4, 4-5, 4-6\n");
        prompt.append("Factor 5 (Scope and Effect): 5-1, 5-2, 5-3, 5-4, 5-5, 5-6\n");
        prompt.append("Factor 6 (Personal Contacts): 6-1, 6-2, 6-3, 6-4\n");
        prompt.append("Factor 7 (Purpose of Contacts): 7-1, 7-2, 7-3, 7-4\n");
        prompt.append("Factor 8 (Physical Demands): 8-1, 8-2, 8-3\n");
        prompt.append("Factor 9 (Work Environment): 9-1, 9-2, 9-3\n\n");
        
        prompt.append("DO NOT use any other level formats. The factor number must match exactly.\n");
        prompt.append("For example: Factor 2 can ONLY use 2-1, 2-2, 2-3, 2-4, or 2-5. Never use 3-4 for Factor 2.\n\n");
        
        if (expectedGrade != null && !expectedGrade.isEmpty()) {
            prompt.append("Ensure your factor level assignments total to achieve ").append(expectedGrade)
                .append(" classification.\n\n");
        }
        
        prompt.append("Return JSON only in this exact format:\n");
        prompt.append("{\"Factor 1\": {\"level\":\"1-X\",\"rationale\":\"brief explanation\"}, ");
        prompt.append("\"Factor 2\": {\"level\":\"2-X\",\"rationale\":\"brief explanation\"}}\n");
        prompt.append("Only include factors that have content provided.\n\n");
        prompt.append("FACTOR CONTENT:\n{");

        // Add factor content to prompt
        boolean hasContent = false;
        for (Map.Entry<String, Object> entry : factors.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            if (value != null && !value.toString().isBlank() && !key.equals("expectedGrade") && !key.equals("supervisoryLevel")) {
                if (hasContent) prompt.append(",");
                String factorNum = key.replace("Factor ", "").trim();
                String content = value.toString().replace("\"", "'").replace("\n", " ");
                prompt.append("\"").append(factorNum).append("\":\"").append(content).append("\"");
                hasContent = true;
            }
        }
        prompt.append("}");

        if (!hasContent) {
            return Map.of(
                "error", "No factor content provided for evaluation",
                "success", false,
                "timestamp", new Date()
            );
        }

        // Log the request
        System.out.println("Starting OpenAI factor evaluation request at: " + new Date());
        System.out.println("Expected Grade: " + expectedGrade);
        System.out.println("Evaluating factors: " + factors.keySet().stream()
            .filter(k -> !k.equals("expectedGrade") && !k.equals("supervisoryLevel"))
            .collect(Collectors.toList()));

        // Call OpenAI API with the constructed prompt
        String response = callOpenAIWithTimeout(prompt.toString(), 25);

        if (response == null || response.trim().isEmpty()) {
            throw new RuntimeException("Empty response from OpenAI API");
        }

        System.out.println("Raw OpenAI response: " + response);

        // Extract JSON from the response
        String jsonResponse = extractJsonFromResponse(response);
        if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
            throw new RuntimeException("Could not extract valid JSON from OpenAI response");
        }

        System.out.println("Extracted JSON: " + jsonResponse);

        // Parse the AI response
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Map<String, String>> aiLevels;
        
        try {
            aiLevels = mapper.readValue(jsonResponse, new TypeReference<Map<String, Map<String, String>>>() {});
        } catch (JsonProcessingException e) {
            System.err.println("JSON parsing failed. Response: " + jsonResponse);
            throw new RuntimeException("Failed to parse AI response as JSON: " + e.getMessage());
        }

        // Process AI recommendations and calculate points
        int totalPoints = 0;
        Map<String, Object> finalFactors = new LinkedHashMap<>();
        List<String> processingWarnings = new ArrayList<>();

        for (Map.Entry<String, Map<String, String>> entry : aiLevels.entrySet()) {
            String factorKey = entry.getKey();
            String factorNum = factorKey.replace("Factor ", "").trim();
            
            System.out.println("Processing factor: '" + factorNum + "'");
            
            Map<String, String> levelData = entry.getValue();
            String originalLevel = levelData.get("level");
            
            if (originalLevel == null || originalLevel.isEmpty()) {
                processingWarnings.add("No level provided for factor " + factorNum);
                continue;
            }

            System.out.println("AI assigned level for factor " + factorNum + ": '" + originalLevel + "'");

            // Correct and validate the level format
            String correctedLevel = correctAndValidateLevel(factorNum, originalLevel);
            
            if (correctedLevel == null) {
                processingWarnings.add("Could not correct invalid level '" + originalLevel + "' for factor " + factorNum);
                continue;
            }

            if (!originalLevel.equals(correctedLevel)) {
                processingWarnings.add("Corrected level for factor " + factorNum + ": " + originalLevel + " -> " + correctedLevel);
                System.out.println("Corrected level for factor " + factorNum + ": " + originalLevel + " -> " + correctedLevel);
            }

            // Get the point mapping for this factor
            Map<String, Integer> factorPointMap = FACTOR_POINTS.get(factorNum);
            if (factorPointMap == null) {
                processingWarnings.add("Unknown factor number: " + factorNum);
                continue;
            }

            // Look up points for the corrected level
            Integer points = factorPointMap.get(correctedLevel);
            if (points == null) {
                // This should not happen with proper correction, but handle it anyway
                processingWarnings.add("Invalid level '" + correctedLevel + "' for factor " + factorNum + " after correction");
                continue;
            }

            totalPoints += points;

            // Create the factor result
            Map<String, Object> factorResult = new LinkedHashMap<>();
            factorResult.put("header", "Factor " + factorNum + " Level " + correctedLevel + ", " + points + " Points");
            factorResult.put("content", factors.getOrDefault("Factor " + factorNum, ""));
            factorResult.put("level", correctedLevel);
            factorResult.put("points", points);
            factorResult.put("rationale", levelData.getOrDefault("rationale", "Level assigned based on factor analysis"));

            finalFactors.put("Factor " + factorNum, factorResult);
        }

        // Calculate final grade and range
        String gradeRange = calculateGradeRange(totalPoints);
        String finalGrade = calculateFinalGrade(totalPoints);

        // Build the summary string
        String summary = String.format("**Total Points: %d**\n**Final Grade: %s**\n**Grade Range: %s**", totalPoints, finalGrade, gradeRange);
        // Append summary to evaluation statement
        finalFactors.put("Grade Evaluations", summary);
        // Build the final response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("factors", finalFactors);
        result.put("totalPoints", totalPoints);
        result.put("gradeRange", gradeRange);
        result.put("finalGrade", finalGrade);
        result.put("timestamp", new Date());
        
        // Validate that calculated grade matches expected grade
        if (expectedGrade != null && !expectedGrade.isEmpty()) {
            if (!finalGrade.equals(expectedGrade)) {
                processingWarnings.add("Calculated grade (" + finalGrade + ") differs from expected grade (" + expectedGrade + ")");
                result.put("gradeDiscrepancy", true);
                result.put("expectedGrade", expectedGrade);
                result.put("calculatedGrade", finalGrade);
            } else {
                result.put("gradeMatchConfirmed", true);
                System.out.println("SUCCESS: Calculated grade matches expected grade: " + expectedGrade);
            }
        }
        
        // Include warnings if any
        if (!processingWarnings.isEmpty()) {
            result.put("warnings", processingWarnings);
        }

        System.out.println("Successfully processed factor evaluation. Total points: " + totalPoints + 
                          ", Final grade: " + finalGrade);
        
        return result;

    } catch (Exception e) {
        // Enhanced error logging
        System.err.println("Error in updateFactorPoints: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        e.printStackTrace();

        // Return detailed error response instead of throwing
        Map<String, Object> errorResponse = new LinkedHashMap<>();
        errorResponse.put("success", false);
        errorResponse.put("error", "Failed to process factor evaluation: " + e.getMessage());
        errorResponse.put("errorType", e.getClass().getSimpleName());
        errorResponse.put("timestamp", new Date());
        errorResponse.put("factorsReceived", factors.keySet());
        
        return errorResponse;
    }
}

/**
 * Helper method to correct and validate AI's level assignments
 * This prevents the "Invalid level 3-4 for factor 2" error
 */
private String correctAndValidateLevel(String factorNum, String aiLevel) {
    if (aiLevel == null || aiLevel.trim().isEmpty()) {
        System.out.println("Empty level provided for factor " + factorNum + ", using default");
        return factorNum + "-1"; // Default to lowest level
    }
    
    // Clean up the input
    aiLevel = aiLevel.trim();
    
    // If it doesn't contain a dash, assume it's just a number
    if (!aiLevel.contains("-")) {
        try {
            int levelNum = Integer.parseInt(aiLevel);
            return factorNum + "-" + Math.max(1, Math.min(levelNum, getMaxLevelForFactor(factorNum)));
        } catch (NumberFormatException e) {
            System.err.println("Could not parse level number from: " + aiLevel);
            return factorNum + "-1";
        }
    }
    
    // Parse the level format (should be like "2-3")
    String[] parts = aiLevel.split("-");
    if (parts.length != 2) {
        System.err.println("Invalid level format: " + aiLevel + " for factor " + factorNum);
        return factorNum + "-1";
    }
    
    try {
        int providedFactorNum = Integer.parseInt(parts[0]);
        int levelNumber = Integer.parseInt(parts[1]);
        
        // Ensure the factor number matches (this fixes the "3-4 for factor 2" issue)
        int expectedFactorNum = Integer.parseInt(factorNum);
        if (providedFactorNum != expectedFactorNum) {
            System.out.println("Factor number mismatch: AI provided " + aiLevel + " for factor " + factorNum + 
                             ". Using correct factor number.");
            // Use the correct factor number but keep the level number if valid
            int maxLevel = getMaxLevelForFactor(factorNum);
            levelNumber = Math.max(1, Math.min(levelNumber, maxLevel));
            return factorNum + "-" + levelNumber;
        }
        
        // Validate the level number is within acceptable range for the factor
        int maxLevel = getMaxLevelForFactor(factorNum);
        if (levelNumber > maxLevel) {
            System.out.println("Level " + levelNumber + " too high for factor " + factorNum + 
                             " (max: " + maxLevel + "). Capping at maximum.");
            levelNumber = maxLevel;
        }
        if (levelNumber < 1) {
            System.out.println("Level " + levelNumber + " too low for factor " + factorNum + ". Setting to 1.");
            levelNumber = 1;
        }
        
        return factorNum + "-" + levelNumber;
        
    } catch (NumberFormatException e) {
        System.err.println("Could not parse level components from: " + aiLevel + " for factor " + factorNum);
        return factorNum + "-1"; // Safe fallback
    }
}

/**
 * Get the maximum valid level for each factor according to OPM standards
 */
private int getMaxLevelForFactor(String factorNum) {
    switch (factorNum) {
        case "1": return 9; // Factor 1 (Knowledge) has levels 1-1 through 1-9
        case "2": return 5; // Factor 2 (Supervisory Controls) has levels 2-1 through 2-5
        case "3": return 5; // Factor 3 (Guidelines) has levels 3-1 through 3-5
        case "4": return 6; // Factor 4 (Complexity) has levels 4-1 through 4-6
        case "5": return 6; // Factor 5 (Scope and Effect) has levels 5-1 through 5-6
        case "6": return 4; // Factor 6 (Personal Contacts) has levels 6-1 through 6-4
        case "7": return 4; // Factor 7 (Purpose of Contacts) has levels 7-1 through 7-4
        case "8": return 3; // Factor 8 (Physical Demands) has levels 8-1 through 8-3
        case "9": return 3; // Factor 9 (Work Environment) has levels 9-1 through 9-3
        default: 
            System.err.println("Unknown factor number: " + factorNum);
            return 1;
    }
}

// Non-overlapping, exhaustive mapping to allowed two-grade intervals only
private String calculateGradeRange(int totalPoints) {
    if (totalPoints >= 855 && totalPoints <= 1100) return "855-1100"; // GS-05
    if (totalPoints >= 1101 && totalPoints <= 1600) return "1101-1600"; // GS-07 (covers gap and full range)
    if (totalPoints >= 1601 && totalPoints <= 2100) return "1601-2100"; // GS-09
    if (totalPoints >= 2101 && totalPoints <= 2750) return "2101-2750"; // GS-11
    if (totalPoints >= 2751 && totalPoints <= 3150) return "2751-3150"; // GS-12
    if (totalPoints >= 3151 && totalPoints <= 3600) return "3151-3600"; // GS-13
    if (totalPoints >= 3601 && totalPoints <= 4050) return "3601-4050"; // GS-14
    if (totalPoints >= 4051) return "4051+"; // GS-15
    return "Unknown";
}

private String calculateFinalGrade(int totalPoints) {
    // Map total points to the corresponding two-grade interval grade
    if (totalPoints >= 855 && totalPoints <= 1100) return "GS-05";
    if (totalPoints >= 1101 && totalPoints <= 1600) return "GS-07";
    if (totalPoints >= 1601 && totalPoints <= 2100) return "GS-09";
    if (totalPoints >= 2101 && totalPoints <= 2750) return "GS-11";
    if (totalPoints >= 2751 && totalPoints <= 3150) return "GS-12";
    if (totalPoints >= 3151 && totalPoints <= 3600) return "GS-13";
    if (totalPoints >= 3601 && totalPoints <= 4050) return "GS-14";
    if (totalPoints >= 4051) return "GS-15";
    return "Unknown";
}

private int getMinPointsForGrade(String grade) {
    switch (grade) {
        case "GS-05": return 855;
        case "GS-07": return 1355;
        case "GS-09": return 1855;
        case "GS-11": return 2355;
        case "GS-12": return 2755;
        case "GS-13": return 3155;
        case "GS-14": return 3605;
        case "GS-15": return 4055;
        default: return 0;
    }
}

private int getMaxPointsForGrade(String grade) {
    switch (grade) {
        case "GS-05": return 1100;
        case "GS-07": return 1600;
        case "GS-09": return 2100;
        case "GS-11": return 2750;
        case "GS-12": return 3150;
        case "GS-13": return 3600;
        case "GS-14": return 4050;
        case "GS-15": return Integer.MAX_VALUE; // No upper limit
        default: return Integer.MAX_VALUE;
    }
}

// --- Helper: Only allow numeric grade ranges ---
private String getPointRangeForGrade(String grade) {
    if (grade == null) return "Unknown";
    switch (grade.toUpperCase()) {
        case "GS-05": case "GS-5": return "855-1100";
        case "GS-07": case "GS-7": return "1355-1600";
        case "GS-09": case "GS-9": return "1855-2100";
        case "GS-11": return "2355-2750";
        case "GS-12": return "2755-3150";
        case "GS-13": return "3155-3600";
        case "GS-14": return "3605-4050";
        case "GS-15": return "4055+";
        default: return "Unknown";
    }
}

/**
 * Helper method to extract JSON from AI response that might contain extra text
 */
private String extractJsonFromResponse(String response) {
    // Find the first { and last } to extract JSON
    int firstBrace = response.indexOf("{");
    int lastBrace = response.lastIndexOf("}");
    
    if (firstBrace != -1 && lastBrace != -1 && firstBrace < lastBrace) {
        return response.substring(firstBrace, lastBrace + 1);
    }
    
    return response; // Return as-is if no clear JSON boundaries found
}

// Complete rewrite of the fixPDFormatting method to handle all spacing issues
private String fixPDFormatting(String pdText) {
    if (pdText == null || pdText.trim().isEmpty()) return "";

    // 1. Fix the most critical spacing issues - Factor headers
    pdText = pdText.replaceAll("(Factor\\s*)(\\d+)(\\s*-)", "Factor $2 -");
    pdText = pdText.replaceAll("(Level\\s*)(\\d+)-(\\d+),\\s*(\\d+)", "Level $2-$3, $4");
    pdText = pdText.replaceAll("(Level\\s*)(\\d+)-(\\d+)\\s*(\\d+)", "Level $2-$3, $4");
    
    // 2. Fix Major Duties headers with proper spacing
    pdText = pdText.replaceAll("(MAJOR\\s*DUTIES?\\s*)(\\d+)(\\s*\\([^)]+\\))", "MAJOR DUTIES $2 $3");
    pdText = pdText.replaceAll("(MAJOR\\s*DUTY\\s*)(\\d+)(\\s*\\([^)]+\\))", "MAJOR DUTIES $2 $3");
    
    // 3. Ensure proper spacing around points
    pdText = pdText.replaceAll("(\\d+)Points", "$1 Points");
    pdText = pdText.replaceAll("Points:\\s*(\\d+)", "Points:** $1");
    
    // 4. Fix percentage formatting in duties
    pdText = pdText.replaceAll("(\\d+)%([A-Z])", "$1%)\n\n**$2");
    pdText = pdText.replaceAll("\\((\\d+%)(\\))", "($1)");
    
    // 5. Fix header sections with proper bold formatting and line breaks
    String[] majorSections = {
        "HEADER:", "INTRODUCTION:", "MAJOR DUTIES:", "FACTOR EVALUATION", 
        "CONDITIONS OF EMPLOYMENT:", "TITLE AND SERIES", "FAIR LABOR STANDARDS"
    };
    
    for (String section : majorSections) {
        pdText = pdText.replaceAll("(?<!\\n\\n)\\*\\*\\s*" + section.replace(":", "\\:"), "\n\n**" + section + "**");
        pdText = pdText.replaceAll("(?<!\\n\\n)" + section.replace(":", "\\:"), "\n\n**" + section + "**");
    }

    // 6. Fix Factor headers with consistent formatting
    pdText = pdText.replaceAll("\\*\\*\\s*(Factor\\s*\\d+\\s*-[^\\*]+?)\\s*Level\\s*(\\d+-\\d+),?\\s*(\\d+)\\s*Points\\s*\\*\\*", 
                            "\n\n**$1 Level $2, $3 Points**");
    
    // 7. Fix Major Duties headers with proper bold formatting
    pdText = pdText.replaceAll("\\*\\*\\s*(MAJOR\\s*DUTIES?\\s*\\d+\\s*\\([^)]+\\))\\s*\\*\\*", 
                            "\n\n**$1**");

    // 8. Fix summary section formatting
    pdText = pdText.replaceAll("\\*\\*Total Points:\\*\\*\\s*(\\d+)", "\n\n**Total Points:** $1");
    pdText = pdText.replaceAll("\\*\\*Final Grade:\\*\\*\\s*(GS-\\d+)", "\n\n**Final Grade:** $1");
    pdText = pdText.replaceAll("\\*\\*Grade Range:\\*\\*\\s*(\\d+-\\d+)", "\n\n**Grade Range:** $1");
    
    // 9. Ensure proper spacing after colons in all contexts
    pdText = pdText.replaceAll("([A-Za-z]+):([A-Za-z])", "$1: $2");
    
    // 10. Fix any remaining concatenated text
    pdText = pdText.replaceAll("([a-z])([A-Z][A-Z]+:)", "$1\n\n**$2**");
    
    // 11. Clean up multiple line breaks and excessive whitespace
    pdText = pdText.replaceAll("\\n{3,}", "\n\n");
    pdText = pdText.replaceAll(" +\\n", "\n");
    pdText = pdText.replaceAll("\\n +", "\n");
    
    // 12. Final formatting pass for headers
    pdText = pdText.replaceAll("\\*\\*([^\\*]+)\\*\\*\\s*\\*\\*", "**$1**\n\n**");
    
    return pdText.trim();
}

@PostMapping("/classify-series")
public List<Map<String, String>> classifySeries(@RequestBody Map<String, String> body) throws Exception {
    String duties = body.getOrDefault("duties", "");
    if (duties.isBlank()) {
        return List.of(Map.of("error", "No duties provided"));
    }

    String prompt = String.format("""
        You are an expert in federal HR classification. 
        Based on the following job duties, recommend the top three most appropriate federal job series codes (e.g., 1801, 1811, 0132, 0343, etc.).
        Use the following links as guidelines and references: https://www.opm.gov/policy-data-oversight/classification-qualifications/classifying-general-schedule-positions/
        Respond ONLY with a numbered list in this format:
        1. 1801 - General Inspection, Investigation, Enforcement, and Compliance
        2. 1811 - Criminal Investigator
        3. 0132 - Intelligence Operations Specialist

        Duties:
        %s
        """, duties);

    String response = callOpenAIWithTimeout(prompt, 20);

    // Parse the response into a list of series code/title maps
    List<Map<String, String>> results = new ArrayList<>();
    Pattern p = Pattern.compile("\\d+\\.\\s*(\\d{4})\\s*[-:]?\\s*(.*)");
    Matcher m = p.matcher(response.trim());
    while (m.find()) {
        String code = m.group(1);
        String title = m.group(2).trim();
        results.add(Map.of("seriesCode", code, "seriesTitle", title));
    }
    // Fallback: if nothing matched, add the whole response
    if (results.isEmpty()) {
        results.add(Map.of("seriesCode", response.trim(), "seriesTitle", ""));
    }
    return results;
}

public String extractPdfText(String pdfPath) throws Exception {
    try (PDDocument document = PDDocument.load(new File(pdfPath))) {
        PDFTextStripper stripper = new PDFTextStripper();
        return stripper.getText(document);
    }
}

private static String cachedHandbookText = null;

public String getHandbookText() {
    if (cachedHandbookText == null) {
        try {
            // Adjust the path as needed for your deployment
            cachedHandbookText = extractPdfText("src/main/resources/static/occupationalhandbook.pdf");
        } catch (Exception e) {
            cachedHandbookText = "";
            System.err.println("Failed to extract PDF text: " + e.getMessage());
        }
    }
    return cachedHandbookText;
}

/**
 * Add this method to your PdGeneratorController to coordinate evaluation and PD generation
 */
@PostMapping("/generate-with-evaluation")
public void generatePdWithEvaluation(@RequestBody PdRequest request, HttpServletResponse response) throws Exception {
    System.out.println("=== COORDINATED PD GENERATION STARTED ===");
    
    response.setContentType("text/event-stream");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-cache");
    response.setHeader("Connection", "keep-alive");
    response.setHeader("Access-Control-Allow-Origin", "*");

    PrintWriter writer = response.getWriter();

    try {
        // Validate request
        if (request == null || request.getHistoricalData() == null || request.getHistoricalData().trim().isEmpty()) {
            writer.println("data: {\"error\":\"Job duties are required\"}\n");
            writer.flush();
            return;
        }

        writer.println("data: {\"status\":\"Generating evaluation statement...\"}\n");
        writer.flush();

        // Step 1: Determine AI grade relevancy and use top recommendation as expected grade
        List<Map<String, Object>> gradeRelevancy = getAIGSGradeRelevancy(request.getHistoricalData());
        String aiTopGrade = null;
        if (gradeRelevancy != null && !gradeRelevancy.isEmpty()) {
            Object g = gradeRelevancy.get(0).get("grade");
            if (g != null) aiTopGrade = g.toString();
        }

        // --- ENFORCE ONLY VALID TWO-GRADE INTERVALS ---
        List<String> allowedGrades = List.of("GS-5", "GS-7", "GS-9", "GS-11", "GS-12", "GS-13", "GS-14", "GS-15");
        if (aiTopGrade == null || !allowedGrades.contains(aiTopGrade)) {
            // Fallback to GS-13 if AI returns invalid grade
            aiTopGrade = "GS-13";
        }

        // Step 2: Generate evaluation statement with corrected factors using AI top grade as expected
        Map<String, String> evaluationRequest = new HashMap<>();
        evaluationRequest.put("duties", request.getHistoricalData());
        evaluationRequest.put("gsGrade", aiTopGrade);
        evaluationRequest.put("jobSeries", request.getJobSeries() != null ? request.getJobSeries() : "");
        evaluationRequest.put("jobTitle", request.getSubJobSeries() != null ? request.getSubJobSeries() : "");
        evaluationRequest.put("positionTitle", request.getSubJobSeries() != null ? request.getSubJobSeries() : "");
        evaluationRequest.put("supervisoryLevel", request.getSupervisoryLevel() != null ? request.getSupervisoryLevel() : "Non-Supervisory");

        Map<String, String> evaluationResult = generateEvaluationStatement(evaluationRequest);
        String evaluationStatement = evaluationResult.get("evaluationStatement");

        if (evaluationStatement == null || evaluationStatement.isEmpty()) {
            writer.println("data: {\"error\":\"Failed to generate evaluation statement\"}\n");
            writer.flush();
            return;
        }

        writer.println("data: {\"status\":\"Extracting evaluation results...\"}\n");
        writer.flush();

        // Step 3: Extract the corrected values from the evaluation statement
        String extractedGrade = extractGradeFromEvaluation(evaluationStatement);
        String extractedRange = extractRangeFromEvaluation(evaluationStatement);
        Integer extractedPoints = extractPointsFromEvaluation(evaluationStatement);

        // --- ENFORCE CONSISTENCY: Use only extracted values from evaluation statement ---
        request.setGsGrade(extractedGrade != null ? extractedGrade : aiTopGrade);
        request.setGradeRange(extractedRange != null ? extractedRange : getPointRangeForGrade(aiTopGrade));
        request.setTotalPoints(extractedPoints != null ? extractedPoints : null);

        writer.println("data: {\"status\":\"Generating position description with validated results...\"}\n");
        writer.flush();

        // Step 4: Generate PD using the corrected values
        String prompt = pdService.buildPrompt(request);
        
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", 
            "You are an expert federal HR classification specialist. Use the exact grade, points, and range provided. " +
            "Create comprehensive position descriptions with real content, never placeholders."
        ));
        messages.add(new Message("user", prompt));

        OpenAIRequest openaiRequest = new OpenAIRequest("gpt-4", messages, true);
        
        ObjectMapper objectMapper = new ObjectMapper();
        String requestBody = objectMapper.writeValueAsString(openaiRequest);

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
            .header("Authorization", "Bearer " + pdService.getOpenaiApiKey())
            .header("Content-Type", "application/json")
            .timeout(java.time.Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build();
                
        HttpResponse<InputStream> openaiResponse = client.send(httpRequest, 
            HttpResponse.BodyHandlers.ofInputStream());

        if (openaiResponse.statusCode() != 200) {
            writer.println("data: {\"error\":\"OpenAI API Error\"}\n");
            writer.flush();
            return;
        }

        // Stream the position description
        StringBuilder fullPD = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(openaiResponse.body()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6);
                    if (!"[DONE]".equals(data.trim()) && !data.trim().isEmpty()) {
                        try {
                            JsonNode jsonNode = objectMapper.readTree(data);
                            if (jsonNode.has("choices") && jsonNode.get("choices").size() > 0) {
                                JsonNode choice = jsonNode.get("choices").get(0);
                                if (choice.has("delta") && choice.get("delta").has("content")) {
                                    String content = choice.get("delta").get("content").asText();
                                    if (!content.trim().isEmpty()) {
                                        writer.println("data: {\"response\":\"" + escapeJson(content) + "\"}\n");
                                        writer.flush();
                                        fullPD.append(content);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("Error parsing streaming response: " + e.getMessage());
                        }
                    }
                }
            }
        }

        // Send final results with both documents
        Map<String, Object> finalResult = new HashMap<>();
        finalResult.put("evaluationStatement", evaluationStatement);
        finalResult.put("positionDescription", fullPD.toString());
        finalResult.put("finalGrade", request.getGsGrade());
        finalResult.put("gradeRange", request.getGradeRange());
        finalResult.put("totalPoints", request.getTotalPoints());

        writer.println("data: {\"complete\":true,\"results\":" + objectMapper.writeValueAsString(finalResult) + "}\n");
        writer.println("data: [DONE]\n");
        writer.flush();

        System.out.println("Coordinated generation completed successfully");

    } catch (Exception e) {
        System.err.println("EXCEPTION in generatePdWithEvaluation: " + e.getMessage());
        e.printStackTrace();
        writer.println("data: {\"error\":\"Generation failed: " + escapeJson(e.getMessage()) + "\"}\n");
    } finally {
        try {
            writer.flush();
            writer.close();
        } catch (Exception e) {
            System.err.println("Error closing writer: " + e.getMessage());
        }
        System.out.println("=== COORDINATED PD GENERATION COMPLETE ===");
    }
}

/**
 * Helper methods to extract values from evaluation statement
 */
private String extractGradeFromEvaluation(String evaluation) {
    Pattern pattern = Pattern.compile("Final Grade:\\s*(GS-\\d+)");
    Matcher matcher = pattern.matcher(evaluation);
    return matcher.find() ? matcher.group(1) : null;
}

private String extractRangeFromEvaluation(String evaluation) {
    Pattern pattern = Pattern.compile("Grade Range:\\s*([\\d-]+)");
    Matcher matcher = pattern.matcher(evaluation);
    return matcher.find() ? matcher.group(1) : null;
}

private Integer extractPointsFromEvaluation(String evaluation) {
    Pattern pattern = Pattern.compile("Total Points:\\s*(\\d+)");
    Matcher matcher = pattern.matcher(evaluation);
    if (matcher.find()) {
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
    return null;
}
}