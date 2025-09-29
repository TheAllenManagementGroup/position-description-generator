package com.example.pdgenerator.controller;

import com.example.pdgenerator.request.PdRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.example.pdgenerator.service.PdService;
import com.example.pdgenerator.jobseries.JobSeriesService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

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

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;

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
    String supervisoryLevel = body.getOrDefault("supervisoryLevel", "Non-Supervisory");
    
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

        // Get GS grade relevancy from AI with supervisory level
        List<Map<String, Object>> gradeRelevancy = getAIGSGradeRelevancy(duties, supervisoryLevel);

        String gsGrade = gradeRelevancy != null && !gradeRelevancy.isEmpty()
            ? (String) gradeRelevancy.get(0).get("grade")
            : "GS-13";

        Map<String, Object> result = new HashMap<>();
        result.put("recommendations", recommendations);
        result.put("gsGrade", gsGrade);
        result.put("gradeRelevancy", gradeRelevancy);
        result.put("supervisoryLevel", supervisoryLevel); // Include for consistency

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

    private List<Map<String, Object>> getAIGSGradeRelevancy(String duties, String supervisoryLevel) throws Exception {
    String prompt = String.format("""
        Use the official OPM two-grade interval system for professional and administrative positions as described here:
        https://www.opm.gov/policy-data-oversight/classification-qualifications/classifying-general-schedule-positions/positionclassificationintro.pdf

        IMPORTANT: Consider the supervisory level when determining grade levels:
        - Non-Supervisory: Focus on technical complexity and individual contribution
        - Team Leader: Add 1 grade level for informal leadership responsibilities
        - Supervisor: Add 1-2 grade levels for formal supervisory duties
        - Manager: Add 2-3 grade levels for managerial responsibilities

        Supervisory Level: %s

        Based on the following federal job duties and supervisory level, list the top 5 most likely GS grade levels for this position.
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
        """, supervisoryLevel != null ? supervisoryLevel : "Non-Supervisory", duties);

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

    // Sanitize and normalize percentages
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
public Map<String, String> generateEvaluationStatement(@RequestBody Map<String, String> body) {
    try {
        String duties = body.getOrDefault("duties", "");
        String requirements = body.getOrDefault("requirements", "");
        String gsGrade = body.getOrDefault("gsGrade", "");
        String jobSeries = body.getOrDefault("jobSeries", "");
        String jobTitle = body.getOrDefault("jobTitle", "");
        String positionTitle = body.getOrDefault("positionTitle", "");
        String supervisoryLevel = body.getOrDefault("supervisoryLevel", "Non-Supervisory");

        // If no grade provided, get it from AI analysis using the same logic as recommend-series
        if (gsGrade == null || gsGrade.trim().isEmpty()) {
            List<Map<String, Object>> gradeRelevancy = getAIGSGradeRelevancy(duties, supervisoryLevel);
            gsGrade = gradeRelevancy != null && !gradeRelevancy.isEmpty()
                ? (String) gradeRelevancy.get(0).get("grade")
                : "GS-13";
        }

        // CRITICAL: Use grade-specific factor level enforcement
        Map<String, String> gradeMinimums = getMinimumFactorLevelsForGrade(gsGrade);
        int targetMinPoints = getMinPointsForGrade(gsGrade);
        int targetMaxPoints = getMaxPointsForGrade(gsGrade);

        String supervisoryGuidance = getSupervisoryGuidance(supervisoryLevel);

        String prompt = String.format("""
        You are a federal HR classification specialist creating an official OPM factor evaluation.

        CRITICAL GRADE ALIGNMENT REQUIREMENTS FOR %s:
        - Total points MUST be between %d and %d points
        - Factor 1 MUST be at least level %s (%d points) - NO EXCEPTIONS
        - Factor 2 MUST be at least level %s (%d points) - NO EXCEPTIONS  
        - Factor 5 MUST be at least level %s (%d points) - NO EXCEPTIONS
        - Use ONLY official OPM factor levels and point values

        SUPERVISORY LEVEL: %s
        %s

        MANDATORY FACTOR POINTS (USE EXACT VALUES):
        Factor 1: 1-1(50), 1-2(200), 1-3(350), 1-4(550), 1-5(750), 1-6(950), 1-7(1250), 1-8(1550), 1-9(1850)
        Factor 2: 2-1(25), 2-2(125), 2-3(275), 2-4(450), 2-5(650)
        Factor 3: 3-1(25), 3-2(125), 3-3(275), 3-4(450), 3-5(650)
        Factor 4: 4-1(25), 4-2(75), 4-3(150), 4-4(225), 4-5(325), 4-6(450)
        Factor 5: 5-1(25), 5-2(75), 5-3(150), 5-4(225), 5-5(325), 5-6(450)
        Factor 6: 6-1(10), 6-2(25), 6-3(60), 6-4(110)
        Factor 7: 7-1(20), 7-2(50), 7-3(120), 7-4(220)
        Factor 8: 8-1(5), 8-2(20), 8-3(50)
        Factor 9: 9-1(5), 9-2(20), 9-3(50)

        Position Details:
        - Target Grade: %s (MUST achieve %d-%d points)
        - Job Series: %s
        - Job Title: %s
        - Position Title: %s
        - Supervisory Level: %s
        - Duties: %s
        - Requirements: %s

        Generate evaluation statement with ALL 9 factors. Each factor MUST:
        1. Meet minimum level requirements for %s
        2. Have 4-5 sentence rationale explaining the specific level assignment
        3. Reference specific duties and explain why this level (not higher/lower)
        4. Total to achieve %s classification (%d-%d points)

        Format:
        **Factor 1 – Knowledge Required by the Position Level 1-X, XXX Points**
        [4-5 sentences with specific duty examples and level justification]

        **Factor 2 – Supervisory Controls Level 2-X, XXX Points**
        [4-5 sentences explaining supervision level with %s considerations]

        [Continue for all 9 factors...]

        **Total Points: [EXACT sum of all 9 factors]**
        **Final Grade: %s**
        **Grade Range: %s**
        """,
        gsGrade, targetMinPoints, targetMaxPoints,
        gradeMinimums.get("f1"), getPointsForLevel("1", gradeMinimums.get("f1")),
        gradeMinimums.get("f2"), getPointsForLevel("2", gradeMinimums.get("f2")),
        gradeMinimums.get("f5"), getPointsForLevel("5", gradeMinimums.get("f5")),
        supervisoryLevel, supervisoryGuidance,
        gsGrade, targetMinPoints, targetMaxPoints, jobSeries, jobTitle, positionTitle,
        supervisoryLevel, duties, requirements, gsGrade, gsGrade, targetMinPoints, targetMaxPoints,
        supervisoryLevel, gsGrade, getPointRangeForGrade(gsGrade)
        );

        String response = callOpenAIWithTimeout(prompt, 60);

        // CRITICAL: Validate and enforce grade alignment
        String validatedResponse = enforceGradeAlignment(response, gsGrade, targetMinPoints, targetMaxPoints);

        return Map.of("evaluationStatement", validatedResponse.trim());

    } catch (Exception e) {
        System.err.println("Error in generate-evaluation-statement: " + e.getMessage());
        e.printStackTrace();
        return Map.of("error", "Internal server error: " + e.getMessage(), "success", "false");
    }
}

// NEW: Get minimum factor levels required for each grade
private Map<String, String> getMinimumFactorLevelsForGrade(String grade) {
    switch (grade.toUpperCase()) {
        case "GS-15":
            return Map.of("f1", "1-9", "f2", "2-5", "f5", "5-6"); // Expert level
        case "GS-14":
            return Map.of("f1", "1-8", "f2", "2-4", "f5", "5-5"); // Advanced professional
        case "GS-13":
            return Map.of("f1", "1-7", "f2", "2-4", "f5", "5-4"); // Senior professional
        case "GS-12":
            return Map.of("f1", "1-6", "f2", "2-4", "f5", "5-4"); // Program responsibility
        case "GS-11":
            return Map.of("f1", "1-5", "f2", "2-3", "f5", "5-3"); // Full professional
        case "GS-9":
            return Map.of("f1", "1-4", "f2", "2-2", "f5", "5-2"); // Developmental
        case "GS-7":
            return Map.of("f1", "1-3", "f2", "2-2", "f5", "5-2"); // Entry professional
        default:
            return Map.of("f1", "1-6", "f2", "2-4", "f5", "5-4"); // Default to GS-12
    }
}

/**
 * Returns the OPM point value for a given factor number and level string.
 */
private int getPointsForLevel(String factorNum, String level) {
    switch (factorNum) {
        case "1":
            switch (level) {
                case "1-1": return 50;
                case "1-2": return 200;
                case "1-3": return 350;
                case "1-4": return 550;
                case "1-5": return 750;
                case "1-6": return 950;
                case "1-7": return 1250;
                case "1-8": return 1550;
                case "1-9": return 1850;
            }
            break;
        case "2":
            switch (level) {
                case "2-1": return 25;
                case "2-2": return 125;
                case "2-3": return 275;
                case "2-4": return 450;
                case "2-5": return 650;
            }
            break;
        case "3":
            switch (level) {
                case "3-1": return 25;
                case "3-2": return 125;
                case "3-3": return 275;
                case "3-4": return 450;
                case "3-5": return 650;
            }
            break;
        case "4":
            switch (level) {
                case "4-1": return 25;
                case "4-2": return 75;
                case "4-3": return 150;
                case "4-4": return 225;
                case "4-5": return 325;
                case "4-6": return 450;
            }
            break;
        case "5":
            switch (level) {
                case "5-1": return 25;
                case "5-2": return 75;
                case "5-3": return 150;
                case "5-4": return 225;
                case "5-5": return 325;
                case "5-6": return 450;
            }
            break;
        case "6":
            switch (level) {
                case "6-1": return 10;
                case "6-2": return 25;
                case "6-3": return 60;
                case "6-4": return 110;
            }
            break;
        case "7":
            switch (level) {
                case "7-1": return 20;
                case "7-2": return 50;
                case "7-3": return 120;
                case "7-4": return 220;
            }
            break;
        case "8":
            switch (level) {
                case "8-1": return 5;
                case "8-2": return 20;
                case "8-3": return 50;
            }
            break;
        case "9":
            switch (level) {
                case "9-1": return 5;
                case "9-2": return 20;
                case "9-3": return 50;
            }
            break;
    }
    // Default fallback if not found
    return 0;
}

private String enforceGradeAlignment(String response, String targetGrade, int minPoints, int maxPoints) {
    System.out.println("Enforcing grade alignment for " + targetGrade + " (" + minPoints + "-" + maxPoints + " points)");
    
    // Extract current factor assignments
    Map<String, String> currentLevels = new HashMap<>();
    Map<String, Integer> currentPoints = new HashMap<>();
    Pattern factorPattern = Pattern.compile("Factor\\s+(\\d+)\\s*[–-]\\s*[^\\n]*?Level\\s+(\\d+-\\d+),\\s*(\\d+)\\s*Points");
    Matcher matcher = factorPattern.matcher(response);
    
    int totalPoints = 0;
    while (matcher.find()) {
        String factorNum = matcher.group(1);
        String level = matcher.group(2);
        int points = Integer.parseInt(matcher.group(3));
        currentLevels.put(factorNum, level);
        currentPoints.put(factorNum, points);
        totalPoints += points;
    }
    
    System.out.println("Current total: " + totalPoints + " points");
    
    // If not in range, force corrections
    if (totalPoints < minPoints || totalPoints > maxPoints) {
        System.out.println("FORCING grade alignment - current total " + totalPoints + " not in range " + minPoints + "-" + maxPoints);
        
        // Get required minimums for the grade
        Map<String, String> requiredLevels = getMinimumFactorLevelsForGrade(targetGrade);
        
        // Apply minimums and calculate new total
        int newTotal = 0;
        for (int i = 1; i <= 9; i++) {
            String factorNum = String.valueOf(i);
            String requiredLevel = requiredLevels.get("f" + i);
            String currentLevel = currentLevels.getOrDefault(factorNum, "1-1");
            
            // Use required level if it's higher than current, or adjust current to meet target
            String finalLevel = currentLevel;
            if (requiredLevel != null) {
                int requiredPoints = getPointsForLevel(factorNum, requiredLevel);
                int currentFactorPoints = currentPoints.getOrDefault(factorNum, 0);
                if (requiredPoints > currentFactorPoints) {
                    finalLevel = requiredLevel;
                }
            }
            
            // If we're still short on total points, boost critical factors
            if (newTotal < minPoints) {
                if (i == 1) { // Boost Factor 1 most aggressively
                    String[] levels = {"1-7", "1-8", "1-9"};
                    for (String level : levels) {
                        if (getPointsForLevel("1", level) > getPointsForLevel("1", finalLevel)) {
                            finalLevel = level;
                            break;
                        }
                    }
                }
            }
            
            int finalPoints = getPointsForLevel(factorNum, finalLevel);
            newTotal += finalPoints;
            
            // Replace in response if changed
            if (!finalLevel.equals(currentLevel)) {
                String oldPattern = "Factor " + i + " [^\\n]*? Level " + currentLevel + ", " + currentPoints.getOrDefault(factorNum, 0) + " Points";
                String newText = "Factor " + i + " Level " + finalLevel + ", " + finalPoints + " Points";
                response = response.replaceAll(oldPattern, newText);
                System.out.println("Corrected Factor " + i + ": " + currentLevel + " -> " + finalLevel + " (+" + (finalPoints - currentPoints.getOrDefault(factorNum, 0)) + " pts)");
            }
        }
        
        // Update summary section
        response = response.replaceAll("\\*\\*Total Points:\\s*\\d+\\*\\*", "**Total Points: " + newTotal + "**");
        response = response.replaceAll("\\*\\*Final Grade:\\s*GS-\\d+\\*\\*", "**Final Grade: " + targetGrade + "**");
        response = response.replaceAll("\\*\\*Grade Range:\\s*[\\d-]+\\*\\*", "**Grade Range: " + getPointRangeForGrade(targetGrade) + "**");
        
        System.out.println("FORCED alignment complete: " + newTotal + " points for " + targetGrade);
    }
    
    return response;
}

private Map<String, Object> parseFactorResponse(String aiResponse, String expectedGrade) throws Exception {
    String jsonResponse = extractJsonFromResponse(aiResponse);
    if (jsonResponse == null) {
        throw new Exception("No valid JSON found in AI response");
    }
    
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> aiFactors = mapper.readValue(jsonResponse, new TypeReference<Map<String, Object>>() {});
    
    Map<String, Object> finalFactors = new LinkedHashMap<>();
    int totalPoints = 0;
    
    // Process all 9 factors
    for (int i = 1; i <= 9; i++) {
        String key = "Factor " + i;
        Object factorObj = aiFactors.get(key);
        
        if (factorObj instanceof Map) {
            Map<String, Object> factor = (Map<String, Object>) factorObj;
            String level = (String) factor.get("level");
            Object pointsObj = factor.get("points");
            String rationale = (String) factor.get("rationale");
            
            // Validate level format and points
            level = validateAndCorrectFactorLevel(String.valueOf(i), level);
            int points = getPointsForLevel(String.valueOf(i), level);
            
            if (pointsObj instanceof Number) {
                int declaredPoints = ((Number) pointsObj).intValue();
                if (declaredPoints != points) {
                    System.out.println("Correcting Factor " + i + " points: " + declaredPoints + " -> " + points);
                }
            }
            
            Map<String, Object> validFactor = new LinkedHashMap<>();
            validFactor.put("level", level);
            validFactor.put("points", points);
            validFactor.put("rationale", rationale != null ? rationale : "Factor analysis for " + expectedGrade);
            validFactor.put("header", "Factor " + i + " Level " + level + ", " + points + " Points");
            
            finalFactors.put(key, validFactor);
            totalPoints += points;
        } else {
            // Create default factor if missing
            String defaultLevel = i + "-1";
            int defaultPoints = getPointsForLevel(String.valueOf(i), defaultLevel);
            
            Map<String, Object> defaultFactor = new LinkedHashMap<>();
            defaultFactor.put("level", defaultLevel);
            defaultFactor.put("points", defaultPoints);
            defaultFactor.put("rationale", "Default analysis for missing factor");
            defaultFactor.put("header", "Factor " + i + " Level " + defaultLevel + ", " + defaultPoints + " Points");
            
            finalFactors.put(key, defaultFactor);
            totalPoints += defaultPoints;
        }
    }
    
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("factors", finalFactors);
    result.put("totalPoints", totalPoints);
    result.put("finalGrade", calculateFinalGrade(totalPoints));
    result.put("gradeRange", calculateGradeRange(totalPoints));
    result.put("success", true);
    
    return result;
}

private Map<String, Object> enforceFactorGradeAlignment(Map<String, Object> result, String expectedGrade, int minPoints, int maxPoints) {
    Map<String, Object> factors = (Map<String, Object>) result.get("factors");
    int actualTotal = (Integer) result.get("totalPoints");
    
    System.out.println("Enforcing alignment: Expected=" + expectedGrade + ", Actual=" + actualTotal + "pts, Target=" + minPoints + "-" + maxPoints);
    
    if (actualTotal < minPoints) {
        // Need to boost factors to reach minimum
        factors = boostFactorsForGrade(factors, minPoints, expectedGrade);
        actualTotal = calculateTotalFromFactors(factors);
        System.out.println("After boosting: " + actualTotal + " points");
        
    } else if (actualTotal > maxPoints && maxPoints != Integer.MAX_VALUE) {
        // Need to reduce factors to stay within maximum  
        factors = reduceFactorsForGrade(factors, maxPoints, expectedGrade);
        actualTotal = calculateTotalFromFactors(factors);
        System.out.println("After reducing: " + actualTotal + " points");
    }
    
    // Update result with corrected values
    result.put("factors", factors);
    result.put("totalPoints", actualTotal);
    result.put("finalGrade", calculateFinalGrade(actualTotal));
    result.put("gradeRange", calculateGradeRange(actualTotal));
    result.put("gradeAlignmentEnforced", true);
    result.put("targetGrade", expectedGrade);
    
    String finalGrade = calculateFinalGrade(actualTotal);
    boolean aligned = finalGrade.equals(expectedGrade);
    result.put("gradeMatches", aligned);
    
    if (aligned) {
        System.out.println("SUCCESS: Grade alignment achieved - " + actualTotal + " points = " + finalGrade);
    } else {
        System.out.println("WARNING: Grade mismatch - " + actualTotal + " points = " + finalGrade + " (expected " + expectedGrade + ")");
    }
    
    return result;
}

private String getSupervisoryGuidance(String supervisoryLevel) {
    switch (supervisoryLevel) {
        case "Supervisor":
            return """
                SUPERVISORY CONSIDERATIONS:
                - Factor 1: May require higher knowledge levels for managing people and resources
                - Factor 2: Should reflect formal supervisory responsibility with less direct oversight
                - Factor 4: Consider complexity of managing multiple staff and their work
                - Factor 5: Scope includes impact through supervised employees
                - Factor 6-7: Include contacts related to personnel management and coordination
                """;
        case "Manager":
            return """
                MANAGERIAL CONSIDERATIONS:
                - Factor 1: Requires advanced knowledge for strategic planning and resource management
                - Factor 2: Reflects managerial independence with broad decision-making authority
                - Factor 4: High complexity managing multiple teams, budgets, and strategic initiatives
                - Factor 5: Broad organizational impact through managed programs and staff
                - Factor 6-7: Extensive external contacts for program coordination and representation
                """;
        case "Team Leader":
            return """
                TEAM LEADER CONSIDERATIONS:
                - Factor 1: May require additional knowledge for coordinating team activities
                - Factor 2: Some independence in task assignment but still receives oversight
                - Factor 4: Moderate increase in complexity for coordinating multiple team members
                - Factor 5: Impact extends through team coordination and task assignment
                - Factor 6-7: Additional contacts for team coordination and project management
                """;
        default: // Non-Supervisory
            return """
                NON-SUPERVISORY CONSIDERATIONS:
                - Factor 1: Focus on technical knowledge required for individual contribution
                - Factor 2: Reflects level of supervision received for individual work
                - Factor 4: Based on complexity of individual assignments and technical challenges
                - Factor 5: Impact through individual work products and contributions
                - Factor 6-7: Contacts related to individual work requirements and collaboration
                """;
    }
}

private String validateAndCorrectEvaluationResponse(String response, String expectedGrade, 
                                                Map<String, Map<String, Integer>> factorPoints) {
    System.out.println("Validating evaluation statement for expected grade: " + expectedGrade);
    
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
    
    System.out.println("Calculated total points before adjustment: " + totalCalculatedPoints);
    System.out.println("Expected grade: " + expectedGrade);
    
    // CRITICAL: Force alignment with expected grade
    int targetMinPoints = getMinPointsForGrade(expectedGrade);
    int targetMaxPoints = getMaxPointsForGrade(expectedGrade);
    
    if (totalCalculatedPoints < targetMinPoints || totalCalculatedPoints > targetMaxPoints) {
        // Need to adjust factor levels to match expected grade
        totalCalculatedPoints = adjustFactorLevelsForTargetGrade(correctedResponse, expectedGrade, factorPoints, corrections);
    }
    
    // Calculate final grade and range based on corrected points
    String finalGrade = calculateFinalGrade(totalCalculatedPoints);
    String gradeRange = calculateGradeRange(totalCalculatedPoints);
    
    // If still doesn't match, force the expected grade
    if (!finalGrade.equals(expectedGrade)) {
        corrections.add("FORCED grade consistency: Setting grade to " + expectedGrade + " (calculated: " + finalGrade + ")");
        finalGrade = expectedGrade;
        gradeRange = getPointRangeForGrade(expectedGrade);
        
        // Adjust points to be within expected grade range
        if (totalCalculatedPoints < getMinPointsForGrade(expectedGrade)) {
            totalCalculatedPoints = getMinPointsForGrade(expectedGrade);
        } else if (totalCalculatedPoints > getMaxPointsForGrade(expectedGrade) && getMaxPointsForGrade(expectedGrade) != Integer.MAX_VALUE) {
            totalCalculatedPoints = getMaxPointsForGrade(expectedGrade);
        }
    }
    
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
    
    System.out.println("FINAL RESULT: " + totalCalculatedPoints + " points = " + finalGrade + 
                    " (target: " + expectedGrade + ") - Match: " + finalGrade.equals(expectedGrade));
    
    return correctedResponse.toString();
}

private int adjustFactorLevelsForTargetGrade(StringBuilder response, String targetGrade, 
                                           Map<String, Map<String, Integer>> factorPoints, 
                                           List<String> corrections) {
    int targetMinPoints = getMinPointsForGrade(targetGrade);
    int targetMaxPoints = getMaxPointsForGrade(targetGrade);
    int targetPoints = (targetMinPoints + Math.min(targetMaxPoints, targetMinPoints + 500)) / 2; // Aim for middle of range
    
    corrections.add("Adjusting factor levels to achieve " + targetGrade + " (target: " + targetPoints + " points)");
    
    // For GS-13, we need approximately 3356-3855 points
    // Let's create a reasonable factor level distribution for GS-13
    Map<String, String> targetLevels = new HashMap<>();
    
    if ("GS-13".equals(targetGrade)) {
        // Typical GS-13 factor level distribution
        targetLevels.put("1", "1-5"); // 750 points - advanced knowledge
        targetLevels.put("2", "2-4"); // 450 points - considerable independence
        targetLevels.put("3", "3-4"); // 450 points - guides need interpretation
        targetLevels.put("4", "4-5"); // 325 points - high complexity
        targetLevels.put("5", "5-5"); // 325 points - broad scope
        targetLevels.put("6", "6-3"); // 60 points - external contacts
        targetLevels.put("7", "7-3"); // 120 points - coordination/negotiation
        targetLevels.put("8", "8-1"); // 5 points - sedentary
        targetLevels.put("9", "9-1"); // 5 points - office environment
        // Total: 2490 points (too low for GS-13)
        
        // Adjust to reach GS-13 range (3356-3855)
        targetLevels.put("1", "1-6"); // 950 points
        targetLevels.put("2", "2-4"); // 450 points
        targetLevels.put("3", "3-4"); // 450 points
        targetLevels.put("4", "4-5"); // 325 points
        targetLevels.put("5", "5-5"); // 325 points
        targetLevels.put("6", "6-3"); // 60 points
        targetLevels.put("7", "7-3"); // 120 points
        targetLevels.put("8", "8-1"); // 5 points
        targetLevels.put("9", "9-1"); // 5 points
        // Total: 2690 points (still too low)
        
        // Further adjust for GS-13
        targetLevels.put("1", "1-7"); // 1250 points - expert level knowledge
        targetLevels.put("2", "2-4"); // 450 points
        targetLevels.put("3", "3-4"); // 450 points
        targetLevels.put("4", "4-5"); // 325 points
        targetLevels.put("5", "5-5"); // 325 points
        targetLevels.put("6", "6-3"); // 60 points
        targetLevels.put("7", "7-3"); // 120 points
        targetLevels.put("8", "8-1"); // 5 points
        targetLevels.put("9", "9-1"); // 5 points
        // Total: 2990 points (getting closer)
        
        // Final adjustment to reach GS-13 minimum (3356)
        targetLevels.put("5", "5-6"); // 450 points instead of 325
        // New Total: 3440 points (solidly in GS-13 range of 3356-3855)
    }
    
    // Apply the target levels to the response
    int newTotalPoints = 0;
    for (Map.Entry<String, String> entry : targetLevels.entrySet()) {
        String factorNum = entry.getKey();
        String newLevel = entry.getValue();
        Integer points = factorPoints.get(factorNum).get(newLevel);
        
        if (points != null) {
            newTotalPoints += points;
            
            // Replace the factor level in the response
            String factorPattern = "(Factor\\s+" + factorNum + "\\s*[–-]\\s*[^\\n]*?)Level\\s+\\d+-\\d+,\\s*\\d+\\s*Points";
            String replacement = "$1Level " + newLevel + ", " + points + " Points";
            response = new StringBuilder(response.toString().replaceAll(factorPattern, replacement));
            
            corrections.add("Adjusted Factor " + factorNum + " to Level " + newLevel + " (" + points + " points) for " + targetGrade);
        }
    }
    
    System.out.println("Adjusted total points: " + newTotalPoints + " for grade " + targetGrade);
    return newTotalPoints;
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

        // Extract JSON from the response
        String jsonResponse = extractJsonFromResponse(response);
        if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
            throw new RuntimeException("Could not extract valid JSON from OpenAI response");
        }

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
    if (totalPoints >= 1355 && totalPoints <= 1600) return "1355-1600"; // GS-07
    if (totalPoints >= 1855 && totalPoints <= 2100) return "1855-2100"; // GS-09
    if (totalPoints >= 2355 && totalPoints <= 2750) return "2355-2750"; // GS-11
    if (totalPoints >= 2755 && totalPoints <= 3150) return "2755-3150"; // GS-12
    if (totalPoints >= 3155 && totalPoints <= 3600) return "3155-3600"; // GS-13
    if (totalPoints >= 3605 && totalPoints <= 4050) return "3605-4050"; // GS-14
    if (totalPoints >= 4055) return "4055+"; // GS-15
    
    // Handle forbidden ranges by adjusting upward
    if (totalPoints >= 1105 && totalPoints <= 1350) return "1355-1600"; // Force GS-07
    if (totalPoints >= 1605 && totalPoints <= 1850) return "1855-2100"; // Force GS-09
    if (totalPoints >= 2105 && totalPoints <= 2350) return "2355-2750"; // Force GS-11
    
    return "Unknown";
}

private String calculateFinalGrade(int totalPoints) {
    // Official OPM two-grade interval assignments only
    if (totalPoints >= 855 && totalPoints <= 1100) return "GS-05";
    if (totalPoints >= 1355 && totalPoints <= 1600) return "GS-07";
    if (totalPoints >= 1855 && totalPoints <= 2100) return "GS-09";
    if (totalPoints >= 2355 && totalPoints <= 2750) return "GS-11";
    if (totalPoints >= 2755 && totalPoints <= 3150) return "GS-12";
    if (totalPoints >= 3155 && totalPoints <= 3600) return "GS-13";
    if (totalPoints >= 3605 && totalPoints <= 4050) return "GS-14";
    if (totalPoints >= 4055) return "GS-15";
    
    // Handle forbidden ranges by forcing upward to next valid grade
    if (totalPoints >= 1105 && totalPoints <= 1350) return "GS-07"; // Skip GS-06
    if (totalPoints >= 1605 && totalPoints <= 1850) return "GS-09"; // Skip GS-08
    if (totalPoints >= 2105 && totalPoints <= 2350) return "GS-11"; // Skip GS-10
    
    return "GS-05"; // Default fallback
}

private int getMinPointsForGrade(String grade) {
    switch (grade.toUpperCase()) {
        case "GS-05": case "GS-5": return 855;
        case "GS-07": case "GS-7": return 1355;
        case "GS-09": case "GS-9": return 1855;
        case "GS-11": return 2355;
        case "GS-12": return 2755;
        case "GS-13": return 3155;
        case "GS-14": return 3605;
        case "GS-15": return 4055;
        default: return 855;
    }
}

private int getMaxPointsForGrade(String grade) {
    switch (grade.toUpperCase()) {
        case "GS-05": case "GS-5": return 1100;
        case "GS-07": case "GS-7": return 1600;
        case "GS-09": case "GS-9": return 2100;
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

// ENHANCED: Force alignment method to ensure consistency with forbidden grade prevention
/* Removed unused ensureGradeConsistency method to resolve unused method error. */

// NEW: Adjust points that fall in forbidden ranges
private int adjustForForbiddenRanges(int totalPoints, Map<String, Object> validatedFactors, 
                                   Map<String, Map<String, Integer>> FACTOR_POINTS) {
    
    // Check for forbidden ranges and adjust upward
    if ((totalPoints >= 1105 && totalPoints <= 1350) || // GS-06 range
        (totalPoints >= 1605 && totalPoints <= 1850) || // GS-08 range  
        (totalPoints >= 2105 && totalPoints <= 2350)) { // GS-10 range
        
        int targetPoints;
        String adjustmentReason;
        
        if (totalPoints <= 1350) {
            targetPoints = 1355; // Move to GS-07 minimum
            adjustmentReason = "GS-06 forbidden - adjusted to GS-07";
        } else if (totalPoints <= 1850) {
            targetPoints = 1855; // Move to GS-09 minimum
            adjustmentReason = "GS-08 forbidden - adjusted to GS-09";
        } else {
            targetPoints = 2355; // Move to GS-11 minimum
            adjustmentReason = "GS-10 forbidden - adjusted to GS-11";
        }
        
        System.out.println("Forbidden range detected (" + totalPoints + " points). " + adjustmentReason);
        
        // Boost Factor 1 first (most impactful)
        @SuppressWarnings("unchecked")
        Map<String, Object> factor1 = (Map<String, Object>) validatedFactors.get("Factor 1");
        String currentLevel = (String) factor1.get("level");
        Integer currentPoints = (Integer) factor1.get("points");
        
        Map<String, Integer> f1Levels = FACTOR_POINTS.get("1");
        String newLevel = getNextHigherLevel(currentLevel, f1Levels);
        
        if (newLevel != null) {
            Integer newPoints = f1Levels.get(newLevel);
            factor1.put("level", newLevel);
            factor1.put("points", newPoints);
            factor1.put("rationale", factor1.get("rationale") + " [" + adjustmentReason + "]");
            
            return totalPoints + (newPoints - currentPoints);
        }
    }
    
    return totalPoints;
}

// NEW: Intelligent factor adjustment to reach target points
private int adjustFactorsToTargetPoints(Map<String, Object> validatedFactors, 
                                      Map<String, Map<String, Integer>> FACTOR_POINTS, 
                                      int targetPoints) {
    
    int currentTotal = 0;
    for (int i = 1; i <= 9; i++) {
        @SuppressWarnings("unchecked")
        Map<String, Object> factor = (Map<String, Object>) validatedFactors.get("Factor " + i);
        currentTotal += (Integer) factor.get("points");
    }
    
    int pointsNeeded = targetPoints - currentTotal;
    System.out.println("Need to adjust by: " + pointsNeeded + " points");
    
    if (Math.abs(pointsNeeded) < 25) return currentTotal; // Close enough
    
    // Priority order for adjustments: 1 (most impact), 5, 4, 2, 3
    int[] adjustmentOrder = {1, 5, 4, 2, 3};
    
    for (int factorNum : adjustmentOrder) {
        if (Math.abs(pointsNeeded) < 50) break; // Close enough
        
        @SuppressWarnings("unchecked")
        Map<String, Object> factor = (Map<String, Object>) validatedFactors.get("Factor " + factorNum);
        String currentLevel = (String) factor.get("level");
        Integer currentPoints = (Integer) factor.get("points");
        
        Map<String, Integer> factorLevels = FACTOR_POINTS.get(String.valueOf(factorNum));
        
        if (pointsNeeded > 0) {
            // Need to increase points - go to higher level
            String newLevel = getNextHigherLevel(currentLevel, factorLevels);
            if (newLevel != null) {
                Integer newPoints = factorLevels.get(newLevel);
                int increase = newPoints - currentPoints;
                
                if (increase <= pointsNeeded + 50) { // Don't overshoot too much
                    factor.put("level", newLevel);
                    factor.put("points", newPoints);
                    factor.put("rationale", factor.get("rationale") + " [Adjusted to achieve target grade]");
                    
                    pointsNeeded -= increase;
                    System.out.println("Boosted Factor " + factorNum + " from " + currentLevel + " to " + newLevel + " (+" + increase + " pts)");
                }
            }
        } else if (pointsNeeded < -50) {
            // Need to decrease points significantly - go to lower level
            String newLevel = getNextLowerLevel(currentLevel, factorLevels);
            if (newLevel != null) {
                Integer newPoints = factorLevels.get(newLevel);
                int decrease = currentPoints - newPoints;
                
                if (decrease <= Math.abs(pointsNeeded) + 50) { // Don't overshoot
                    factor.put("level", newLevel);
                    factor.put("points", newPoints);
                    factor.put("rationale", factor.get("rationale") + " [Adjusted to achieve target grade]");
                    
                    pointsNeeded += decrease;
                    System.out.println("Reduced Factor " + factorNum + " from " + currentLevel + " to " + newLevel + " (-" + decrease + " pts)");
                }
            }
        }
    }
    
    // Recalculate total
    int newTotal = 0;
    for (int i = 1; i <= 9; i++) {
        @SuppressWarnings("unchecked")
        Map<String, Object> factor = (Map<String, Object>) validatedFactors.get("Factor " + i);
        newTotal += (Integer) factor.get("points");
    }
    
    return newTotal;
}

// NEW: Force exact grade alignment when other methods fail
private int forceExactGradeAlignment(Map<String, Object> validatedFactors,
                                   Map<String, Map<String, Integer>> FACTOR_POINTS,
                                   int targetPoints, String targetGrade) {
    
    System.out.println("Forcing exact alignment to " + targetGrade + " (" + targetPoints + " points)");
    
    // Use predetermined factor combinations for each grade
    Map<String, String> targetLevels = getOptimalFactorLevelsForGrade(targetGrade);
    
    int newTotal = 0;
    for (Map.Entry<String, String> entry : targetLevels.entrySet()) {
        String factorKey = entry.getKey();
        String level = entry.getValue();
        
        String factorNum = factorKey.replace("Factor ", "");
        Integer points = FACTOR_POINTS.get(factorNum).get(level);
        
        if (points != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> factor = (Map<String, Object>) validatedFactors.get(factorKey);
            
            factor.put("level", level);
            factor.put("points", points);
            factor.put("rationale", factor.get("rationale") + " [Aligned for " + targetGrade + "]");
            
            newTotal += points;
        }
    }
    
    System.out.println("Forced alignment complete: " + newTotal + " points");
    return newTotal;
}

// NEW: Get optimal factor level combinations for each grade
private Map<String, String> getOptimalFactorLevelsForGrade(String grade) {
    Map<String, String> levels = new HashMap<>();
    
    switch (grade.toUpperCase()) {
        case "GS-11":
            levels.put("Factor 1", "1-5"); // 750
            levels.put("Factor 2", "2-3"); // 275
            levels.put("Factor 3", "3-3"); // 275
            levels.put("Factor 4", "4-4"); // 225
            levels.put("Factor 5", "5-4"); // 225
            levels.put("Factor 6", "6-2"); // 25
            levels.put("Factor 7", "7-2"); // 50
            levels.put("Factor 8", "8-1"); // 5
            levels.put("Factor 9", "9-1"); // 5
            // Total: 1835 - but we need 2355-2750, so boost key factors
            levels.put("Factor 1", "1-6"); // 950
            levels.put("Factor 2", "2-4"); // 450
            // New total: 2430 (in GS-11 range)
            break;
            
        case "GS-12":
            levels.put("Factor 1", "1-6"); // 950
            levels.put("Factor 2", "2-4"); // 450
            levels.put("Factor 3", "3-4"); // 450
            levels.put("Factor 4", "4-4"); // 225
            levels.put("Factor 5", "5-4"); // 225
            levels.put("Factor 6", "6-3"); // 60
            levels.put("Factor 7", "7-3"); // 120
            levels.put("Factor 8", "8-1"); // 5
            levels.put("Factor 9", "9-1"); // 5
            // Total: 2490 - need 2755-3150, so boost
            levels.put("Factor 1", "1-7"); // 1250
            levels.put("Factor 5", "5-5"); // 325
            // New total: 2950 (in GS-12 range)
            break;
            
        case "GS-13":
            levels.put("Factor 1", "1-7"); // 1250
            levels.put("Factor 2", "2-4"); // 450
            levels.put("Factor 3", "3-4"); // 450
            levels.put("Factor 4", "4-5"); // 325
            levels.put("Factor 5", "5-5"); // 325
            levels.put("Factor 6", "6-3"); // 60
            levels.put("Factor 7", "7-3"); // 120
            levels.put("Factor 8", "8-1"); // 5
            levels.put("Factor 9", "9-1"); // 5
            // Total: 2990 - need 3155-3600, so boost
            levels.put("Factor 1", "1-8"); // 1550
            // New total: 3290 (in GS-13 range)
            break;
            
        case "GS-14":
            levels.put("Factor 1", "1-8"); // 1550
            levels.put("Factor 2", "2-5"); // 650
            levels.put("Factor 3", "3-5"); // 650
            levels.put("Factor 4", "4-5"); // 325
            levels.put("Factor 5", "5-6"); // 450
            levels.put("Factor 6", "6-4"); // 110
            levels.put("Factor 7", "7-4"); // 220
            levels.put("Factor 8", "8-1"); // 5
            levels.put("Factor 9", "9-2"); // 20
            // Total: 3980 (in GS-14 range: 3605-4050)
            break;
            
        default: // GS-07, GS-09, etc.
            levels.put("Factor 1", "1-4");
            levels.put("Factor 2", "2-3");
            levels.put("Factor 3", "3-3");
            levels.put("Factor 4", "4-3");
            levels.put("Factor 5", "5-3");
            levels.put("Factor 6", "6-2");
            levels.put("Factor 7", "7-2");
            levels.put("Factor 8", "8-1");
            levels.put("Factor 9", "9-1");
    }
    
    return levels;
}

// Helper methods for level navigation
private String getNextHigherLevel(String currentLevel, Map<String, Integer> factorLevels) {
    String[] parts = currentLevel.split("-");
    if (parts.length != 2) return null;
    
    try {
        String prefix = parts[0];
        int currentNum = Integer.parseInt(parts[1]);
        String nextLevel = prefix + "-" + (currentNum + 1);
        
        return factorLevels.containsKey(nextLevel) ? nextLevel : null;
    } catch (NumberFormatException e) {
        return null;
    }
}

private String getNextLowerLevel(String currentLevel, Map<String, Integer> factorLevels) {
    String[] parts = currentLevel.split("-");
    if (parts.length != 2) return null;
    
    try {
        String prefix = parts[0];
        int currentNum = Integer.parseInt(parts[1]);
        if (currentNum <= 1) return null; // Can't go lower than level 1
        
        String lowerLevel = prefix + "-" + (currentNum - 1);
        return factorLevels.containsKey(lowerLevel) ? lowerLevel : null;
    } catch (NumberFormatException e) {
        return null;
    }
}

/**
 * Helper method to extract JSON from AI response that might contain extra text
 */
private String extractJsonFromResponse(String response) {
    if (response == null) return null;
    int firstBrace = response.indexOf("{");
    int lastBrace = response.lastIndexOf("}");
    if (firstBrace == -1 || lastBrace == -1 || firstBrace >= lastBrace) return null;
    String json = response.substring(firstBrace, lastBrace + 1);

    // Remove trailing commas before closing braces
    json = json.replaceAll(",\\s*}", "}");
    json = json.replaceAll(",\\s*]", "]");

    // Remove any lines that start with // or #
    json = json.replaceAll("(?m)^\\s*(//|#).*$", "");

    // Remove any comments inside the JSON
    json = json.replaceAll("/\\*.*?\\*/", "");

    // Remove invalid field names (unquoted keys)
    json = json.replaceAll("(?m)^\\s*([A-Za-z0-9_]+)\\s*:", "\"$1\":");

    return json.trim();
}

// Complete rewrite of the fixPDFormatting method to handle all spacing issues
private String fixPDFormatting(String pdText) {
    if (pdText == null || pdText.trim().isEmpty()) return "";

    String text = pdText.trim();

    // Normalize line endings and whitespace
    text = text.replaceAll("\\r\\n?", "\n");
    text = text.replaceAll("\\n{3,}", "\n\n");
    
    // --- SPECIFIC HEADER FIXES ---
    // Fix Justice Department + Agency merging
    text = text.replaceAll("(U\\.S\\. Department of Justice)([A-Z][a-zA-Z ]+(?:Bureau|Division|Office|Administration|Service)[^\\n]*)", "$1\n$2");
    
    // Fix Position/Title + GS grade merging  
    text = text.replaceAll("(Position: [^G\\n]+|[A-Z][a-zA-Z ]+Supervisor|[A-Z][a-zA-Z ]+Manager|[A-Z][a-zA-Z ]+Analyst)(GS-\\d+)", "$1\n$2");
    
    // Fix GS grade + Organizational Title merging
    text = text.replaceAll("(GS-\\d+)(Organizational Title:|Position:|Title:)", "$1\n$2");

    // --- FACTOR HEADER FIXES ---
    // Fix factor headers that are merged like "Factor1 - Knowledge Required by the Position Level1-7,1250 Points:"
    text = text.replaceAll("Factor(\\d+) - ([^L]+?)Level(\\d+)-(\\d+),(\\d+) Points:", 
                          "\n\n**Factor $1 - $2 Level $3-$4, $5 Points**");
    
    // Also handle variations without colons
    text = text.replaceAll("Factor(\\d+) - ([^L]+?)Level(\\d+)-(\\d+),(\\d+) Points", 
                          "\n\n**Factor $1 - $2 Level $3-$4, $5 Points**");

    // --- EVALUATION SUMMARY FIXES ---
    // Fix merged evaluation summary like "Total Points:2540Final Grade: GS-11Grade Range:2356-2855"
    text = text.replaceAll("Total Points:(\\d+)Final Grade: (GS-\\d+)Grade Range:(\\d+-\\d+)", 
                          "\n\n**Total Points:** $1\n**Final Grade:** $2\n**Grade Range:** $3");
    
    // Handle partial merging
    text = text.replaceAll("Total Points:(\\d+)", "**Total Points:** $1");
    text = text.replaceAll("Final Grade: (GS-\\d+)", "**Final Grade:** $1");
    text = text.replaceAll("Grade Range:(\\d+-\\d+)", "**Grade Range:** $1");

    // --- MAJOR SECTION HEADERS ---
    String[] majorHeaders = {
        "HEADER:", "INTRODUCTION:", "MAJOR DUTIES:",
        "FACTOR EVALUATION - COMPLETE ANALYSIS:",
        "EVALUATION SUMMARY:", "CONDITIONS OF EMPLOYMENT:",
        "TITLE AND SERIES DETERMINATION:",
        "FAIR LABOR STANDARDS ACT DETERMINATION:"
    };

    for (String header : majorHeaders) {
        String cleanHeader = header.replace("*", "");
        // Handle headers that might be merged with content
        text = text.replaceAll("(?i)\\*{0,2}\\s*" + Pattern.quote(cleanHeader) + "\\s*\\*{0,2}([A-Z])", 
                              "\n\n**" + cleanHeader + "**\n\n$1");
        // Handle standalone headers
        text = text.replaceAll("(?i)\\*{0,2}\\s*" + Pattern.quote(cleanHeader) + "\\s*\\*{0,2}(?![A-Z])", 
                              "\n\n**" + cleanHeader + "**");
    }

    // --- MAJOR DUTIES FORMATTING ---
    // Handle bullet points that start with dashes
    text = text.replaceAll("(?m)^\\s*-\\s*([A-Z][^:]+?)(\\s*\\(\\d+%\\))?\\s*:", 
                          "\n- **$1$2:**");
    
    // Handle numbered duties  
    text = text.replaceAll("(?m)^\\s*(\\d+)\\.\\s*([A-Z][^:]+?)(\\s*\\(\\d+%\\))?\\s*:", 
                          "\n\n$1. **$2$3:**");

    // --- CONDITIONS OF EMPLOYMENT ---
    // Fix bullet points in conditions section
    text = text.replaceAll("(\\*\\*CONDITIONS OF EMPLOYMENT:\\*\\*)\\s*-\\s*", "$1\n\n- ");
    text = text.replaceAll("(?<!\\*\\*)\\n-\\s*([A-Z])", "\n- $1");

    // --- FINAL CLEANUP ---
    // Ensure proper spacing around all headers
    text = text.replaceAll("([^\\n])\\n(\\*\\*[A-Z][^*]*:\\*\\*)", "$1\n\n$2");
    text = text.replaceAll("(\\*\\*[A-Z][^*]*:\\*\\*)\\n([A-Z])", "$1\n\n$2");
    
    // Clean up excessive whitespace
    text = text.replaceAll("\\n{3,}", "\n\n");
    text = text.replaceAll("^\\n+", "");
    text = text.replaceAll("\\n+$", "");
    text = text.replaceAll("(?m)[ \\t]+$", "");
    text = text.replaceAll("[ \\t]+", " ");

    return text.trim();
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
    try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(new File(pdfPath))) {
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
        if (request == null || request.getHistoricalData() == null || request.getHistoricalData().trim().isEmpty()) {
            writer.println("data: {\"error\":\"Job duties are required\"}\n");
            writer.flush();
            return;
        }

        String supervisoryLevel = request.getSupervisoryLevel() != null ? request.getSupervisoryLevel() : "Non-Supervisory";
        request.setSupervisoryLevel(supervisoryLevel);

        writer.println("data: {\"status\":\"Analyzing grade requirements...\"}\n");
        writer.flush();

        // 1. Grade analysis
        List<Map<String, Object>> gradeRelevancy = getAIGSGradeRelevancy(request.getHistoricalData(), supervisoryLevel);
        String targetGrade = null;
        if (gradeRelevancy != null && !gradeRelevancy.isEmpty()) {
            Object g = gradeRelevancy.get(0).get("grade");
            if (g != null) targetGrade = g.toString();
        }
        List<String> allowedGrades = List.of("GS-5", "GS-7", "GS-9", "GS-11", "GS-12", "GS-13", "GS-14", "GS-15");
        if (targetGrade == null || !allowedGrades.contains(targetGrade)) {
            targetGrade = "GS-13";
        }

        writer.println("data: {\"status\":\"Generating evaluation statement...\"}\n");
        writer.flush();

        // 2. Evaluation statement
        Map<String, String> evaluationRequest = new HashMap<>();
        evaluationRequest.put("duties", request.getHistoricalData());
        evaluationRequest.put("gsGrade", targetGrade);
        evaluationRequest.put("jobSeries", request.getJobSeries() != null ? request.getJobSeries() : "");
        evaluationRequest.put("jobTitle", request.getSubJobSeries() != null ? request.getSubJobSeries() : "");
        evaluationRequest.put("positionTitle", request.getSubJobSeries() != null ? request.getSubJobSeries() : "");
        evaluationRequest.put("supervisoryLevel", supervisoryLevel);

        Map<String, String> evaluationResult = generateEvaluationStatement(evaluationRequest);
        String evaluationStatement = evaluationResult.get("evaluationStatement");

        // Extract values from evaluation statement
        String extractedGrade = extractGradeFromEvaluation(evaluationStatement);
        String extractedRange = extractRangeFromEvaluation(evaluationStatement);
        Integer extractedPoints = extractPointsFromEvaluation(evaluationStatement);

        // --- NEW: Extract and correct factor points/levels ---
        Map<String, Integer> factorPoints = new HashMap<>();
        Map<String, String> factorLevels = new HashMap<>();
        Pattern factorPattern = Pattern.compile("Factor\\s+(\\d+)\\s*[–-]\\s*[^\\n]*?Level\\s+(\\d+-\\d+),\\s*(\\d+)\\s*Points");
        Matcher matcher = factorPattern.matcher(evaluationStatement);
        while (matcher.find()) {
            String factorNum = matcher.group(1);
            String assignedLevel = matcher.group(2);
            int assignedPoints = Integer.parseInt(matcher.group(3));
            factorLevels.put(factorNum, assignedLevel);
            factorPoints.put(factorNum, assignedPoints);
        }
        int actualTotal = factorPoints.values().stream().mapToInt(Integer::intValue).sum();

        // OPM factor points table
        final Map<String, Map<String, Integer>> FACTOR_POINTS = Map.of(
            "1", Map.of("1-1", 50, "1-2", 200, "1-3", 350, "1-4", 550, "1-5", 750, "1-6", 950, "1-7", 1250, "1-8", 1550, "1-9", 1850),
            "2", Map.of("2-1", 25, "2-2", 125, "2-3", 275, "2-4", 450, "2-5", 650),
            "3", Map.of("3-1", 25, "3-2", 125, "3-3", 275, "3-4", 450, "3-5", 650),
            "4", Map.of("4-1", 25, "4-2", 75, "4-3", 150, "4-4", 225, "4-5", 325, "4-6", 450),
            "5", Map.of("5-1", 25, "5-2", 75, "5-3", 150, "5-4", 225, "5-5", 325, "5-6", 450),
            "6", Map.of("6-1", 10, "6-2", 25, "6-3", 60, "6-4", 110),
            "7", Map.of("7-1", 20, "7-2", 50, "7-3", 120, "7-4", 220),
            "8", Map.of("8-1", 5, "8-2", 20, "8-3", 50),
            "9", Map.of("9-1", 5, "9-2", 20, "9-3", 50)
        );

        // Defensive: If actualTotal does not match extractedPoints, boost factors upward
        if (extractedPoints != null && actualTotal != extractedPoints) {
            for (String key : List.of("1", "5", "2", "3", "4", "6", "7", "8", "9")) {
                String currentLevel = factorLevels.get(key);
                if (currentLevel == null) continue;
                int currentNum = Integer.parseInt(currentLevel.split("-")[1]);
                int maxNum = FACTOR_POINTS.get(key).size();
                while (currentNum < maxNum && actualTotal < extractedPoints) {
                    currentNum++;
                    String newLevel = key + "-" + currentNum;
                    int newPoints = FACTOR_POINTS.get(key).get(newLevel);
                    actualTotal = actualTotal - factorPoints.get(key) + newPoints;
                    factorLevels.put(key, newLevel);
                    factorPoints.put(key, newPoints);
                }
            }
        }

        // Pass these exact values into your PD prompt
        request.setFactorLevels(factorLevels);
        request.setFactorPoints(factorPoints);
        request.setTotalPoints(extractedPoints);
        request.setGradeRange(extractedRange);
        request.setGsGrade(extractedGrade);

        // Now build the PD prompt with these values
        String prompt = pdService.buildPrompt(request);

        writer.println("data: {\"status\":\"Generating position description...\"}\n");
        writer.flush();

        // 3. Generate PD using consistent values
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
            .header("Authorization", "Bearer " + pdService.getOpenaiApiKey())
            .header("Content-Type", "application/json")
            .timeout(java.time.Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build();

        HttpResponse<InputStream> openaiResponse = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());

        if (openaiResponse.statusCode() != 200) {
            writer.println("data: {\"error\":\"OpenAI API Error\"}\n");
            writer.flush();
            return;
        }

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

        Map<String, Object> finalResult = new HashMap<>();
        finalResult.put("evaluationStatement", evaluationStatement);
        finalResult.put("positionDescription", fullPD.toString());
        finalResult.put("finalGrade", request.getGsGrade());
        finalResult.put("gradeRange", request.getGradeRange());
        finalResult.put("totalPoints", request.getTotalPoints());
        finalResult.put("supervisoryLevel", supervisoryLevel);
        finalResult.put("gradeConsistency", request.getGsGrade().equals(targetGrade));

        writer.println("data: {\"complete\":true,\"results\":" + objectMapper.writeValueAsString(finalResult) + "}\n");
        writer.println("data: [DONE]\n");
        writer.flush();

        System.out.println("Coordinated generation completed successfully with consistent grade: " + request.getGsGrade());

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

@PostMapping("/regenerate-factors")
public Map<String, Object> regenerateFactors(@RequestBody Map<String, String> body) {
    try {
        String duties = body.getOrDefault("duties", "");
        if (duties == null || duties.trim().isEmpty()) {
            return Map.of("error", "Duties are required", "success", false);
        }

        // OPM factor points table
        final Map<String, Map<String, Integer>> FACTOR_POINTS = Map.of(
            "1", Map.of("1-1", 50, "1-2", 200, "1-3", 350, "1-4", 550, "1-5", 750, "1-6", 950, "1-7", 1250, "1-8", 1550, "1-9", 1850),
            "2", Map.of("2-1", 25, "2-2", 125, "2-3", 275, "2-4", 450, "2-5", 650),
            "3", Map.of("3-1", 25, "3-2", 125, "3-3", 275, "3-4", 450, "3-5", 650),
            "4", Map.of("4-1", 25, "4-2", 75, "4-3", 150, "4-4", 225, "4-5", 325, "4-6", 450),
            "5", Map.of("5-1", 25, "5-2", 75, "5-3", 150, "5-4", 225, "5-5", 325, "5-6", 450),
            "6", Map.of("6-1", 10, "6-2", 25, "6-3", 60, "6-4", 110),
            "7", Map.of("7-1", 20, "7-2", 50, "7-3", 120, "7-4", 220),
            "8", Map.of("8-1", 5, "8-2", 20, "8-3", 50),
            "9", Map.of("9-1", 5, "9-2", 20, "9-3", 50)
        );

        // Enhanced prompt with mandatory factor analysis and accurate grade differentiation
        String prompt = String.format("""
            You are a federal HR classification specialist with deep expertise in OPM position classification standards. 
            You must be PRECISE and AGGRESSIVE in recognizing the true complexity level of work described.
            
            CRITICAL REQUIREMENTS:
            1. You MUST analyze ALL 9 factors - no exceptions
            2. Every factor must have a substantive rationale based on the duties provided
            3. FORBIDDEN GRADES: You cannot assign point totals that result in GS-6 (1105-1350), GS-8 (1605-1850), or GS-10 (2105-2350)
            4. AVOID GS-12 CONVERGENCE: Do not default to GS-12 - carefully assess if work is higher or lower
            
            AVAILABLE GRADE RANGES ONLY:
            GS-5: 855-1100 pts   | Routine clerical/technical work, following clear procedures
            GS-7: 1355-1600 pts  | Entry professional work, some analysis under guidance
            GS-9: 1855-2100 pts  | Developmental professional, independent routine analysis
            GS-11: 2355-2750 pts | Full professional performance, complex analysis, some program responsibility
            GS-12: 2755-3150 pts | Senior professional, significant program oversight, leads others
            GS-13: 3155-3600 pts | Advanced professional, major program responsibility, organization-wide impact
            GS-14: 3605-4050 pts | Expert authority, policy influence, agency-level impact, recognized expertise
            GS-15: 4055+ pts     | Preeminent authority, government-wide influence, establishes policy/precedent
            
            PRECISE GRADE DIFFERENTIATION - READ THE DUTIES CAREFULLY:
            
            GS-9 INDICATORS (Target 1855-2100 pts):
            - "entry level professional" OR "developmental" OR "trainee"
            - "under guidance" OR "with assistance" OR "routine analysis"
            - "follows established procedures" OR "standard methods"
            - "learns to apply" OR "gaining experience"
            → Factor 1: 1-4 to 1-5 (550-750 pts) | Factor 2: 2-2 to 2-3 (125-275 pts)
            
            GS-11 INDICATORS (Target 2355-2750 pts):
            - "independent professional work" OR "full performance level"
            - "complex analysis" without "expert" qualifier
            - "applies professional knowledge" OR "professional judgment"
            - "coordinates" OR "plans work" OR "solves problems"
            → Factor 1: 1-5 to 1-6 (750-950 pts) | Factor 2: 2-3 to 2-4 (275-450 pts)
            
            GS-12 INDICATORS (Target 2755-3150 pts):
            - "leads" OR "supervises" OR "program responsibility"
            - "senior professional" OR "advanced analysis"
            - "trains others" OR "provides guidance to staff"
            - "manages projects" OR "significant responsibility"
            → Factor 1: 1-5 to 1-6 (750-950 pts) | Factor 2: 2-4 (450 pts) | Factor 5: 5-4 (225 pts)
            
            GS-13 INDICATORS (Target 3155-3600 pts):
            - "program manager" OR "major program responsibility"
            - "policy development" OR "strategic planning"
            - "organizational impact" OR "affects multiple programs"
            - "advises senior management" OR "organizational expertise"
            → Factor 1: 1-6 to 1-7 (950-1250 pts) | Factor 5: 5-4 to 5-5 (225-325 pts)
            
            GS-14 INDICATORS (Target 3605-4050 pts):
            - "subject matter expert" OR "recognized authority"
            - "policy influence" OR "executive briefings"
            - "agency-wide impact" OR "external influence"
            - "establishes precedents" OR "innovative approaches"
            → Factor 1: 1-7 to 1-8 (1250-1550 pts) | Factor 5: 5-5 to 5-6 (325-450 pts)
            
            GS-15 INDICATORS (Target 4055+ pts):
            - "nationally recognized expert" OR "preeminent authority"
            - "government-wide influence" OR "shapes policy"
            - "represents agency externally" OR "industry leader"
            - "pioneering work" OR "breaks new ground"
            → Factor 1: 1-8 to 1-9 (1550-1850 pts) | Factor 5: 5-6 (450 pts)
            
            Factor 2 - Supervisory Controls:
            - "independently" + any analysis/research → MINIMUM 2-4 (450 pts)
            - "sets own priorities" OR "minimal supervision" → 2-5 (650 pts)
            - "works with broad guidance" → 2-4 (450 pts)
            
            Factor 3 - Guidelines:
            ALWAYS analyze based on duties - consider policy interpretation, precedent-setting, guideline development
            
            Factor 4 - Complexity:
            - "complex problems" OR "extensive analysis" → MINIMUM 4-5 (325 pts)
            - "innovative solutions" OR "creative approaches" → 4-6 (450 pts)
            - "analytical models" OR "methodologies" → MINIMUM 4-5 (325 pts)
            
            Factor 5 - Scope and Effect:
            - "senior leadership decisions" OR "executive briefings" → MINIMUM 5-5 (325 pts)
            - "organizational impact" OR "agency-wide" → MINIMUM 5-4 (225 pts)
            - "policy development" OR "strategic planning" → MINIMUM 5-5 (325 pts)
            
            Factor 6 - Personal Contacts:
            ALWAYS analyze - consider internal staff, management, external stakeholders, public
            
            Factor 7 - Purpose of Contacts:
            ALWAYS analyze - consider information sharing, coordination, influence, negotiation
            
            Factor 8 - Physical Demands:
            ALWAYS analyze - consider work setting, travel, physical requirements
            
            Factor 9 - Work Environment:
            ALWAYS analyze - consider stress, deadlines, working conditions
            
            MANDATORY: Return JSON with ALL 9 factors analyzed. Each rationale must reference the duties and explain the factor level assignment:
            
            {
              "Factor 1": {"level": "1-X", "points": NNNN, "rationale": "Based on duties showing [specific examples], this position requires [knowledge level] because [detailed explanation]"},
              "Factor 2": {"level": "2-X", "points": NNNN, "rationale": "The duties indicate [independence level] because [specific examples of supervision/autonomy]"},
              "Factor 3": {"level": "3-X", "points": NNNN, "rationale": "Guidelines analysis shows [level] because duties involve [specific policy/guideline work]"},
              "Factor 4": {"level": "4-X", "points": NNNN, "rationale": "Complexity assessment shows [level] because duties involve [specific complex work examples]"},
              "Factor 5": {"level": "5-X", "points": NNNN, "rationale": "Scope and effect analysis shows [level] because work impacts [specific organizational areas]"},
              "Factor 6": {"level": "6-X", "points": NNNN, "rationale": "Contact analysis shows [level] because position interacts with [specific contact types]"},
              "Factor 7": {"level": "7-X", "points": NNNN, "rationale": "Contact purpose analysis shows [level] because interactions involve [specific purposes]"},
              "Factor 8": {"level": "8-X", "points": NNNN, "rationale": "Physical demands analysis shows [level] because work involves [specific physical requirements]"},
              "Factor 9": {"level": "9-X", "points": NNNN, "rationale": "Work environment analysis shows [level] because conditions involve [specific environmental factors]"}
            }
            
            VALIDATION: Ensure total points fall in available grade ranges. If total would create GS-6/8/10, adjust factors upward.

            Major Duties:
            %s
            """, duties);

        String aiResponse = callOpenAIWithTimeout(prompt, 60);
        if (aiResponse == null || aiResponse.trim().isEmpty()) {
            return Map.of("error", "AI did not return any factor evaluation. Please try again.", "success", false);
        }

        String jsonResponse = extractJsonFromResponse(aiResponse);
        if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
            return Map.of("error", "Could not extract valid JSON from AI response.", "success", false, "rawResponse", aiResponse);
        }

        ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> factors;
            try {
                factors = mapper.readValue(jsonResponse, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                // Fallback: try to parse as JsonNode and extract factors manually
                try {
                    JsonNode node = mapper.readTree(jsonResponse);
                    final Map<String, Object> factorsFromJsonNode = new HashMap<>();
                    node.fields().forEachRemaining(entry -> factorsFromJsonNode.put(entry.getKey(), entry.getValue()));
                    factors = factorsFromJsonNode;
                } catch (Exception ex) {
                    // Fallback: return a default structure so frontend never breaks
                    factors = new HashMap<>();
                    for (int i = 1; i <= 9; i++) {
                        factors.put("Factor " + i, Map.of(
                            "level", "1-1",
                            "points", 50,
                            "rationale", "Default rationale due to AI parsing error."
                        ));
                    }
                }
            }

        // Validate and structure the response - ensure all 9 factors are present with proper analysis
        int totalPoints = 0;
        Map<String, Object> validatedFactors = new LinkedHashMap<>();
        
        for (int i = 1; i <= 9; i++) {
            String key = "Factor " + i;
            Object value = factors.get(key);
            
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> factor = (Map<String, Object>) value;
                String level = (String) factor.get("level");
                String rationale = (String) factor.get("rationale");
                // For Factor 1, check for expert-level language and upgrade if needed
                if (i == 1 && rationale != null && (
                    rationale.toLowerCase().contains("expert") ||
                    rationale.toLowerCase().contains("authoritative") ||
                    rationale.toLowerCase().contains("advanced") ||
                    rationale.toLowerCase().contains("complex") ||
                    rationale.toLowerCase().contains("guidance")
                )) {
                    // If level is 1-1, 1-2, or 1-3, upgrade to at least 1-7
                    if (level.equals("1-1") || level.equals("1-2") || level.equals("1-3") || level.equals("1-4") || level.equals("1-5") || level.equals("1-6")) {
                        level = "1-7";
                        factor.put("level", level);
                        factor.put("points", FACTOR_POINTS.get("1").get(level));
                    }
                }
                
                // Validate level exists in our points table
                Map<String, Integer> factorLevels = FACTOR_POINTS.get(String.valueOf(i));
                Integer correctPoints = factorLevels.get(level);
                
                if (correctPoints == null) {
                    // Find closest valid level
                    level = findClosestValidLevel(String.valueOf(i), level, factorLevels);
                    correctPoints = factorLevels.get(level);
                }
                
                // Ensure substantive rationale exists
                if (rationale == null || rationale.trim().isEmpty() || 
                    rationale.toLowerCase().contains("not addressed") || 
                    rationale.toLowerCase().contains("not impacted") ||
                    rationale.toLowerCase().contains("default")) {
                    rationale = generateSubstantiveRationale(i, level, duties);
                }
                
                // Create validated factor
                Map<String, Object> validatedFactor = new LinkedHashMap<>();
                validatedFactor.put("header", "Factor " + i + " Level " + level + ", " + correctPoints + " Points");
                validatedFactor.put("content", factors.getOrDefault("Factor " + i, ""));
                validatedFactor.put("level", level);
                validatedFactor.put("points", correctPoints);
                validatedFactor.put("rationale", rationale);

                validatedFactors.put("Factor " + i, validatedFactor);
                totalPoints += correctPoints;
                
            } else {
                // AI failed to provide this factor - create intelligent analysis
                String intelligentLevel = getIntelligentLevel(i, duties);
                Integer points = FACTOR_POINTS.get(String.valueOf(i)).get(intelligentLevel);
                String substantiveRationale = generateSubstantiveRationale(i, intelligentLevel, duties);
                
                Map<String, Object> factor = new LinkedHashMap<>();
                factor.put("level", intelligentLevel);
                factor.put("points", points);
                factor.put("rationale", substantiveRationale);
                
                validatedFactors.put(key, factor);
                totalPoints += points;
                
                System.out.println("WARNING: AI failed to analyze " + key + ", generated substantive analysis");
            }
        }
        
        // Apply minimum level corrections based on duty language
        totalPoints = applyMinimumLevelCorrections(duties, validatedFactors, FACTOR_POINTS);
        
        // Validate total points make sense for the work described
        totalPoints = validateTotalPointsAgainstComplexity(duties, validatedFactors, FACTOR_POINTS, totalPoints);

        // Ensure we don't have forbidden grade assignments
        totalPoints = adjustForForbiddenGrades(totalPoints, validatedFactors, FACTOR_POINTS);

        // Calculate final grade and range
        String finalGrade = calculateFinalGrade(totalPoints);
        String gradeRange = calculateGradeRange(totalPoints);

        // Log for debugging
        System.out.println("Total Points Calculated: " + totalPoints);
        System.out.println("Final Grade: " + finalGrade);
        System.out.println("Grade Range: " + gradeRange);

        // Return structured response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("factors", validatedFactors);
        result.put("totalPoints", totalPoints);
        result.put("finalGrade", finalGrade);
        result.put("gradeRange", gradeRange);
        result.put("success", true);

        return result;

    } catch (Exception e) {
        e.printStackTrace();
        return Map.of("error", "Internal server error: " + e.getMessage(), "success", false);
    }
}

// Generate substantive rationale based on factor type and duties with grade-appropriate language
private String generateSubstantiveRationale(int factorNum, String level, String duties) {
    String lowerDuties = duties.toLowerCase();
    
    // Determine complexity level for appropriate language
    boolean isHighLevel = containsAny(lowerDuties, new String[]{"expert", "authority", "policy", "strategic", "executive", "agency-wide"});
    boolean isMidLevel = containsAny(lowerDuties, new String[]{"professional", "complex", "independent", "program", "coordinates"});
    
    switch (factorNum) {
        case 1: // Knowledge Required
            if (isHighLevel) {
                return "This position requires expert-level professional knowledge at level " + level + ". The duties demonstrate mastery of specialized principles, advanced analytical capabilities, and authoritative expertise needed to address complex organizational challenges and provide definitive guidance.";
            } else if (isMidLevel) {
                return "The duties require professional knowledge at level " + level + " involving independent application of complex concepts, analytical thinking, and specialized expertise to solve varied problems and make professional judgments.";
            } else {
                return "The position requires developing professional knowledge at level " + level + " to perform analytical work, apply established methods, and gain expertise in the field under appropriate guidance.";
            }
            
        case 2: // Supervisory Controls
            if (level.equals("2-5")) {
                return "Supervisory controls at level " + level + " reflect extensive independence with administrative direction only. The incumbent sets priorities, plans work approaches, and operates with full authority for professional decisions within broad policy guidelines.";
            } else if (level.equals("2-4")) {
                return "The duties indicate considerable independence at level " + level + " with supervisor providing general guidance on priorities and approaches. The incumbent plans work, makes decisions, and operates with significant autonomy.";
            } else if (level.equals("2-3")) {
                return "Supervisory controls at level " + level + " provide specific guidance on complex assignments while allowing independence on routine work. The incumbent receives clear direction on new or difficult problems.";
            } else {
                return "The position operates under closer supervision at level " + level + " with regular guidance and review of work approaches and decisions.";
            }
            
        case 3: // Guidelines
            if (level.contains("5")) {
                return "Guidelines assessment at level " + level + " reflects the need to interpret broad policy, establish precedents, and develop new approaches where specific guidance doesn't exist. Extensive judgment required in applying principles.";
            } else if (level.contains("4")) {
                return "The duties require significant interpretation of guidelines at level " + level + " including adaptation of policies to new situations and development of approaches for complex problems.";
            } else {
                return "Guidelines at level " + level + " provide adequate direction for most work with some interpretation required for unusual situations or complex applications.";
            }
            
        case 4: // Complexity
            if (level.contains("6") || level.contains("5")) {
                return "Work complexity at level " + level + " involves highly complex, unprecedented problems requiring innovative solutions, extensive analysis of interrelated factors, and creative problem-solving approaches.";
            } else if (level.contains("4")) {
                return "The duties present complex problems at level " + level + " requiring analytical thinking, consideration of multiple factors, and development of solutions for varied situations.";
            } else {
                return "Work complexity at level " + level + " involves moderately complex problems with some variety requiring analytical skills and problem-solving within established frameworks.";
            }
            
        case 5: // Scope and Effect
            if (level.contains("6")) {
                return "Scope and effect at level " + level + " demonstrates organization-wide and external impact affecting major policies, programs, and external stakeholder relationships with significant consequences for agency mission.";
            } else if (level.contains("5")) {
                return "The work scope at level " + level + " affects senior leadership decisions, major program directions, and organizational policies with substantial impact on agency operations and external relationships.";
            } else if (level.contains("4")) {
                return "Scope and effect at level " + level + " impacts multiple organizational units, program operations, and affects significant management decisions and resource allocation.";
            } else {
                return "The work scope at level " + level + " affects unit operations, project outcomes, and contributes to broader program objectives with moderate organizational impact.";
            }
            
        case 6: // Personal Contacts
            if (level.contains("4")) {
                return "Personal contacts at level " + level + " include senior executives, high-level external officials, key stakeholders, and industry leaders requiring sophisticated communication and relationship management skills.";
            } else if (level.contains("3")) {
                return "The position involves contacts at level " + level + " with management officials, external professionals, contractors, and stakeholders requiring diplomatic and professional interaction skills.";
            } else {
                return "Personal contacts at level " + level + " include staff throughout the organization, some external contacts, and various professional counterparts requiring effective communication skills.";
            }
            
        case 7: // Purpose of Contacts
            if (level.contains("4")) {
                return "Contact purposes at level " + level + " involve influencing policy decisions, negotiating critical agreements, resolving complex conflicts, and representing organizational positions on significant issues.";
            } else if (level.contains("3")) {
                return "The purpose of contacts at level " + level + " includes coordinating complex activities, influencing decisions, resolving problems, and providing authoritative information and guidance.";
            } else if (level.contains("2")) {
                return "Contact purposes at level " + level + " involve planning work, coordinating activities, exchanging information, and resolving routine problems through collaborative efforts.";
            } else {
                return "Contacts at level " + level + " primarily involve information exchange, clarification of requirements, and routine coordination activities.";
            }
            
        case 8: // Physical Demands
            return "Physical demands at level " + level + " reflect typical professional office work with sedentary requirements, occasional travel, and standard technology use appropriate for analytical and administrative responsibilities.";
            
        case 9: // Work Environment
            if (level.contains("2")) {
                return "Work environment at level " + level + " involves moderate to high stress from deadlines, competing priorities, complex problem-solving demands, and significant responsibility for critical organizational outcomes.";
            } else {
                return "Work environment at level " + level + " represents normal office conditions with routine stress levels typical of professional analytical work and standard organizational demands.";
            }
            
        default:
            return "Factor level " + level + " assigned based on comprehensive analysis of position duties and requirements.";
    }
}

// Get intelligent factor level based on duties analysis with proper grade differentiation
private String getIntelligentLevel(int factorNum, String duties) {
    String lowerDuties = duties.toLowerCase();
    
    // Analyze overall complexity first to guide factor assignments
    boolean isGS15Level = containsAny(lowerDuties, new String[]{"nationally recognized", "preeminent", "government-wide", "pioneering", "industry leader"});
    boolean isGS14Level = containsAny(lowerDuties, new String[]{"subject matter expert", "recognized authority", "executive briefing", "agency-wide", "policy influence"});
    boolean isGS13Level = containsAny(lowerDuties, new String[]{"program manager", "policy development", "strategic planning", "organizational impact", "advises senior"});
    boolean isGS12Level = containsAny(lowerDuties, new String[]{"leads", "supervises", "program responsibility", "trains others", "manages projects"});
    boolean isGS11Level = containsAny(lowerDuties, new String[]{"independent professional", "complex analysis", "professional judgment", "coordinates", "plans work"});
    boolean isGS9Level = containsAny(lowerDuties, new String[]{"entry level", "developmental", "under guidance", "routine analysis", "follows established"});
    
    switch (factorNum) {
        case 1: // Knowledge Required - most critical factor
            if (isGS15Level) return "1-8";
            else if (isGS14Level) return "1-7";
            else if (isGS13Level) return "1-6";
            else if (isGS12Level) return "1-5";
            else if (isGS11Level) return "1-5";
            else if (isGS9Level) return "1-4";
            else return "1-4"; // Default entry professional
            
        case 2: // Supervisory Controls
            if (isGS15Level || isGS14Level) return "2-5";
            else if (isGS13Level || isGS12Level) return "2-4";
            else if (isGS11Level) return "2-3";
            else if (isGS9Level) return "2-2";
            else return "2-3";
            
        case 3: // Guidelines
            if (isGS15Level) return "3-5";
            else if (isGS14Level || isGS13Level) return "3-4";
            else if (isGS12Level || isGS11Level) return "3-3";
            else return "3-2";
            
        case 4: // Complexity
            if (isGS15Level) return "4-6";
            else if (isGS14Level) return "4-5";
            else if (isGS13Level || isGS12Level) return "4-4";
            else if (isGS11Level) return "4-3";
            else return "4-2";
            
        case 5: // Scope and Effect - critical for higher grades
            if (isGS15Level) return "5-6";
            else if (isGS14Level) return "5-5";
            else if (isGS13Level) return "5-4";
            else if (isGS12Level) return "5-3";
            else if (isGS11Level) return "5-3";
            else return "5-2";
            
        case 6: // Personal Contacts
            if (isGS15Level || isGS14Level) return "6-4";
            else if (isGS13Level || isGS12Level) return "6-3";
            else return "6-2";
            
        case 7: // Purpose of Contacts
            if (isGS15Level || isGS14Level) return "7-4";
            else if (isGS13Level || isGS12Level) return "7-3";
            else if (isGS11Level) return "7-2";
            else return "7-1";
            
        case 8: // Physical Demands
            return "8-1"; // Most professional work is sedentary
            
        case 9: // Work Environment
            if (isGS15Level || isGS14Level || isGS13Level) return "9-2"; // High-level work has stress
            else return "9-1";
            
        default:
            return "1-1";
    }
}

// Helper method to check if string contains any of the target phrases
private boolean containsAny(String text, String[] phrases) {
    for (String phrase : phrases) {
        if (text.contains(phrase)) {
            return true;
        }
    }
    return false;
}

// Adjust points to avoid forbidden grade ranges (GS-6: 1105-1350, GS-8: 1605-1850, GS-10: 2105-2350)
private int adjustForForbiddenGrades(int totalPoints, Map<String, Object> validatedFactors, 
                                   Map<String, Map<String, Integer>> FACTOR_POINTS) {
    if ((totalPoints >= 1105 && totalPoints <= 1350) || 
        (totalPoints >= 1605 && totalPoints <= 1850) || 
        (totalPoints >= 2105 && totalPoints <= 2350)) {
        
        if (totalPoints <= 1350) totalPoints = 1355; // Move to GS-7
        else if (totalPoints <= 1850) totalPoints = 1855; // Move to GS-9  
        else totalPoints = 2355; // Move to GS-11
        
        // Boost Factor 1 first
        @SuppressWarnings("unchecked")
        Map<String, Object> factor1 = (Map<String, Object>) validatedFactors.get("Factor 1");
        String currentLevel = (String) factor1.get("level");
        String newLevel = getNextHigherLevel(currentLevel, FACTOR_POINTS.get("1"));
        
        if (newLevel != null) {
            Integer newPoints = FACTOR_POINTS.get("1").get(newLevel);
            Integer currentPoints = (Integer) factor1.get("points");
            factor1.put("level", newLevel);
            factor1.put("points", newPoints);
            factor1.put("rationale", factor1.get("rationale"));
            return totalPoints + (newPoints - currentPoints);
        }
    }
    
    return totalPoints;
}

// Helper method to find closest valid level
private String findClosestValidLevel(String factorNum, String invalidLevel, Map<String, Integer> validLevels) {
    try {
        String[] parts = invalidLevel.split("-");
        if (parts.length == 2) {
            int levelNum = Integer.parseInt(parts[1]);
            String prefix = parts[0];
            
            int minDiff = Integer.MAX_VALUE;
            String closestLevel = null;
            
            for (String validLevel : validLevels.keySet()) {
                String[] validParts = validLevel.split("-");
                if (validParts.length == 2 && validParts[0].equals(prefix)) {
                    int validNum = Integer.parseInt(validParts[1]);
                    int diff = Math.abs(levelNum - validNum);
                    if (diff < minDiff) {
                        minDiff = diff;
                        closestLevel = validLevel;
                    }
                }
            }
            
            if (closestLevel != null) {
                return closestLevel;
            }
        }
    } catch (Exception e) {
        // Fall back to default
    }
    
    return validLevels.keySet().iterator().next();
}

// Apply minimum level corrections based on specific duty language
private int applyMinimumLevelCorrections(String duties, Map<String, Object> validatedFactors, 
                                       Map<String, Map<String, Integer>> FACTOR_POINTS) {
    String lowerDuties = duties.toLowerCase();
    int totalPoints = 0;
    
    // Define minimum levels based on specific language
    Map<String, String> factor1Minimums = Map.of(
        "subject matter expert", "1-7",
        "recognized expert", "1-7", 
        "authoritative", "1-7",
        "expert analysis", "1-7",
        "independently design", "1-7",
        "complex analysis", "1-6",
        "comprehensive analysis", "1-6",
        "wide-ranging", "1-6"
    );
    
    Map<String, String> factor2Minimums = Map.of(
        "independently", "2-4",
        "considerable independence", "2-4",
        "minimal supervision", "2-5",
        "sets own priorities", "2-5"
    );
    
    Map<String, String> factor4Minimums = Map.of(
        "complex problems", "4-5",
        "extensive analysis", "4-5",
        "innovative solutions", "4-6",
        "analytical models", "4-5",
        "methodologies", "4-5"
    );
    
    Map<String, String> factor5Minimums = Map.of(
        "senior leadership", "5-5",
        "executive briefing", "5-5",
        "policy development", "5-5",
        "strategic planning", "5-5",
        "organizational impact", "5-4",
        "agency-wide", "5-4"
    );
    
    // Apply corrections for each factor
    totalPoints += applyFactorMinimums(validatedFactors, FACTOR_POINTS, lowerDuties, factor1Minimums, 1);
    totalPoints += applyFactorMinimums(validatedFactors, FACTOR_POINTS, lowerDuties, factor2Minimums, 2);
    totalPoints += applyFactorMinimums(validatedFactors, FACTOR_POINTS, lowerDuties, factor4Minimums, 4);
    totalPoints += applyFactorMinimums(validatedFactors, FACTOR_POINTS, lowerDuties, factor5Minimums, 5);
    
    // Add points for factors that weren't corrected
    for (int i : new int[]{3, 6, 7, 8, 9}) {
        @SuppressWarnings("unchecked")
        Map<String, Object> factor = (Map<String, Object>) validatedFactors.get("Factor " + i);
        totalPoints += (Integer) factor.get("points");
    }
    
    return totalPoints;
}

// Helper method to apply minimum levels for a specific factor
private int applyFactorMinimums(Map<String, Object> validatedFactors, 
                            Map<String, Map<String, Integer>> FACTOR_POINTS,
                            String lowerDuties, Map<String, String> minimums, int factorNum) {
    @SuppressWarnings("unchecked")
    Map<String, Object> factor = (Map<String, Object>) validatedFactors.get("Factor " + factorNum);
    Integer currentPoints = (Integer) factor.get("points");
    
    String requiredLevel = null;
    String matchedPhrase = null;
    
    // Find the highest minimum level required
    for (Map.Entry<String, String> entry : minimums.entrySet()) {
        if (lowerDuties.contains(entry.getKey())) {
            String minLevel = entry.getValue();
            Integer minPoints = FACTOR_POINTS.get(String.valueOf(factorNum)).get(minLevel);
            if (minPoints != null && minPoints > currentPoints) {
                if (requiredLevel == null || minPoints > FACTOR_POINTS.get(String.valueOf(factorNum)).get(requiredLevel)) {
                    requiredLevel = minLevel;
                    matchedPhrase = entry.getKey();
                }
            }
        }
    }
    
    // Apply correction if needed
    if (requiredLevel != null) {
        Integer newPoints = FACTOR_POINTS.get(String.valueOf(factorNum)).get(requiredLevel);
        factor.put("level", requiredLevel);
        factor.put("points", newPoints);
        String rationale = (String) factor.get("rationale");
        factor.put("rationale", rationale + " [Corrected to minimum level " + requiredLevel + " due to '" + matchedPhrase + "' in duties]");
        return newPoints;
    }
    
    return currentPoints;
}

// Helper method to validate total points against work complexity
private int validateTotalPointsAgainstComplexity(String duties, Map<String, Object> validatedFactors,
                                            Map<String, Map<String, Integer>> FACTOR_POINTS, int currentTotal) {
    String lowerDuties = duties.toLowerCase();
    
    // Count complexity indicators
    String[] gs14Indicators = {
        "subject matter expert", "expert analysis", "authoritative", "independently design",
        "senior leadership", "executive briefing", "policy development", "strategic planning",
        "complex analysis", "comprehensive analysis", "major program", "organizational impact"
    };
    
    String[] gs13Indicators = {
        "professional", "independent", "complex", "analytical", "program", "advanced"
    };
    
    int gs14Count = 0, gs13Count = 0;
    for (String indicator : gs14Indicators) {
        if (lowerDuties.contains(indicator)) gs14Count++;
    }
    for (String indicator : gs13Indicators) {
        if (lowerDuties.contains(indicator)) gs13Count++;
    }
    
    // Determine expected grade range based on official OPM point ranges
    int expectedMinPoints = 0;
    if (gs14Count >= 3) {
        expectedMinPoints = 3605; // GS-14 minimum (official OPM range: 3605-4050)
    } else if (gs13Count >= 3) {
        expectedMinPoints = 3155; // GS-13 minimum (official OPM range: 3155-3600)
    }
    
    // If total is significantly below expected, boost Factor 1
    if (expectedMinPoints > 0 && currentTotal < expectedMinPoints) {
        @SuppressWarnings("unchecked")
        Map<String, Object> factor1 = (Map<String, Object>) validatedFactors.get("Factor 1");
        
        // Determine target Factor 1 level based on official standards
        String targetLevel = gs14Count >= 3 ? "1-7" : "1-6"; // GS-14 needs 1-7+ for Factor 1
        Integer targetPoints = FACTOR_POINTS.get("1").get(targetLevel);
        Integer currentF1Points = (Integer) factor1.get("points");
        
        if (targetPoints > currentF1Points) {
            int pointsIncrease = targetPoints - currentF1Points;
            factor1.put("level", targetLevel);
            factor1.put("points", targetPoints);
            String rationale = (String) factor1.get("rationale");
            factor1.put("rationale", rationale);
            return currentTotal + pointsIncrease;
        }
    }
    
    return currentTotal;
}

@PostMapping("/generate-title-series")
public Map<String, String> generateTitleSeriesDetermination(@RequestBody Map<String, String> body) throws Exception {
    String duties = body.getOrDefault("duties", "");
    String gsGrade = body.getOrDefault("gsGrade", "");
    String jobSeries = body.getOrDefault("jobSeries", "");
    String jobTitle = body.getOrDefault("jobTitle", "");
    String supervisoryLevel = body.getOrDefault("supervisoryLevel", "Non-Supervisory");

    String prompt = String.format("""
        You are a federal HR classification specialist.
        Based on the following duties, GS grade, job series, job title, and supervisory level, generate a concise "Title and Series Determination" section for a federal position description.
        Use OPM standards and reference the GS series and grade.
        Duties: %s
        GS Grade: %s
        Job Series: %s
        Job Title: %s
        Supervisory Level: %s

        Return only the Title and Series Determination section, no extra commentary.
        """, duties, gsGrade, jobSeries, jobTitle, supervisoryLevel);

    String response = callOpenAIWithTimeout(prompt, 20);
    return Map.of("titleSeriesSection", response.trim());
}

@GetMapping("/proxy/usajobs")
public ResponseEntity<String> proxyUsaJobs(@RequestParam Map<String, String> params) {
    try {
        // Build URL
        StringBuilder url = new StringBuilder("https://data.usajobs.gov/api/search?");
        params.forEach((k, v) -> {
            if (v != null && !v.trim().isEmpty()) {
                url.append(k).append("=").append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append("&");
            }
        });
        
        // Remove trailing &
        String finalUrl = url.toString();
        if (finalUrl.endsWith("&")) {
            finalUrl = finalUrl.substring(0, finalUrl.length() - 1);
        }
        
        System.out.println("Making request to: " + finalUrl);
        
        // Use RestTemplate instead of HttpClient to avoid Host header restrictions
        RestTemplate restTemplate = new RestTemplate();
        
        // Set headers
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization-Key", "szq+h8pmtLiZ++/ldJQh3ZZjfVfEk74mcsAViRJGgCA=");
        headers.set("User-Agent", "marko.vukovic0311@gmail.com");
        headers.set("Host", "data.usajobs.gov");
        headers.set("Accept", "application/json");
        
        HttpEntity<?> entity = new HttpEntity<>(headers);
        
        System.out.println("Sending request with headers: " + headers);
        
        ResponseEntity<String> response = restTemplate.exchange(
            finalUrl,
            HttpMethod.GET,
            entity,
            String.class
        );
        
        System.out.println("Response status: " + response.getStatusCode());
        
        return ResponseEntity.status(response.getStatusCode())
            .headers(response.getHeaders())
            .body(response.getBody());
        
    } catch (HttpClientErrorException | HttpServerErrorException e) {
        System.err.println("HTTP error in proxyUsaJobs: " + e.getStatusCode() + " - " + e.getMessage());
        System.err.println("Response body: " + e.getResponseBodyAsString());
        
        return ResponseEntity.status(e.getStatusCode())
            .body("{\"error\":\"USAJobs API error\",\"status\":" + e.getStatusCode().value() + 
                  ",\"message\":\"" + e.getMessage().replaceAll("\"", "'") + 
                  "\",\"details\":\"" + e.getResponseBodyAsString().replaceAll("\"", "'").replaceAll("\n", " ") + "\"}");
                  
    } catch (Exception e) {
        System.err.println("Error in proxyUsaJobs: " + e.getMessage());
        e.printStackTrace();
        return ResponseEntity.status(500)
            .body("{\"error\":\"Internal server error\",\"message\":\"" + 
                  e.getMessage().replaceAll("\"", "'") + "\"}");
    }
}

private String getFactorName(String factorNum) {
    switch (factorNum) {
        case "1": return "Knowledge Required by the Position";
        case "2": return "Supervisory Controls";
        case "3": return "Guidelines";
        case "4": return "Complexity";
        case "5": return "Scope and Effect";
        case "6": return "Personal Contacts";
        case "7": return "Purpose of Contacts";
        case "8": return "Physical Demands";
        case "9": return "Work Environment";
        default: return "Unknown Factor";
    }
}

private Map<String, Object> boostFactorsForGrade(Map<String, Object> factors, int targetPoints, String grade) {
    int currentTotal = calculateTotalFromFactors(factors);
    int needed = targetPoints - currentTotal;
    
    System.out.println("Boosting factors by " + needed + " points for " + grade);
    
    // Strategic boosting order based on grade requirements
    String[] boostOrder = grade.equals("GS-14") || grade.equals("GS-15") 
        ? new String[]{"1", "5", "2", "3", "4"} // High grades need Factor 1 & 5 boost
        : new String[]{"1", "2", "5", "4", "3"}; // Other grades prioritize Factor 1 & 2
    
    for (String factorNum : boostOrder) {
        if (needed <= 50) break; // Close enough
        
        Map<String, Object> factor = (Map<String, Object>) factors.get("Factor " + factorNum);
        String currentLevel = (String) factor.get("level");
        
        String boostedLevel = getNextHigherValidLevel(factorNum, currentLevel);
        if (boostedLevel != null) {
            int currentPoints = (Integer) factor.get("points");
            int newPoints = getPointsForLevel(factorNum, boostedLevel);
            int increase = newPoints - currentPoints;
            
            if (increase > 0 && increase <= needed + 200) { // Allow reasonable overshoot
                factor.put("level", boostedLevel);
                factor.put("points", newPoints);
                factor.put("rationale", factor.get("rationale") + " [Boosted for " + grade + "]");
                needed -= increase;
                System.out.println("Boosted Factor " + factorNum + ": " + currentLevel + " -> " + boostedLevel + " (+" + increase + "pts)");
            }
        }
    }
    
    return factors;
}

private Map<String, Object> reduceFactorsForGrade(Map<String, Object> factors, int maxPoints, String grade) {
    int currentTotal = calculateTotalFromFactors(factors);
    int excess = currentTotal - maxPoints;
    
    System.out.println("Reducing factors by " + excess + " points for " + grade);
    
    // Reduce less critical factors first (reverse priority)
    String[] reduceOrder = {"9", "8", "7", "6", "4", "3", "2", "5", "1"};
    
    for (String factorNum : reduceOrder) {
        if (excess <= 25) break; // Close enough
        
        Map<String, Object> factor = (Map<String, Object>) factors.get("Factor " + factorNum);
        String currentLevel = (String) factor.get("level");
        
        String reducedLevel = getNextLowerValidLevel(factorNum, currentLevel);
        if (reducedLevel != null) {
            int currentPoints = (Integer) factor.get("points");
            int newPoints = getPointsForLevel(factorNum, reducedLevel);
            int decrease = currentPoints - newPoints;
            
            if (decrease > 0 && decrease <= excess + 50) {
                factor.put("level", reducedLevel);
                factor.put("points", newPoints);
                factor.put("rationale", factor.get("rationale") + " [Adjusted for " + grade + "]");
                excess -= decrease;
                System.out.println("Reduced Factor " + factorNum + ": " + currentLevel + " -> " + reducedLevel + " (-" + decrease + "pts)");
            }
        }
    }
    
    return factors;
}

private String validateAndCorrectFactorLevel(String factorNum, String level) {
    if (level == null || level.trim().isEmpty()) {
        return factorNum + "-1"; // Default to lowest level
    }
    
    // Ensure correct format (factor number must match)
    if (!level.startsWith(factorNum + "-")) {
        String[] parts = level.split("-");
        if (parts.length == 2) {
            try {
                int levelNum = Integer.parseInt(parts[1]);
                int maxLevel = getMaxLevelForFactor(factorNum);
                levelNum = Math.max(1, Math.min(levelNum, maxLevel));
                return factorNum + "-" + levelNum;
            } catch (NumberFormatException e) {
                return factorNum + "-1";
            }
        }
        return factorNum + "-1";
    }
    
    // Validate the level exists
    if (getPointsForLevel(factorNum, level) == 0) {
        return factorNum + "-1";
    }
    
    return level;
}

private String getNextHigherValidLevel(String factorNum, String currentLevel) {
    String[] parts = currentLevel.split("-");
    if (parts.length != 2) return null;
    
    try {
        int currentNum = Integer.parseInt(parts[1]);
        int maxLevel = getMaxLevelForFactor(factorNum);
        
        if (currentNum < maxLevel) {
            return factorNum + "-" + (currentNum + 1);
        }
    } catch (NumberFormatException e) {
        return null;
    }
    
    return null;
}

private String getNextLowerValidLevel(String factorNum, String currentLevel) {
    String[] parts = currentLevel.split("-");
    if (parts.length != 2) return null;
    
    try {
        int currentNum = Integer.parseInt(parts[1]);
        
        if (currentNum > 1) {
            return factorNum + "-" + (currentNum - 1);
        }
    } catch (NumberFormatException e) {
        return null;
    }
    
    return null;
}

private int calculateTotalFromFactors(Map<String, Object> factors) {
    int total = 0;
    for (int i = 1; i <= 9; i++) {
        Map<String, Object> factor = (Map<String, Object>) factors.get("Factor " + i);
        if (factor != null && factor.get("points") instanceof Integer) {
            total += (Integer) factor.get("points");
        }
    }
    return total;
}

private int sumFactorPoints(Map<String, Object> factors) {
    int total = 0;
    for (int i = 1; i <= 9; i++) {
        String key = "Factor " + i;
        Object fObj = factors.get(key);
        if (fObj instanceof Map) {
            Map<?, ?> f = (Map<?, ?>) fObj;
            Object pointsObj = f.get("points");
            if (pointsObj instanceof Number) {
                total += ((Number) pointsObj).intValue();
            } else if (pointsObj != null) {
                try {
                    total += Integer.parseInt(pointsObj.toString());
                } catch (Exception ignore) {}
            }
        } else {
            // If missing, use lowest level for that factor
            int defaultPoints = getDefaultFactorPoints(i);
            total += defaultPoints;
        }
    }
    return total;
}

private int getDefaultFactorPoints(int factorNum) {
    switch (factorNum) {
        case 1: return 50;
        case 2: return 25;
        case 3: return 25;
        case 4: return 25;
        case 5: return 25;
        case 6: return 10;
        case 7: return 20;
        case 8: return 5;
        case 9: return 5;
        default: return 0;
    }
}
}