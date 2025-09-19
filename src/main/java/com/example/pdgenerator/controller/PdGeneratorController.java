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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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

            OpenAIRequest openaiRequest = new OpenAIRequest("gpt-3.5-turbo", messages, true);
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

    private final Map<String, String> analysisCache = new ConcurrentHashMap<>();

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
     * Get AI analysis with caching for better performance
     */
    private String getCachedAIAnalysis(String duties) throws Exception {
        String cacheKey = String.valueOf(duties.hashCode());
        
        if (analysisCache.containsKey(cacheKey)) {
            return analysisCache.get(cacheKey);
        }
        
        String analysis = getAIAnalysis(duties);
        analysisCache.put(cacheKey, analysis);
        
        if (analysisCache.size() > 100) {
            analysisCache.clear();
        }
        
        return analysis;
    }

    /**
     * Get AI analysis using OpenAI API
     */
    private String getAIAnalysis(String duties) throws Exception {
        String prompt = String.format("""
        Analyze these job duties and extract key information for job series matching:
        
        %s
        
        Provide only:
        SKILLS: [list key skills needed]
        FUNCTIONS: [list main job functions] 
        DOMAIN: [work area/field]
        LEVEL: [Entry/Mid/Senior]
        
        Be concise and specific.
        """, duties);
    
        return callOpenAIWithTimeout(prompt, 25);
    }

    /**
     * Get top recommendations using dynamic scoring
     */
    private List<Map<String, Object>> getTopRecommendations(String duties, String aiAnalysis) {
        Set<String> userTerms = extractKeyTerms(duties, aiAnalysis);
        Map<String, Map<String, Object>> allSeries = jobSeriesService.getJobSeriesData();
        
        List<RecommendationScore> scored = new ArrayList<>();
        
        for (Map.Entry<String, Map<String, Object>> entry : allSeries.entrySet()) {
            String code = entry.getKey();
            Map<String, Object> series = entry.getValue();
            
            double score = calculateMatchScore(userTerms, duties, series);
            
            if (score > 0.05) {
                scored.add(new RecommendationScore(code, series, score));
            }
        }
        
        return scored.stream()
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(8)
                .map(this::formatRecommendationResult)
                .collect(Collectors.toList());
    }

    /**
     * Extract relevant terms from user input
     */
    private Set<String> extractKeyTerms(String duties, String aiAnalysis) {
        Set<String> terms = new HashSet<>();
    
        String[] words = duties.toLowerCase()
                .replaceAll("[^a-zA-Z0-9\\s]", " ")
                .split("\\s+");
    
        for (String word : words) {
            if (word.length() > 3 && !isCommonWord(word)) {
                terms.add(word);
            }
        }

        // After splitting into words, also extract bigrams/trigrams
        for (int i = 0; i < words.length - 1; i++) {
            String bigram = words[i] + " " + words[i + 1];
            if (bigram.length() > 6) terms.add(bigram);
            if (i < words.length - 2) {
                String trigram = words[i] + " " + words[i + 1] + " " + words[i + 2];
                if (trigram.length() > 10) terms.add(trigram);
            }
        }
    
        if (aiAnalysis != null && !aiAnalysis.isEmpty()) {
            String[] lines = aiAnalysis.split("\n");
            for (String line : lines) {
                if (line.contains(":")) {
                    String[] parts = line.split(":", 2);
                    if (parts.length == 2) {
                        String[] values = parts[1].toLowerCase().split("[,\\[\\]\\(\\)]");
                        for (String val : values) {
                            String clean = val.trim();
                            if (clean.length() > 2 && !clean.isEmpty()) {
                                terms.add(clean);
                            }
                        }
                    }
                }
            }
        }
    
        return terms;
    }

    private boolean isCommonWord(String word) {
        return word.matches("(the|and|for|are|but|not|you|all|can|had|her|was|one|our|out|day|get|has|him|his|how|its|may|new|now|old|see|two|who|work|will|with|from|have|they|that|this|been|more|some|time|very|when|much|make|than|many|over|such|take|only|think|know|just|first|also|after|back|other|good|could|would|federal|government|duties|position|agency|department|employee|responsibilities)");
    }

    /**
     * Calculate match score between user terms and job series
     */
    private double calculateMatchScore(Set<String> userTerms, String duties, Map<String, Object> series) {
        if (userTerms.isEmpty()) return 0.0;
        
        String title = (String) series.getOrDefault("title", "");
        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) series.getOrDefault("keywords", new ArrayList<>());
        @SuppressWarnings("unchecked")
        List<String> subSeries = (List<String>) series.getOrDefault("subSeries", new ArrayList<>());
        
        double score = 0.0;
        
        // Title matching (weight: 40%)
        if (!title.isEmpty()) {
            String lowerTitle = title.toLowerCase();
            long titleMatches = userTerms.stream()
                    .mapToLong(term -> lowerTitle.contains(term) ? 1 : 0)
                    .sum();
            score += (double) titleMatches / userTerms.size() * 0.4;
        }
        
        // Keywords matching (weight: 35%)
        if (!keywords.isEmpty()) {
            long keywordMatches = 0;
            for (String userTerm : userTerms) {
                for (String keyword : keywords) {
                    if (keyword.toLowerCase().contains(userTerm) || userTerm.contains(keyword.toLowerCase())) {
                        keywordMatches++;
                        break;
                    }
                }
            }
            score += (double) keywordMatches / userTerms.size() * 0.35;
        }
        
        // Sub-series matching (weight: 25%)
        if (!subSeries.isEmpty()) {
            String lowerDuties = duties.toLowerCase();
            long subMatches = subSeries.stream()
                    .mapToLong(sub -> {
                        String lowerSub = sub.toLowerCase();
                        return userTerms.stream().anyMatch(term -> 
                            lowerSub.contains(term) || lowerDuties.contains(lowerSub)) ? 1 : 0;
                    })
                    .sum();
            score += Math.min(1.0, (double) subMatches / subSeries.size()) * 0.25;
        }
        
        return Math.min(1.0, score);
    }

    /**
     * Format individual recommendation
     */
    private Map<String, Object> formatRecommendationResult(RecommendationScore score) {
        Map<String, Object> result = new HashMap<>();

        result.put("code", score.code);
        result.put("title", score.series.get("title"));
        result.put("confidence", Math.round(score.score * 100.0) / 100.0);

        // Get all sub-series/positions for this series
        @SuppressWarnings("unchecked")
        List<String> subSeries = (List<String>) score.series.getOrDefault("subSeries", new ArrayList<>());
        result.put("subSeries", subSeries);

        if (!subSeries.isEmpty()) {
            result.put("position", subSeries.get(0)); // Top position for this series
        } else {
            result.put("position", "Multiple positions available");
        }

        return result;
    }

    private List<Map<String, Object>> ensureFiveResults(List<Map<String, Object>> recs) {
        List<Map<String, Object>> result = new ArrayList<>(recs);
    
        while (result.size() < 5) {
            result.add(createFallbackRecommendation(result.size()));
        }
        
        return result.subList(0, 5);
    }

    /**
     * Create fallback recommendation when insufficient matches found
     */
    private Map<String, Object> createFallbackRecommendation(int position) {
    Map<String, Object> fallback = new HashMap<>();
    fallback.put("code", "GENERAL");
    fallback.put("title", "General Administrative and Clerical Support");
    
    // Higher fallback confidence scores
    double confidence;
    switch (position) {
        case 0: confidence = 0.85; break;
        case 1: confidence = 0.75; break;
        case 2: confidence = 0.65; break;
        case 3: confidence = 0.55; break;
        default: confidence = 0.45; break;
    }
    
    fallback.put("confidence", confidence);
    fallback.put("position", "Administrative Support Specialist");
    return fallback;
}

    /**
     * Helper class for scoring recommendations
     */
    private static class RecommendationScore {
        final String code;
        final Map<String, Object> series;
        final double score;
        
        RecommendationScore(String code, Map<String, Object> series, double score) {
            this.code = code;
            this.series = series;
            this.score = score;
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

    // Construct prompt
    String prompt = String.format("""
        You are a federal HR classification specialist.
        Based on the following:
        - Job series: %s
        - Job title: %s
        - Position title: %s
        - Assigned grade: %s
        - Duties: %s
        - Requirements: %s

        Generate a federal position evaluation statement including all 9 classification factors:

        For each factor:
        - Include the factor number, title, level, and assigned points, e.g.:
          Factor 1 – Knowledge Required by the Position Level 1-7, 1250 Points
        - Provide a detailed 3–4 sentence reasoning explaining:
          • Why this factor fits the assigned grade
          • Why it does NOT meet the next higher grade
          • Why it EXCEEDS the previous lower grade

        Factors:
        1. Knowledge Required
        2. Supervisory Controls
        3. Guidelines
        4. Complexity
        5. Scope and Effect
        6. Personal Contacts
        7. Purpose of Contacts
        8. Physical Demands
        9. Work Environment

        Keep the reasoning for each factor detailed but concise. Return only the evaluation statement.
        """, jobSeries, jobTitle, positionTitle, gsGrade, duties, requirements);

    String response = callOpenAIWithTimeout(prompt, 45);

    return Map.of("evaluationStatement", response.trim());
}

@PostMapping("/update-factor-points")
public Map<String, Object> updateFactorPoints(@RequestBody Map<String, Object> factors) throws Exception {

    final Map<String, Map<String, Integer>> FACTOR_POINTS = new HashMap<>();
    FACTOR_POINTS.put("1", Map.of("1-1",50,"1-2",200,"1-3",350,"1-4",550,"1-5",750,"1-6",950,"1-7",1250,"1-8",1550,"1-9",1850));
    FACTOR_POINTS.put("2", Map.of("2-1",25,"2-2",125,"2-3",275,"2-4",450,"2-5",650));
    FACTOR_POINTS.put("3", Map.of("3-1",25,"3-2",125,"3-3",275,"3-4",450,"3-5",650));
    FACTOR_POINTS.put("4", Map.of("4-1",25,"4-2",75,"4-3",150,"4-4",225,"4-5",325,"4-6",450));
    FACTOR_POINTS.put("5", Map.of("5-1",25,"5-2",75,"5-3",150,"5-4",225,"5-5",325,"5-6",450));
    FACTOR_POINTS.put("6", Map.of("6-1",10,"6-2",25,"6-3",60,"6-4",110));
    FACTOR_POINTS.put("7", Map.of("7-1",20,"7-2",50,"7-3",120,"7-4",220));
    FACTOR_POINTS.put("8", Map.of("8-1",5,"8-2",20,"8-3",50));
    FACTOR_POINTS.put("9", Map.of("9-1",5,"9-2",20,"9-3",50));

    try {
        // Build AI prompt
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an OPM HR expert. Review each factor content and assign the correct level. ");
        prompt.append("Return JSON only with {\"Factor X\": {\"level\":\"X-Y\",\"rationale\":\"brief explanation\"}} for all provided factors.\n");

        prompt.append("CONTENT={");
        factors.forEach((k,v) -> {
            if (!v.toString().isBlank()) {
                prompt.append(k.replace("Factor ","")).append(":\"")
                    .append(v.toString().replace("\"","'")).append("\",");
            }
        });
        if (prompt.charAt(prompt.length()-1) == ',') prompt.deleteCharAt(prompt.length()-1);
        prompt.append("}");

        String response = callOpenAIWithTimeout(prompt.toString(), 25);
        String jsonResponse = extractJsonFromResponse(response);
        System.out.println("AI JSON: " + jsonResponse); // <-- log AI response

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Map<String,String>> aiLevels =
            mapper.readValue(jsonResponse, new TypeReference<Map<String, Map<String,String>>>() {});

        int totalPoints = 0;
        Map<String, Object> finalFactors = new LinkedHashMap<>();

        for (Map.Entry<String, Map<String,String>> entry : aiLevels.entrySet()) {
            String factorNum = entry.getKey().replace("Factor ",""); // <-- fixed
            Map<String,String> data = entry.getValue();
            String level = data.get("level");

            Map<String,Integer> factorMap = FACTOR_POINTS.get(factorNum);
            if (factorMap == null) {
                throw new RuntimeException("Unknown factor number: " + factorNum);
            }

            Integer points = factorMap.get(level);
            if (points == null) {
                throw new RuntimeException("Invalid level " + level + " for factor " + factorNum);
            }

            totalPoints += points;

            finalFactors.put("Factor " + factorNum, Map.of(
                "header","Factor " + factorNum + " Level " + level + ", " + points + " Points",
                "content", factors.getOrDefault("Factor " + factorNum, ""),
                "level", level,
                "points", points,
                "rationale", data.get("rationale")
            ));
        }

        String gradeRange = calculateGradeRange(totalPoints);
        String finalGrade = calculateFinalGrade(totalPoints);

        Map<String,Object> result = new LinkedHashMap<>();
        result.put("factors", finalFactors);
        result.put("totalPoints", totalPoints);
        result.put("gradeRange", gradeRange);
        result.put("finalGrade", finalGrade);

        return result;

    } catch (Exception e) {
        e.printStackTrace();
        throw new RuntimeException("Failed to update factor points/grade: " + e.getMessage(), e);
    }
}

private String calculateGradeRange(int totalPoints) {
    if (totalPoints >=  855 && totalPoints <= 1100) return "0855-1100"; // GS-05
    if (totalPoints >= 1355 && totalPoints <= 1600) return "1355-1600"; // GS-07
    if (totalPoints >= 1855 && totalPoints <= 2100) return "1855-2100"; // GS-09
    if (totalPoints >= 2355 && totalPoints <= 2750) return "2355-2750"; // GS-11
    if (totalPoints >= 2755 && totalPoints <= 3150) return "2755-3150"; // GS-12
    if (totalPoints >= 3155 && totalPoints <= 3600) return "3155-3600"; // GS-13
    if (totalPoints >= 3605 && totalPoints <= 4050) return "3605-4050"; // GS-14
    if (totalPoints >= 4055) return "4055+"; // GS-15
    return "Unknown";
}

private String calculateFinalGrade(int totalPoints) {
    if (totalPoints >=  855 && totalPoints <= 1100) return "GS-05";
    if (totalPoints >= 1355 && totalPoints <= 1600) return "GS-07";
    if (totalPoints >= 1855 && totalPoints <= 2100) return "GS-09";
    if (totalPoints >= 2355 && totalPoints <= 2750) return "GS-11";
    if (totalPoints >= 2755 && totalPoints <= 3150) return "GS-12";
    if (totalPoints >= 3155 && totalPoints <= 3600) return "GS-13";
    if (totalPoints >= 3605 && totalPoints <= 4050) return "GS-14";
    if (totalPoints >= 4055) return "GS-15";
    return "Unknown";
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
}