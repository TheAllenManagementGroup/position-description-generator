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
import org.springframework.web.multipart.MultipartFile;
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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

    @Value("${usajobs.api.key}")
    private String usajobsApiKey;

    @Value("${usajobs.user.agent}")
    private String usajobsUserAgent;

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
            this.max_tokens = 8000; // <-- update field name
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

    private String getAutoPdfContext() {
    StringBuilder context = new StringBuilder();
    
    // CRITICAL: Load PDFs with STRICT size limits to avoid token overflow
    String opmStandards = loadPdfFromClasspathWithLimit("/static/pdfs/occupationalhandbook.pdf", 600);
    String factorGuide = loadPdfFromClasspathWithLimit("/static/pdfs/gsadmn.pdf", 400);
    String gradeGuide = loadPdfFromClasspathWithLimit("/static/pdfs/gssg.pdf", 300);
    String csGuide = loadPdfFromClasspathWithLimit("/static/pdfs/interpretive-guidance-for-cybersecurity-positions.pdf", 200);

    if (!opmStandards.isEmpty()) {
        context.append("OPM CLASSIFICATION STANDARDS (Summary):\n");
        context.append(opmStandards).append("\n\n");
    }
    if (!factorGuide.isEmpty()) {
        context.append("FACTOR EVALUATION SYSTEM (Key Points):\n");
        context.append(factorGuide).append("\n\n");
    }
    if (!gradeGuide.isEmpty()) {
        context.append("SUPERVISORY GUIDE (Summary):\n");
        context.append(gradeGuide).append("\n\n");
    }
    if (!csGuide.isEmpty()) {
        context.append("CYBERSECURITY GUIDE (Summary):\n");
        context.append(csGuide).append("\n\n");
    }
    
    // Log the actual size
    System.out.println("PDF context size: " + context.length() + " characters (~" + (context.length() / 4) + " tokens)");
    
    return context.toString();
}

private String loadPdfFromClasspathWithLimit(String classpathResource, int maxTokens) {
    try (InputStream is = getClass().getResourceAsStream(classpathResource)) {
        if (is == null) {
            System.out.println("PDF not found: " + classpathResource);
            return "";
        }

        byte[] pdfBytes = is.readAllBytes();
        PDDocument document = Loader.loadPDF(pdfBytes);
        PDFTextStripper stripper = new PDFTextStripper();
        
        // Only extract first few pages to limit size
        int totalPages = document.getNumberOfPages();
        stripper.setStartPage(1);
        stripper.setEndPage(Math.min(3, totalPages)); // Max 3 pages
        
        String text = stripper.getText(document);
        document.close();
        
        // Aggressive truncation: 1 token ≈ 4 characters
        int maxChars = maxTokens * 4;
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars);
            // Try to cut at sentence boundary
            int lastPeriod = text.lastIndexOf(". ");
            if (lastPeriod > maxChars * 0.7) {
                text = text.substring(0, lastPeriod + 1);
            }
            text += "\n[Truncated for size]";
        }
        
        System.out.println("Loaded PDF: " + classpathResource + " (" + text.length() + " chars, ~" + (text.length() / 4) + " tokens)");
        return text;
        
    } catch (Exception e) {
        System.err.println("Failed to load PDF " + classpathResource + ": " + e.getMessage());
        return "";
    }
}

    /**
     * POST endpoint to generate a position description (PD) based on
     * input parameters. Enhanced error handling and debugging.
     */
    @PostMapping("/generate")
public void generatePd(@RequestBody PdRequest request, HttpServletResponse response) throws Exception {
    System.out.println("=== PD GENERATION STARTED ===");

    System.out.println("[PD GENERATION] Incoming request variables:");
    System.out.println("  jobSeries: " + request.getJobSeries());
    System.out.println("  subJobSeries: " + request.getSubJobSeries());
    System.out.println("  positionTitle: " + request.getPositionTitle());
    System.out.println("  federalAgency: " + request.getFederalAgency());
    System.out.println("  subOrganization: " + request.getSubOrganization());
    System.out.println("  lowestOrg: " + request.getLowestOrg());
    System.out.println("  gsGrade: " + request.getGsGrade());
    System.out.println("  supervisoryLevel: " + request.getSupervisoryLevel());
    System.out.println("  totalPoints: " + request.getTotalPoints());
    System.out.println("  gradeRange: " + request.getGradeRange());
    System.out.println("  factorLevels: " + request.getFactorLevels());
    System.out.println("  factorPoints: " + request.getFactorPoints());
    System.out.println("  historicalData: " + (request.getHistoricalData() != null ? request.getHistoricalData().substring(0, Math.min(100, request.getHistoricalData().length())) + (request.getHistoricalData().length() > 100 ? "..." : "") : "null"));
    
    response.setContentType("text/event-stream");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-cache");
    response.setHeader("Connection", "keep-alive");
    response.setHeader("Access-Control-Allow-Origin", "*");

    PrintWriter writer = response.getWriter();

    try {
        // Validation
        if (request == null || request.getHistoricalData() == null || request.getHistoricalData().trim().isEmpty()) {
            writer.println("data: {\"error\":\"Job duties are required\"}\n");
            writer.flush();
            return;
        }

        String apiKey = pdService.getOpenaiApiKey();
        if (apiKey == null || !apiKey.startsWith("sk-")) {
            writer.println("data: {\"error\":\"Invalid API key\"}\n");
            writer.flush();
            return;
        }

        writer.println("data: {\"status\":\"Generating position description...\"}\n");
        writer.flush();

        // Build prompt - this should now use PdfProcessingService
        String prompt = pdService.buildPrompt(request);
        
        // CRITICAL: Validate token count BEFORE sending
        int estimatedInputTokens = prompt.length() / 4;
        System.out.println("Prompt length: " + prompt.length() + " chars (~" + estimatedInputTokens + " tokens)");
        
        // If prompt is too large, truncate it
        int maxInputTokens = 3000; // Leave room for 4000 output tokens
        if (estimatedInputTokens > maxInputTokens) {
            int maxChars = maxInputTokens * 4;
            System.out.println("WARNING: Truncating prompt from " + prompt.length() + " to " + maxChars + " chars");
            prompt = prompt.substring(0, maxChars);
            estimatedInputTokens = maxInputTokens;
        }

        // Prepare OpenAI request with SAFE token allocation
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", 
            "You are an expert federal HR classification specialist. " +
            "Create COMPLETE position descriptions with ALL sections fully written. " +
            "NO placeholders or brackets."
        ));
        messages.add(new Message("user", prompt));

        // Calculate safe output tokens
        int safeOutputTokens = Math.min(4000, 8000 - estimatedInputTokens - 200);
        
        OpenAIRequest openaiRequest = new OpenAIRequest("gpt-4", messages, true);
        openaiRequest.setMax_tokens(safeOutputTokens);
        openaiRequest.setTemperature(0.3);
        
        System.out.println("Token allocation: Input=" + estimatedInputTokens + ", Output=" + safeOutputTokens);
        
        ObjectMapper objectMapper = new ObjectMapper();
        String requestBody = objectMapper.writeValueAsString(openaiRequest);

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .timeout(java.time.Duration.ofSeconds(180))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build();
            
        HttpResponse<InputStream> openaiResponse = client.send(httpRequest, 
            HttpResponse.BodyHandlers.ofInputStream());

        System.out.println("OpenAI response status: " + openaiResponse.statusCode());

        if (openaiResponse.statusCode() != 200) {
            String errorResponse = new BufferedReader(new InputStreamReader(openaiResponse.body()))
                .lines().collect(Collectors.joining("\n"));
            System.err.println("OpenAI Error: " + errorResponse);
            writer.println("data: {\"error\":\"API Error: " + escapeJson(errorResponse) + "\"}\n");
            writer.flush();
            return;
        }

        // Stream response
        StringBuilder fullText = new StringBuilder();
        int chunkCount = 0;
        
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
                                        fullText.append(content);
                                        chunkCount++;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("Error parsing chunk: " + e.getMessage());
                        }
                    }
                }
            }
        }

        System.out.println("Streamed " + chunkCount + " chunks, total " + fullText.length() + " chars");

        if (fullText.length() == 0) {
            writer.println("data: {\"error\":\"No content generated. Please try again.\"}\n");
        } else {
            String formattedPD = fixPDFormatting(fullText.toString());
            writer.println("data: {\"fullPD\":\"" + escapeJson(formattedPD) + "\"}\n");
        }
        
        writer.println("data: [DONE]\n");
        writer.flush();
        
        System.out.println("PD generation completed");
        
    } catch (Exception e) {
        System.err.println("EXCEPTION in generatePd: " + e.getMessage());
        e.printStackTrace();
        writer.println("data: {\"error\":\"" + escapeJson(e.getMessage()) + "\"}\n");
    } finally {
        try {
            writer.flush();
            writer.close();
        } catch (Exception e) {
            System.err.println("Error closing writer: " + e.getMessage());
        }
    }
}

    @PostMapping("/generate-sync")
public ResponseEntity<Map<String, String>> generatePdSync(@RequestBody PdRequest request) throws Exception {
    System.out.println("=== PD GENERATION (NON-STREAMING) STARTED ===");

    Map<String, String> result = new HashMap<>();

    try {
        // Validation
        if (request == null || request.getHistoricalData() == null || request.getHistoricalData().trim().isEmpty()) {
            result.put("error", "Job duties are required");
            return ResponseEntity.badRequest().body(result);
        }

        String apiKey = pdService.getOpenaiApiKey();
        if (apiKey == null || !apiKey.startsWith("sk-")) {
            result.put("error", "Invalid API key");
            return ResponseEntity.badRequest().body(result);
        }

        // --- Ensure factor values are locked and valid ---
        String gsGrade = request.getGsGrade() != null ? request.getGsGrade() : "GS-13";
        String supervisoryLevel = request.getSupervisoryLevel() != null ? request.getSupervisoryLevel() : "Non-Supervisory";

        Map<String, String> factorLevels = request.getFactorLevels();
        Map<String, Integer> factorPoints = request.getFactorPoints();

        if (factorLevels == null || factorPoints == null || factorLevels.size() != 9 || factorPoints.size() != 9) {
            // Auto-generate locked factor levels and points for the assigned grade
            factorLevels = pdService.getDefaultFactorLevelsForGrade(gsGrade, supervisoryLevel);
            factorPoints = new HashMap<>();
            for (Map.Entry<String, String> entry : factorLevels.entrySet()) {
                factorPoints.put(entry.getKey(), pdService.getPointsForFactorLevel(entry.getKey(), entry.getValue()));
            }
            request.setFactorLevels(factorLevels);
            request.setFactorPoints(factorPoints);
        }

        // Calculate total points and grade range
        int totalPoints = factorPoints.values().stream().mapToInt(Integer::intValue).sum();
        String gradeRange = pdService.getPointRangeForGrade(gsGrade);

        request.setTotalPoints(totalPoints);
        request.setGradeRange(gradeRange);
        request.setGsGrade(gsGrade);

        String prompt = pdService.buildConcisePrompt(request);

        // Estimate token usage
        int promptTokens = prompt.length() / 4;
        int maxModelTokens = 8192;
        int safeOutputTokens = Math.min(1200, maxModelTokens - promptTokens - 200); // Increased for full PD

        System.out.println("Prompt length: " + prompt.length() + " chars (~" + promptTokens + " tokens)");
        System.out.println("Token allocation: Input=" + promptTokens + ", Output=" + safeOutputTokens);
        System.out.println("Received jobSeries: " + request.getJobSeries());
        System.out.println("Received positionTitle: " + request.getPositionTitle());
        System.out.println("Received subJobSeries: " + request.getSubJobSeries());

        // Call OpenAI synchronously (no streaming), passing historicalData as context
        try {
            // Pass safeOutputTokens to PdService if needed, or set it inside PdService
            String rawPD = pdService.callOpenAI(prompt, request.getHistoricalData());
            String formattedPD = pdService.fixPDFormatting(rawPD);
            result.put("fullPD", formattedPD);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            System.err.println("OpenAI error: " + e.getMessage());
            result.put("error", "OpenAI error: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }

    } catch (Exception e) {
        System.err.println("Exception in generatePdSync: " + e.getMessage());
        result.put("error", e.getMessage());
        return ResponseEntity.status(500).body(result);
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

            Duties may be listed with percentages. Duties with higher percentages must be given greater weight and considered more important in your analysis. Percentages if present depict the importance of that duty.

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
        // Automatically prepend PDF context to every prompt
        String pdfContext = getAutoPdfContext();
        String enhancedPrompt = pdfContext + prompt;
        
        System.out.println("Enhanced prompt length: " + enhancedPrompt.length() + " chars (includes PDF context)");
        
        // Prepare OpenAI API request
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", 
            "You are a federal HR specialist with access to official OPM standards. " +
            "Use the provided reference materials to ensure accurate classification."));
        messages.add(new Message("user", enhancedPrompt));

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

        // Remove duplicate series codes (keep first occurrence)
        List<Map<String, String>> uniqueClassifications = new ArrayList<>();
        java.util.HashSet<String> seenCodes = new java.util.HashSet<>();
        for (Map<String, String> classification : classifications) {
            String code = classification.getOrDefault("seriesCode", "");
            if (!seenCodes.contains(code) && !code.isBlank()) {
                uniqueClassifications.add(classification);
                seenCodes.add(code);
            }
            if (uniqueClassifications.size() >= 3) break; // Only need top 3
        }

        List<Map<String, Object>> recommendations = new ArrayList<>();
        for (Map<String, String> classification : uniqueClassifications) {
            String code = classification.getOrDefault("seriesCode", "");
            String title = classification.getOrDefault("seriesTitle", "");

            Map<String, Object> recommendation = new HashMap<>();
            recommendation.put("code", code);
            recommendation.put("title", title);
            recommendation.put("confidence", 0.99);
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
        result.put("supervisoryLevel", supervisoryLevel);

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
    String pdfContext = getAutoPdfContext();
    
    String prompt = pdfContext + String.format("""
        Duties may be listed with percentages. Duties with higher percentages must be given greater weight and considered more important in your analysis. Percentages if present depict the importance of that duty.
        Use the official OPM two-grade interval system and the reference materials provided above for accurate grade determination.

        IMPORTANT: Consider the supervisory level when determining grade levels:
        - Non-Supervisory: Focus on technical complexity and individual contribution
        - Team Leader: Add 1 grade level for informal leadership responsibilities
        - Supervisor: Add 1-2 grade levels for formal supervisory duties
        - Manager: Add 2-3 grade levels for managerial responsibilities

        Supervisory Level: %s

        Based on the following federal job duties, supervisory level, and OPM standards provided above, 
        list the top 5 most likely GS grade levels for this position.
        
        Only consider two-grade interval grades (GS-5, GS-7, GS-9, GS-11, GS-12, GS-13, GS-14, GS-15) 
        unless the duties clearly fit a one-grade interval series.
        
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
public void generateEvaluationStatement(@RequestBody Map<String, String> body, HttpServletResponse response) throws Exception {
    response.setContentType("text/event-stream");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-cache");
    response.setHeader("Connection", "keep-alive");
    response.setHeader("Access-Control-Allow-Origin", "*");

    PrintWriter writer = response.getWriter();

    try {
        String duties = body.getOrDefault("duties", "");
        String gsGrade = body.getOrDefault("gsGrade", "");
        String positionTitle = body.getOrDefault("positionTitle", "");
        String supervisoryLevel = body.getOrDefault("supervisoryLevel", "Non-Supervisory");

        if (gsGrade == null || gsGrade.trim().isEmpty()) {
            List<Map<String, Object>> gradeRelevancy = getAIGSGradeRelevancy(duties, supervisoryLevel);
            gsGrade = gradeRelevancy != null && !gradeRelevancy.isEmpty()
                ? (String) gradeRelevancy.get(0).get("grade")
                : "GS-13";
        }

        Map<String, String> gradeMinimums = getMinimumFactorLevelsForGrade(gsGrade);
        int targetMinPoints = getMinPointsForGrade(gsGrade);
        int targetMaxPoints = getMaxPointsForGrade(gsGrade);

        String pdfContext = getAutoPdfContext();

        String prompt = String.format("""
            %s

            Duties may be listed with percentages. Duties with higher percentages must be given greater weight and considered more important in your analysis. Percentages if present depict the importance of that duty.

            You are creating an OPM factor evaluation for %s targeting %d-%d points for %s.

            CRITICAL REQUIREMENTS:
            - ALL 9 factors must be analyzed with specific levels and rationales.
            - Factor 1 minimum: %s, Factor 2 minimum: %s, Factor 5 minimum: %s.
            - For each factor, especially Factor 1, explain in 2-3 sentences WHY the selected level fits the work described, and WHY it does NOT fit the next lower or higher level (e.g., for 1-8, explain why not 1-7 or 1-9).
            - NEVER say the position does not fit the same level as assigned. If the factor is assigned 1-8, do NOT say it does not fit 1-8. Only compare to the next lower and next higher levels.
            - DO NOT say factor levels or points might be adjusted after generation. Your response must be final and complete.
            - DO NOT mention GS grades, grade ranges, or point values in the rationale text.
            - DO NOT reference "Factor Level X-X" or point values in the explanation.
            - Focus on the WORK CHARACTERISTICS and specific duties that justify the selected level and distinguish it from adjacent levels.
            - DO NOT say anything about needing to finish, complete, or address all factors. Your response must always be fully complete and never mention unfinished or incomplete factors.

            GRADE DIFFERENTIATION GUIDANCE:
            For %s positions, you must explain:
            - Why the work is MORE complex/responsible than the next lower level (what additional elements elevate it)
            - Why the work is LESS complex/responsible than the next higher level (what elements are not yet present)
            - Specific duty examples that demonstrate the appropriate level

            MANDATORY RESPONSE FORMAT (complete ALL 9 factors):

            Factor 1 – Knowledge Required by the Position Level 1-X, XXX Points

            [Explain the depth and breadth of knowledge required. Describe why this level of knowledge is appropriate for the work, and why it is not the next lower or higher level. Reference specific duties that demonstrate this knowledge level.]

            ... (repeat for other factors)

            Total Points: [EXACT sum]

            Final Grade: %s

            Grade Range: %s
            """,
                pdfContext,
                positionTitle,
                targetMinPoints, targetMaxPoints,
                gsGrade,
                gradeMinimums.get("f1"),
                gradeMinimums.get("f2"),
                gradeMinimums.get("f5"),
                gsGrade,
                gsGrade,
                getPointRangeForGrade(gsGrade)
            );

        // Prepare OpenAI streaming request
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "You are a federal HR specialist. Provide complete, accurate analysis based on OPM standards."));
        messages.add(new Message("user", prompt));

        OpenAIRequest openaiRequest = new OpenAIRequest("gpt-4", messages, true);
        openaiRequest.setMax_tokens(2000);
        openaiRequest.setTemperature(0.3);

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

        StringBuilder fullText = new StringBuilder();
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
                                        String formatted = fixPDFormatting(content);
                                        writer.println("data: {\"response\":\"" + escapeJson(formatted) + "\"}\n");
                                        writer.flush();
                                        fullText.append(content);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("Error parsing chunk: " + e.getMessage());
                        }
                    }
                }
            }
        }

        // After streaming is done, send the fully formatted evaluation statement
        if (fullText.length() == 0) {
            writer.println("data: {\"error\":\"No content generated. Please try again.\"}\n");
        } else {
            String formattedEval = fixPDFormatting(fullText.toString());

            // --- ENFORCE TOTAL POINTS FIT THE ASSIGNED GRADE RANGE ---
            Map<String, Integer> factorPoints = extractFactorPoints(formattedEval);
            int totalPoints = factorPoints.values().stream().mapToInt(Integer::intValue).sum();

            int minPoints = getMinPointsForGrade(gsGrade);
            int maxPoints = getMaxPointsForGrade(gsGrade);

            if (totalPoints < minPoints || totalPoints > maxPoints) {
                int diff = (minPoints + maxPoints) / 2 - totalPoints;
                String[] priorityFactors = {"1", "5", "2", "4", "3", "6", "7", "8", "9"};
                for (String factorNum : priorityFactors) {
                    Integer oldPoints = factorPoints.get(factorNum);
                    if (oldPoints == null) continue;
                    String oldLevel = extractFactorLevel(formattedEval, factorNum);
                    String newLevel = diff > 0
                        ? getNextHigherValidLevel(factorNum, oldLevel)
                        : getNextLowerValidLevel(factorNum, oldLevel);
                    if (newLevel != null) {
                        int newPoints = getPointsForLevel(factorNum, newLevel);
                        int change = newPoints - oldPoints;
                        if ((diff > 0 && change > 0) || (diff < 0 && change < 0)) {
                            formattedEval = formattedEval.replaceFirst(
                                "(Factor\\s+" + factorNum + "[^\\n]*?)Level\\s+" + oldLevel + ",\\s*" + oldPoints + "\\s*Points",
                                "$1Level " + newLevel + ", " + newPoints + " Points"
                            );
                            factorPoints.put(factorNum, newPoints);
                            totalPoints += change;
                            if (totalPoints >= minPoints && totalPoints <= maxPoints) break;
                        }
                    }
                }
                totalPoints = factorPoints.values().stream().mapToInt(Integer::intValue).sum();
            }

            String finalGrade = calculateFinalGrade(totalPoints);
            String gradeRange = calculateGradeRange(totalPoints);
            formattedEval = formattedEval
                .replaceAll("\\*\\*Total Points:\\s*\\d+\\*\\*", "**Total Points: " + totalPoints + "**")
                .replaceAll("\\*\\*Final Grade:\\s*GS-\\d+\\*\\*", "**Final Grade: " + finalGrade + "**")
                .replaceAll("\\*\\*Grade Range:\\s*[\\d\\-+]+\\*\\*", "**Grade Range: " + gradeRange + "**");

            writer.println("data: {\"evaluationStatement\":\"" + escapeJson(formattedEval) + "\"}\n");
        }
        writer.println("data: [DONE]\n");
        writer.flush();

    } catch (Exception e) {
        System.err.println("EXCEPTION in generateEvaluationStatementStream: " + e.getMessage());
        e.printStackTrace();
        writer.println("data: {\"error\":\"" + escapeJson(e.getMessage()) + "\"}\n");
    } finally {
        try {
            writer.flush();
            writer.close();
        } catch (Exception e) {
            System.err.println("Error closing writer: " + e.getMessage());
        }
    }
}

/**
 * Enforce exact grade alignment by validating and correcting factor points
 */
private String enforceExactGradeAlignment(String response, String targetGrade, int minPoints, int maxPoints) {
    System.out.println("Enforcing exact grade alignment for " + targetGrade + " (" + minPoints + "-" + maxPoints + " points)");

    // Extract current factor levels and points
    Map<String, String> currentLevels = new HashMap<>();
    Map<String, Integer> currentPoints = new HashMap<>();
    Pattern factorPattern = Pattern.compile("Factor\\s+(\\d+)[^\\n]*?Level\\s+(\\d+-[\\dA-Z]),\\s*(\\d+)\\s*Points");
    Matcher matcher = factorPattern.matcher(response);

    int actualTotal = 0;
    while (matcher.find()) {
        String factorNum = matcher.group(1);
        String level = matcher.group(2);
        int points = Integer.parseInt(matcher.group(3));

        // Validate points match the level
        int correctPoints = getPointsForLevel(factorNum, level);
        if (correctPoints != points) {
            System.out.println("WARNING: Factor " + factorNum + " has wrong points: " + points + " (should be " + correctPoints + ")");
            points = correctPoints;
        }

        currentLevels.put(factorNum, level);
        currentPoints.put(factorNum, points);
        actualTotal += points;
    }

    System.out.println("Current total: " + actualTotal + " points (target: " + minPoints + "-" + maxPoints + ")");

    // If not in range, adjust factors to hit target
    if (actualTotal < minPoints || actualTotal > maxPoints) {
        System.out.println("FORCING grade alignment - adjusting factors...");

        int targetMidpoint = (minPoints + maxPoints) / 2;
        int pointsNeeded = targetMidpoint - actualTotal;

        // Adjust factors intelligently (prioritize Factor 1, 2, 5 for increases; 9, 8, 7 for decreases)
        String[] adjustOrder = pointsNeeded > 0 ?
            new String[]{"1", "5", "2", "4", "3", "6", "7", "8", "9"} :
            new String[]{"9", "8", "7", "6", "3", "4", "2", "5", "1"};

        for (String factorNum : adjustOrder) {
            if (Math.abs(pointsNeeded) < 50) break; // Close enough to target

            String currentLevel = currentLevels.get(factorNum);
            if (currentLevel == null) continue;

            String newLevel = pointsNeeded > 0 ?
                getNextHigherValidLevel(factorNum, currentLevel) :
                getNextLowerValidLevel(factorNum, currentLevel);

            if (newLevel != null) {
                int oldPoints = currentPoints.get(factorNum);
                int newPoints = getPointsForLevel(factorNum, newLevel);
                int change = newPoints - oldPoints;

                // Only apply if it moves us closer to target without overshooting too much
                if ((pointsNeeded > 0 && change > 0 && change <= pointsNeeded + 100) ||
                    (pointsNeeded < 0 && change < 0 && change >= pointsNeeded - 100)) {

                    // --- Use preserveFactorLetter here ---
                    Pattern headerPattern = Pattern.compile(
                        "(Factor\\s+" + factorNum + "[^\\n]*?)Level\\s+" + Pattern.quote(currentLevel) + ",\\s*" + oldPoints + "\\s*Points"
                    );
                    Matcher headerMatcher = headerPattern.matcher(response);
                    if (headerMatcher.find()) {
                        String replacement = headerMatcher.group(1) + "Level " + preserveFactorLetter(currentLevel, newLevel) + ", " + newPoints + " Points";
                        response = headerMatcher.replaceFirst(replacement);
                    }

                    currentLevels.put(factorNum, preserveFactorLetter(currentLevel, newLevel));
                    currentPoints.put(factorNum, newPoints);
                    actualTotal += change;
                    pointsNeeded -= change;

                    System.out.println("Adjusted Factor " + factorNum + ": " + currentLevel + " -> " +
                        preserveFactorLetter(currentLevel, newLevel) + " (" + (change > 0 ? "+" : "") + change + " pts)");
                }
            }
        }
    }

    // Recalculate final total
    int finalTotal = currentPoints.values().stream().mapToInt(Integer::intValue).sum();
    String calculatedGrade = calculateFinalGrade(finalTotal);
    String gradeRange = calculateGradeRange(finalTotal);

    // Force update the summary section with correct values
    response = response.replaceAll("\\*\\*Total Points:\\s*\\d+\\*\\*", "**Total Points: " + finalTotal + "**");
    response = response.replaceAll("\\*\\*Final Grade:\\s*GS-\\d+\\*\\*", "**Final Grade: " + calculatedGrade + "**");
    response = response.replaceAll("\\*\\*Grade Range:\\s*[\\d-]+\\+?\\*\\*", "**Grade Range: " + gradeRange + "**");

    // Final validation
    if (!calculatedGrade.equals(targetGrade)) {
        System.out.println("WARNING: Grade mismatch after adjustment - Calculated: " + calculatedGrade +
            ", Target: " + targetGrade + " (Total: " + finalTotal + " points)");
    } else {
        System.out.println("SUCCESS: Grade alignment achieved - " + finalTotal + " points = " + calculatedGrade);
    }

    return response;
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

/**
 * Complete updateFactorPoints method with proper error handling and level validation
 */
@PostMapping("/update-factor-points")
public Map<String, Object> updateFactorPoints(@RequestBody Map<String, Object> factors) {
    String supervisoryLevel = (String) factors.getOrDefault("supervisoryLevel", "Non-Supervisory");
    String expectedGrade = (String) factors.getOrDefault("expectedGrade", null);

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
        prompt.append("Duties may be listed with percentages. Duties with higher percentages must be given greater weight and considered more important in your analysis. Percentages if present depict the importance of that duty.\n");
        prompt.append("You are an OPM HR expert specializing in federal position classification. ");
        prompt.append("Supervisory Level: ").append(supervisoryLevel).append("\n");

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

        System.out.println("Starting OpenAI factor evaluation request at: " + new Date());
        System.out.println("Expected Grade: " + expectedGrade);
        System.out.println("Evaluating factors: " + factors.keySet().stream()
            .filter(k -> !k.equals("expectedGrade") && !k.equals("supervisoryLevel"))
            .collect(Collectors.toList()));

        String response = callOpenAIWithTimeout(prompt.toString(), 25);

        if (response == null || response.trim().isEmpty()) {
            throw new RuntimeException("Empty response from OpenAI API");
        }

        String jsonResponse = extractJsonFromResponse(response);
        if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
            throw new RuntimeException("Could not extract valid JSON from OpenAI response");
        }

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Map<String, String>> aiLevels;
        try {
            aiLevels = mapper.readValue(jsonResponse, new TypeReference<Map<String, Map<String, String>>>() {});
        } catch (JsonProcessingException e) {
            System.err.println("JSON parsing failed. Response: " + jsonResponse);
            throw new RuntimeException("Failed to parse AI response as JSON: " + e.getMessage());
        }

        int totalPoints = 0;
        Map<String, Object> finalFactors = new LinkedHashMap<>();
        List<String> processingWarnings = new ArrayList<>();

        Map<String, String> originalLevels = new HashMap<>();
        for (int i = 1; i <= 9; i++) {
            String origKey = "Factor " + i + "_originalLevel";
            Object origLevelObj = factors.get(origKey);
            if (origLevelObj != null) {
                originalLevels.put(String.valueOf(i), origLevelObj.toString());
            }
        }

        for (Map.Entry<String, Map<String, String>> entry : aiLevels.entrySet()) {
            String factorKey = entry.getKey();
            String factorNum = factorKey.replace("Factor ", "").trim();
            Map<String, String> levelData = entry.getValue();
            String aiLevel = levelData.get("level");

            String origLevel = originalLevels.get(factorNum);
            String correctedLevel = correctAndValidateLevel(factorNum, aiLevel);
            if (origLevel != null && correctedLevel != null) {
                correctedLevel = preserveFactorLetter(origLevel, correctedLevel);
            }

            Map<String, Integer> factorPointMap = FACTOR_POINTS.get(factorNum);
            Integer points = factorPointMap != null ? factorPointMap.get(correctedLevel) : null;
            if (points == null) {
                processingWarnings.add("Invalid level '" + correctedLevel + "' for factor " + factorNum + " after correction");
                continue;
            }

            totalPoints += points;

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

        // --- ENFORCE GRADE ALIGNMENT IF NEEDED ---
        int minPoints = getMinPointsForGrade(finalGrade);
        int maxPoints = getMaxPointsForGrade(finalGrade);
        if (totalPoints < minPoints || totalPoints > maxPoints) {
            // Build a fake "evaluation statement" string to use your existing alignment logic
            StringBuilder eval = new StringBuilder();
            for (int i = 1; i <= 9; i++) {
                Map<String, Object> f = (Map<String, Object>) finalFactors.get("Factor " + i);
                if (f != null) {
                    eval.append("Factor ").append(i)
                        .append(" Level ").append(f.get("level"))
                        .append(", ").append(f.get("points")).append(" Points\n");
                }
            }
            String aligned = enforceExactGradeAlignment(eval.toString(), finalGrade, minPoints, maxPoints);

            // Parse the aligned result and update finalFactors and totalPoints
            Pattern p = Pattern.compile("Factor\\s+(\\d+)\\s+Level\\s+(\\d+-\\d+),\\s*(\\d+)\\s*Points");
            Matcher m = p.matcher(aligned);
            int newTotal = 0;
            while (m.find()) {
                String factorNum = m.group(1);
                String level = m.group(2);
                int points = Integer.parseInt(m.group(3));
                Map<String, Object> f = (Map<String, Object>) finalFactors.get("Factor " + factorNum);
                if (f != null) {
                    f.put("level", level);
                    f.put("points", points);
                }
                newTotal += points;
            }
            totalPoints = newTotal;
            gradeRange = calculateGradeRange(totalPoints);
            finalGrade = calculateFinalGrade(totalPoints);
        }

        String summary = String.format("**Total Points: %d**\n**Final Grade: %s**\n**Grade Range: %s**", totalPoints, finalGrade, gradeRange);
        finalFactors.put("Grade Evaluations", summary);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("factors", finalFactors);
        result.put("totalPoints", totalPoints);
        result.put("gradeRange", gradeRange);
        result.put("finalGrade", finalGrade);
        result.put("timestamp", new Date());

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

        if (!processingWarnings.isEmpty()) {
            result.put("warnings", processingWarnings);
        }

        System.out.println("Successfully processed factor evaluation. Total points: " + totalPoints +
                          ", Final grade: " + finalGrade);

        return result;

    } catch (Exception e) {
        System.err.println("Error in updateFactorPoints: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        e.printStackTrace();

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

/**
 * Fix spacing and formatting issues in generated PD text.
 */
public String fixPDFormatting(String pdText) {
    if (pdText == null || pdText.trim().isEmpty()) return "";

    String text = pdText.trim();

    // Remove lines that contain only asterisks (with or without whitespace)
    text = text.replaceAll("(?m)^\\s*\\*+\\s*$\\n?", "");

    // Remove asterisks that are alone on a line or surrounded by blank lines
    text = text.replaceAll("(?m)^\\s*\\*+\\s*$", "");

    // Normalize line endings and whitespace
    text = text.replaceAll("\\r\\n?", "\n");
    text = text.replaceAll("\\n{3,}", "\n\n");

    // Add space between letters and numbers (e.g., GS13 -> GS 13, Factor3 -> Factor 3)
    text = text.replaceAll("([a-zA-Z])([0-9])", "$1 $2");
    text = text.replaceAll("([0-9])([a-zA-Z])", "$1 $2");

    // Ensure section headers are always on their own line
    text = text.replaceAll("(Duties/Responsibilities Context:[^\n]+)[ \\t]*\\*\\*INTRODUCTION\\*\\*", "$1\n\n\n**INTRODUCTION**");

    // --- HEADER variable splitting ---
    text = text.replaceAll("Job Series:\\s*([^\\n]+?)Position Title:", "Job Series: $1\nPosition Title:");
    text = text.replaceAll("Position Title:\\s*([^\\n]+?)Agency:", "Position Title: $1\nAgency:");
    text = text.replaceAll("Agency:\\s*([^\\n]+?)Organization:", "Agency: $1\nOrganization:");
    text = text.replaceAll("Organization:\\s*([^\\n]+?)Supervisory Level:", "Organization: $1\nSupervisory Level:");
    text = text.replaceAll("Supervisory Level:\\s*([^\\n]+?)Duties/Responsibilities Context:", "Supervisory Level: $1\nDuties/Responsibilities Context:");

    // --- Section headers bold and spaced ---
    String[] sectionHeaders = {
        "HEADER",
        "INTRODUCTION",
        "MAJOR DUTIES",
        "FACTOR EVALUATION - COMPLETE ANALYSIS",
        "EVALUATION SUMMARY",
        "CONDITIONS OF EMPLOYMENT",
        "TITLE AND SERIES DETERMINATION",
        "FAIR LABOR STANDARDS ACT DETERMINATION"
    };
    for (String header : sectionHeaders) {
        String regex = "\\*{0,2}\\s*" + header + "\\s*:?\\s*\\*{0,2}";
        text = text.replaceAll("(?i)" + regex, "\n\n**" + header + "**\n\n");
    }

    // --- Factor header spacing fix ---
    text = text.replaceAll("Factor\\s*([0-9])([–-])\\s*", "Factor $1 $2 ");
    text = text.replaceAll("Level\\s*([0-9]+)-([0-9]+),\\s*([0-9]+)\\s*Points", "Level $1-$2, $3 Points");
    text = text.replaceAll("(Level\\s*[0-9]+-[0-9]+),([0-9]+\\s*Points)", "$1, $2");

    // --- Ensure factor headers are bold and spaced ---
    text = text.replaceAll(
        "\\*\\*\\s*Factor\\s*([0-9])\\s*[–-]\\s*([^\\n]+?)\\s*Level\\s*([0-9]+)-([0-9]+),\\s*([0-9]+)\\s*Points\\s*\\*\\*",
        "\n\n**Factor $1 – $2 Level $3-$4, $5 Points**\n\n"
    );
    text = text.replaceAll(
        "Factor\\s*([0-9])\\s*[–-]\\s*([^\\n]+?)\\s*Level\\s*([0-9]+)-([0-9]+),\\s*([0-9]+)\\s*Points",
        "\n\n**Factor $1 – $2 Level $3-$4, $5 Points**\n\n"
    );

    // --- Evaluation summary spaced and bold ---
    text = text.replaceAll("Total Points:\\s*(\\d+)", "\n\n**Total Points: $1**\n");
    text = text.replaceAll("Final Grade:\\s*(GS-\\d+)", "\n\n**Final Grade: $1**\n");
    text = text.replaceAll("Grade Range:\\s*([0-9\\-+]+)", "\n\n**Grade Range: $1**\n");

    // --- Remove extra spaces and blank lines ---
    text = text.replaceAll("[ \\t]+\\n", "\n");
    text = text.replaceAll("\\n[ \\t]+", "\n");
    text = text.replaceAll("\\n{3,}", "\n\n");
    text = text.replaceAll("^\\n+", "");
    text = text.replaceAll("\\n+$", "");

    // --- Fix run-together header lines (e.g., "**HEADER** Job Series: ...") ---
    text = text.replaceAll("(\\*\\*HEADER\\*\\*)\\s*([^\\n]+)", "$1\n$2");
    text = text.replaceAll("(\\*\\*INTRODUCTION\\*\\*)\\s*([^\\n]+)", "$1\n$2");
    text = text.replaceAll("(\\*\\*MAJOR DUTIES\\*\\*)\\s*([^\\n]+)", "$1\n$2");
    text = text.replaceAll("(\\*\\*FACTOR EVALUATION - COMPLETE ANALYSIS\\*\\*)\\s*([^\\n]+)", "$1\n$2");
    text = text.replaceAll("(\\*\\*EVALUATION SUMMARY\\*\\*)\\s*([^\\n]+)", "$1\n$2");
    text = text.replaceAll("(\\*\\*CONDITIONS OF EMPLOYMENT\\*\\*)\\s*([^\\n]+)", "$1\n$2");
    text = text.replaceAll("(\\*\\*TITLE AND SERIES DETERMINATION\\*\\*)\\s*([^\\n]+)", "$1\n$2");
    text = text.replaceAll("(\\*\\*FAIR LABOR STANDARDS ACT DETERMINATION\\*\\*)\\s*([^\\n]+)", "$1\n$2");

    // --- Remove any double spaces ---
    text = text.replaceAll(" +", " ");

    return text.trim();
}

@PostMapping("/classify-series")
public List<Map<String, String>> classifySeries(@RequestBody Map<String, String> body) throws Exception {
    String duties = body.getOrDefault("duties", "");
    if (duties.isBlank()) {
        return List.of(Map.of("error", "No duties provided"));
    }

    String pdfContext = getAutoPdfContext();

    String prompt = pdfContext + String.format("""
        Duties may be listed with percentages. Duties with higher percentages must be given greater weight and considered more important in your analysis. Percentages if present depict the importance of that duty.
        You are an expert in federal HR classification with access to official OPM standards provided above.
        
        Based on the following job duties and the OPM classification standards, recommend the top three
        most appropriate federal job series codes (e.g., 1801, 1811, 0132, 0343, etc.).
        
        Use the reference materials provided to ensure accurate series classification based on:
        - Primary work performed
        - Knowledge and skills required
        - Occupational group definitions
        - Series-specific criteria from OPM standards
        
        Respond ONLY with a numbered list in this exact format:
        1. 1801 - General Inspection, Investigation, Enforcement, and Compliance
        2. 1811 - Criminal Investigator
        3. 0132 - Intelligence Operations Specialist

        DO NOT include any explanation or reasoning - ONLY the numbered list.

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
    
    // Fallback: if nothing matched, try to extract just the series codes
    if (results.isEmpty()) {
        Pattern codePattern = Pattern.compile("(\\d{4})");
        Matcher codeMatcher = codePattern.matcher(response);
        while (codeMatcher.find() && results.size() < 3) {
            results.add(Map.of("seriesCode", codeMatcher.group(1), "seriesTitle", ""));
        }
    }
    
    // Final fallback: return the whole response
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

        // 2. Generate evaluation statement
        Map<String, String> evaluationRequest = new HashMap<>();
        evaluationRequest.put("duties", request.getHistoricalData());
        evaluationRequest.put("gsGrade", targetGrade);
        evaluationRequest.put("jobSeries", request.getJobSeries() != null ? request.getJobSeries() : "");
        evaluationRequest.put("jobTitle", request.getSubJobSeries() != null ? request.getSubJobSeries() : "");
        evaluationRequest.put("positionTitle", request.getSubJobSeries() != null ? request.getSubJobSeries() : "");
        evaluationRequest.put("supervisoryLevel", supervisoryLevel);

        generateEvaluationStatement(evaluationRequest, response);
        String evaluationStatement = "";

        // 3. Extract exact factor values from evaluation statement
        Map<String, String> factorLevels = extractFactorLevels(evaluationStatement);
        Map<String, Integer> factorPoints = extractFactorPoints(evaluationStatement);
        Integer totalPoints = extractPointsFromEvaluation(evaluationStatement);
        String finalGrade = extractGradeFromEvaluation(evaluationStatement);
        String gradeRange = extractRangeFromEvaluation(evaluationStatement);

        // 4. Validate extraction worked
        if (factorLevels.size() != 9 || factorPoints.size() != 9) {
            System.err.println("WARNING: Failed to extract all 9 factors. Using defaults.");
            factorLevels = getDefaultFactorLevels(targetGrade);
            final Map<String, Integer> defaultFactorPoints = getDefaultFactorPoints(targetGrade);
            totalPoints = defaultFactorPoints.values().stream().mapToInt(Integer::intValue).sum();
            finalGrade = targetGrade;
            gradeRange = getPointRangeForGrade(targetGrade);
        }

        // 5. Lock these exact values into the request
        request.setFactorLevels(factorLevels);
        request.setFactorPoints(factorPoints);
        request.setTotalPoints(totalPoints);
        request.setGsGrade(finalGrade);
        request.setGradeRange(gradeRange);

        System.out.println("Locked factor values:");
        factorLevels.forEach((k, v) -> System.out.println("  Factor " + k + ": Level " + v + ", " + factorPoints.get(k) + " pts"));
        System.out.println("Total: " + totalPoints + " pts = " + finalGrade + " (" + gradeRange + ")");

        writer.println("data: {\"status\":\"Generating position description with locked values...\"}\n");
        writer.flush();

        // 6. Generate PD with locked values
        String prompt = pdService.buildPrompt(request);

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", 
            "You are an expert federal HR classification specialist. " +
            "Create complete, professional position descriptions using the EXACT factor values provided. " +
            "DO NOT recalculate or change any factor levels or points. " +
            "Copy the provided values exactly into the Factor Evaluation section."
        ));
        messages.add(new Message("user", prompt));

        OpenAIRequest openaiRequest = new OpenAIRequest("gpt-4", messages, true);
        openaiRequest.setMax_tokens(8000);

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

        // 7. Validate and correct the generated PD
        String validatedPD = enforceFactorConsistency(
            fullPD.toString(),
            factorLevels,
            factorPoints,
            totalPoints,
            finalGrade,
            gradeRange
        );

        Map<String, Object> finalResult = new HashMap<>();
        finalResult.put("evaluationStatement", evaluationStatement);
        finalResult.put("positionDescription", validatedPD);
        finalResult.put("finalGrade", finalGrade);
        finalResult.put("gradeRange", gradeRange);
        finalResult.put("totalPoints", totalPoints);
        finalResult.put("supervisoryLevel", supervisoryLevel);
        finalResult.put("factorsValidated", true);

        writer.println("data: {\"complete\":true,\"results\":" + objectMapper.writeValueAsString(finalResult) + "}\n");
        writer.println("data: [DONE]\n");
        writer.flush();

        System.out.println("=== COORDINATED GENERATION COMPLETE - VALUES LOCKED ===");

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
    }
}

/**
 * Extract factor levels from evaluation statement
 */
private Map<String, String> extractFactorLevels(String evaluation) {
    Map<String, String> levels = new HashMap<>();
    Pattern pattern = Pattern.compile("Factor\\s+(\\d+)[^\\n]*?Level\\s+(\\d+-\\d+)");
    Matcher matcher = pattern.matcher(evaluation);
    
    while (matcher.find()) {
        String factorNum = matcher.group(1);
        String level = matcher.group(2);
        levels.put(factorNum, level);
    }
    
    System.out.println("Extracted factor levels: " + levels);
    return levels;
}

/**
 * Extract factor points from evaluation statement
 */
private Map<String, Integer> extractFactorPoints(String evaluation) {
    Map<String, Integer> points = new HashMap<>();
    Pattern pattern = Pattern.compile("Factor\\s+(\\d+)[^\\n]*?Level\\s+\\d+-\\d+,\\s*(\\d+)\\s*Points");
    Matcher matcher = pattern.matcher(evaluation);
    
    while (matcher.find()) {
        String factorNum = matcher.group(1);
        int factorPoints = Integer.parseInt(matcher.group(2));
        points.put(factorNum, factorPoints);
    }
    
    System.out.println("Extracted factor points: " + points);
    return points;
}

/**
 * Enforce factor consistency in generated PD
 */
private String enforceFactorConsistency(String generatedPD,
                            Map<String, String> correctLevels,
                            Map<String, Integer> correctPoints,
                            int correctTotal,
                            String correctGrade,
                            String correctRange) {
    
    System.out.println("Enforcing factor consistency...");
    String validated = generatedPD;
    
    // Replace each factor with correct values
    for (int i = 1; i <= 9; i++) {
        String factorNum = String.valueOf(i);
        String correctLevel = correctLevels.get(factorNum);
        Integer correctPts = correctPoints.get(factorNum);
        
        if (correctLevel != null && correctPts != null) {
            // Find and replace factor headers with wrong values
            Pattern wrongPattern = Pattern.compile(
                "(Factor\\s+" + i + "\\s+[^\\n]*?)Level\\s+\\d+-\\d+,\\s*\\d+\\s*Points"
            );
            Matcher matcher = wrongPattern.matcher(validated);
            
            if (matcher.find()) {
                String replacement = matcher.group(1) + "Level " + correctLevel + ", " + correctPts + " Points";
                validated = matcher.replaceFirst(replacement);
                System.out.println("Corrected Factor " + i + " to Level " + correctLevel + ", " + correctPts + " pts");
            }
        }
    }
    
    // Replace total points, grade, and range
    validated = validated.replaceAll("\\*\\*Total Points:\\s*\\*\\*\\s*\\d+", 
                                    "**Total Points:** " + correctTotal);
    validated = validated.replaceAll("\\*\\*Final Grade:\\s*\\*\\*\\s*GS-\\d+", 
                                    "**Final Grade:** " + correctGrade);
    validated = validated.replaceAll("\\*\\*Grade Range:\\s*\\*\\*\\s*[\\d-]+", 
                                    "**Grade Range:** " + correctRange);
    
    System.out.println("Factor consistency enforced: Total=" + correctTotal + ", Grade=" + correctGrade);
    return validated;
}

/**
 * Extract the factor level for a given factor number from the evaluation statement.
 */
private String extractFactorLevel(String evaluation, String factorNum) {
    if (evaluation == null || factorNum == null) return null;
    Pattern pattern = Pattern.compile("Factor\\s+" + factorNum + "[^\\n]*?Level\\s+(\\d+-\\d+)");
    Matcher matcher = pattern.matcher(evaluation);
    if (matcher.find()) {
        return matcher.group(1);
    }
    return null;
}

/**
 * Get default factor levels for a grade (fallback)
 */
private Map<String, String> getDefaultFactorLevels(String grade) {
    Map<String, String> defaults = new HashMap<>();
    
    switch (grade.toUpperCase()) {
        case "GS-14":
            defaults.put("1", "1-8"); defaults.put("2", "2-5"); defaults.put("3", "3-5");
            defaults.put("4", "4-5"); defaults.put("5", "5-6"); defaults.put("6", "6-4");
            defaults.put("7", "7-4"); defaults.put("8", "8-1"); defaults.put("9", "9-2");
            break;
        case "GS-13":
            defaults.put("1", "1-7"); defaults.put("2", "2-4"); defaults.put("3", "3-4");
            defaults.put("4", "4-5"); defaults.put("5", "5-5"); defaults.put("6", "6-3");
            defaults.put("7", "7-3"); defaults.put("8", "8-1"); defaults.put("9", "9-1");
            break;
        default: // GS-12
            defaults.put("1", "1-6"); defaults.put("2", "2-4"); defaults.put("3", "3-4");
            defaults.put("4", "4-4"); defaults.put("5", "5-4"); defaults.put("6", "6-3");
            defaults.put("7", "7-3"); defaults.put("8", "8-1"); defaults.put("9", "9-1");
    }
    
    return defaults;
}

/**
 * Get default factor points for a grade (fallback)
 */
private Map<String, Integer> getDefaultFactorPoints(String grade) {
    Map<String, String> levels = getDefaultFactorLevels(grade);
    Map<String, Integer> points = new HashMap<>();
    
    for (Map.Entry<String, String> entry : levels.entrySet()) {
        String factorNum = entry.getKey();
        String level = entry.getValue();
        points.put(factorNum, getPointsForLevel(factorNum, level));
    }
    
    return points;
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

        // Collect original factor levels if provided (e.g., "Factor 7_originalLevel": "7-D")
        Map<String, String> originalLevels = new HashMap<>();
        for (int i = 1; i <= 9; i++) {
            String origKey = "Factor " + i + "_originalLevel";
            String origLevel = body.getOrDefault(origKey, null);
            if (origLevel != null) {
                originalLevels.put(String.valueOf(i), origLevel);
            }
        }

        // Get PDF context
        String pdfContext = getAutoPdfContext();

        // Build prompt (unchanged)
        String prompt = pdfContext + String.format("""
            Duties may be listed with percentages. Duties with higher percentages must be given greater weight and considered more important in your analysis. Percentages if present depict the importance of that duty.
            You are a federal HR classification specialist with deep expertise in OPM position classification standards.
            Use the official reference materials provided above to ensure accurate factor evaluation.
            
            You MUST be PRECISE and AGGRESSIVE in recognizing the true complexity level of work described.
            
            For each factor, provide a rationale that describes the work characteristics, duties, and required knowledge/skills.
            DO NOT mention factor level numbers (e.g., "1-6") or point values in the rationale.
            Focus on describing the nature of the work, complexity, independence, scope, and impact.
            Example (GOOD): "The position requires professional knowledge of criminal justice research methodologies, data collection and analysis techniques, and related laws and regulations. The incumbent must have the ability to apply this knowledge in complex situations, such as identifying trends and inconsistencies in data and formulating recommendations."
            Example (BAD): "This position requires a high level of knowledge (1-6) as it involves..."

            CRITICAL REQUIREMENTS:
            1. You MUST analyze ALL 9 factors - no exceptions
            2. Every factor must have a substantive rationale based on the duties provided and OPM standards
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
            
            MANDATORY: Return JSON with ALL 9 factors analyzed. Each rationale must reference the duties, 
            OPM standards provided, and explain the factor level assignment:
            
            {
              "Factor 1": {"level": "1-X", "points": NNNN, "rationale": "Based on duties showing [specific examples] and OPM standards, this position requires [knowledge level] because [detailed explanation]"},
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

                // --- PRESERVE FACTOR LETTER IF ORIGINAL HAD IT ---
                String origLevel = originalLevels.get(String.valueOf(i));
                if (origLevel != null && level != null) {
                    level = preserveFactorLetter(origLevel, level);
                }

                // For Factor 1, check for expert-level language and upgrade if needed
                if (i == 1 && rationale != null && (
                    rationale.toLowerCase().contains("expert") ||
                    rationale.toLowerCase().contains("authoritative") ||
                    rationale.toLowerCase().contains("advanced") ||
                    rationale.toLowerCase().contains("complex") ||
                    rationale.toLowerCase().contains("guidance")
                )) {
                    if (level.equals("1-1") || level.equals("1-2") || level.equals("1-3") ||
                        level.equals("1-4") || level.equals("1-5") || level.equals("1-6")) {
                        level = "1-7";
                        factor.put("level", level);
                        factor.put("points", FACTOR_POINTS.get("1").get(level));
                    }
                }

                Map<String, Integer> factorLevels = FACTOR_POINTS.get(String.valueOf(i));
                Integer correctPoints = factorLevels.get(level);

                if (correctPoints == null) {
                    level = findClosestValidLevel(String.valueOf(i), level, factorLevels);
                    correctPoints = factorLevels.get(level);
                }

                if (rationale == null || rationale.trim().isEmpty() ||
                    rationale.toLowerCase().contains("not addressed") ||
                    rationale.toLowerCase().contains("not impacted") ||
                    rationale.toLowerCase().contains("default")) {
                    rationale = generateSubstantiveRationale(i, level, duties);
                }

                Map<String, Object> validatedFactor = new LinkedHashMap<>();
                validatedFactor.put("header", "Factor " + i + " Level " + level + ", " + correctPoints + " Points");
                validatedFactor.put("content", factors.getOrDefault("Factor " + i, ""));
                validatedFactor.put("level", level);
                validatedFactor.put("points", correctPoints);
                validatedFactor.put("rationale", rationale);

                validatedFactors.put("Factor " + i, validatedFactor);
                totalPoints += correctPoints;

            } else {
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

        // --- ENFORCE GRADE ALIGNMENT IF NEEDED ---
        int minPoints = getMinPointsForGrade(finalGrade);
        int maxPoints = getMaxPointsForGrade(finalGrade);
        if (totalPoints < minPoints || totalPoints > maxPoints) {
            // Build a fake "evaluation statement" string to use your existing alignment logic
            StringBuilder eval = new StringBuilder();
            for (int i = 1; i <= 9; i++) {
                Map<String, Object> f = (Map<String, Object>) validatedFactors.get("Factor " + i);
                eval.append("Factor ").append(i)
                    .append(" Level ").append(f.get("level"))
                    .append(", ").append(f.get("points")).append(" Points\n");
            }
            String aligned = enforceExactGradeAlignment(eval.toString(), finalGrade, minPoints, maxPoints);

            // Parse the aligned result and update validatedFactors and totalPoints
            Pattern p = Pattern.compile("Factor\\s+(\\d+)\\s+Level\\s+(\\d+-\\d+),\\s*(\\d+)\\s*Points");
            Matcher m = p.matcher(aligned);
            int newTotal = 0;
            while (m.find()) {
                String factorNum = m.group(1);
                String level = m.group(2);
                int points = Integer.parseInt(m.group(3));
                Map<String, Object> f = (Map<String, Object>) validatedFactors.get("Factor " + factorNum);
                if (f != null) {
                    f.put("level", level);
                    f.put("points", points);
                }
                newTotal += points;
            }
            totalPoints = newTotal;
            gradeRange = calculateGradeRange(totalPoints);
            finalGrade = calculateFinalGrade(totalPoints);
        }

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
        case 1:
            if (isHighLevel) {
                return "This position requires expert-level professional knowledge at level " + level + ". The duties demonstrate mastery of specialized principles, advanced analytical capabilities, and authoritative expertise needed to address complex organizational challenges and provide definitive guidance.";
            } else if (isMidLevel) {
                return "The duties require professional knowledge at level " + level + " involving independent application of complex concepts, analytical thinking, and specialized expertise to solve varied problems and make professional judgments.";
            } else {
                return "The position requires developing professional knowledge at level " + level + " to perform analytical work, apply established methods, and gain expertise in the field under appropriate guidance.";
            }
            
        case 2:
            if (level.equals("2-5")) {
                return "Supervisory controls at level " + level + " reflect extensive independence with administrative direction only. The incumbent sets priorities, plans work approaches, and operates with full authority for professional decisions within broad policy guidelines.";
            } else if (level.equals("2-4")) {
                return "The duties indicate considerable independence at level " + level + " with supervisor providing general guidance on priorities and approaches. The incumbent plans work, makes decisions, and operates with significant autonomy.";
            } else if (level.equals("2-3")) {
                return "Supervisory controls at level " + level + " provide specific guidance on complex assignments while allowing independence on routine work. The incumbent receives clear direction on new or difficult problems.";
            } else {
                return "The position operates under closer supervision at level " + level + " with regular guidance and review of work approaches and decisions.";
            }
            
        case 3:
            if (level.contains("5")) {
                return "Guidelines assessment at level " + level + " reflects the need to interpret broad policy, establish precedents, and develop new approaches where specific guidance doesn't exist. Extensive judgment required in applying principles.";
            } else if (level.contains("4")) {
                return "The duties require significant interpretation of guidelines at level " + level + " including adaptation of policies to new situations and development of approaches for complex problems.";
            } else {
                return "Guidelines at level " + level + " provide adequate direction for most work with some interpretation required for unusual situations or complex applications.";
            }
            
        case 4:
            if (level.contains("6") || level.contains("5")) {
                return "Work complexity at level " + level + " involves highly complex, unprecedented problems requiring innovative solutions, extensive analysis of interrelated factors, and creative problem-solving approaches.";
            } else if (level.contains("4")) {
                return "The duties present complex problems at level " + level + " requiring analytical thinking, consideration of multiple factors, and development of solutions for varied situations.";
            } else {
                return "Work complexity at level " + level + " involves moderately complex problems with some variety requiring analytical skills and problem-solving within established frameworks.";
            }
            
        case 5:
            if (level.contains("6")) {
                return "Scope and effect at level " + level + " demonstrates organization-wide and external impact affecting major policies, programs, and external stakeholder relationships with significant consequences for agency mission.";
            } else if (level.contains("5")) {
                return "The work scope at level " + level + " affects senior leadership decisions, major program directions, and organizational policies with substantial impact on agency operations and external relationships.";
            } else if (level.contains("4")) {
                return "Scope and effect at level " + level + " impacts multiple organizational units, program operations, and affects significant management decisions and resource allocation.";
            } else {
                return "The work scope at level " + level + " affects unit operations, project outcomes, and contributes to broader program objectives with moderate organizational impact.";
            }
            
        case 6:
            if (level.contains("4")) {
                return "Personal contacts at level " + level + " include senior executives, high-level external officials, key stakeholders, and industry leaders requiring sophisticated communication and relationship management skills.";
            } else if (level.contains("3")) {
                return "The position involves contacts at level " + level + " with management officials, external professionals, contractors, and stakeholders requiring diplomatic and professional interaction skills.";
            } else {
                return "Personal contacts at level " + level + " include staff throughout the organization, some external contacts, and various professional counterparts requiring effective communication skills.";
            }
            
        case 7:
            if (level.contains("4")) {
                return "Contact purposes at level " + level + " involve influencing policy decisions, negotiating critical agreements, resolving complex conflicts, and representing organizational positions on significant issues.";
            } else if (level.contains("3")) {
                return "The purpose of contacts at level " + level + " includes coordinating complex activities, influencing decisions, resolving problems, and providing authoritative information and guidance.";
            } else if (level.contains("2")) {
                return "Contact purposes at level " + level + " involve planning work, coordinating activities, exchanging information, and resolving routine problems through collaborative efforts.";
            } else {
                return "Contacts at level " + level + " primarily involve information exchange, clarification of requirements, and routine coordination activities.";
            }
            
        case 8:
            return "Physical demands at level " + level + " reflect typical professional office work with sedentary requirements, occasional travel, and standard technology use appropriate for analytical and administrative responsibilities.";
            
        case 9:
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
        case 1:
            if (isGS15Level) return "1-8";
            else if (isGS14Level) return "1-7";
            else if (isGS13Level) return "1-6";
            else if (isGS12Level) return "1-5";
            else if (isGS11Level) return "1-5";
            else if (isGS9Level) return "1-4";
            else return "1-4";
            
        case 2:
            if (isGS15Level || isGS14Level) return "2-5";
            else if (isGS13Level || isGS12Level) return "2-4";
            else if (isGS11Level) return "2-3";
            else if (isGS9Level) return "2-2";
            else return "2-3";
            
        case 3:
            if (isGS15Level) return "3-5";
            else if (isGS14Level || isGS13Level) return "3-4";
            else if (isGS12Level || isGS11Level) return "3-3";
            else return "3-2";
            
        case 4:
            if (isGS15Level) return "4-6";
            else if (isGS14Level) return "4-5";
            else if (isGS13Level || isGS12Level) return "4-4";
            else if (isGS11Level) return "4-3";
            else return "4-2";
            
        case 5:
            if (isGS15Level) return "5-6";
            else if (isGS14Level) return "5-5";
            else if (isGS13Level) return "5-4";
            else if (isGS12Level) return "5-3";
            else if (isGS11Level) return "5-3";
            else return "5-2";
            
        case 6:
            if (isGS15Level || isGS14Level) return "6-4";
            else if (isGS13Level || isGS12Level) return "6-3";
            else return "6-2";
            
        case 7:
            if (isGS15Level || isGS14Level) return "7-4";
            else if (isGS13Level || isGS12Level) return "7-3";
            else if (isGS11Level) return "7-2";
            else return "7-1";
            
        case 8:
            return "8-1";
            
        case 9:
            if (isGS15Level || isGS14Level || isGS13Level) return "9-2";
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
        
        if (totalPoints <= 1350) totalPoints = 1355;
        else if (totalPoints <= 1850) totalPoints = 1855;
        else totalPoints = 2355;

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

    // Short, focused prompt with keyword guidance for supervisory fit
    String prompt = String.format("""
    Duties may be listed with percentages. Duties with higher percentages must be given greater weight and considered more important in your analysis.
    You are a federal HR classification specialist.

    Evaluate the following position details and determine the most appropriate federal job series and title based on the duties provided. Weigh higher-percentage duties more heavily.

    Duties: %s
    GS Grade: %s
    Proposed Job Series: %s
    Proposed Job Title: %s
    Supervisory Level: %s

    When evaluating if the supervisory level fits, use these keyword indicators:
    - **Supervisory**: supervises, manages, directs, oversees, assigns work, evaluates performance, hires, fires, conducts reviews, approves leave, sets team goals, responsible for staff, team lead, leadership, coaching, mentoring, staff development, resource allocation, disciplinary actions, performance appraisals, hiring, onboarding, delegating tasks, managing teams, conducting meetings, providing feedback, handling grievances, employee relations, policy implementation, compliance oversight, budget management, project management, stakeholder engagement, organizational development, performance improvement, process improvement, quality assurance, compliance, audit, risk management, strategic planning.
    - **Non-Supervisory**: performs tasks, provides support, assists, completes assignments, individual contributor, technical work, analysis, research, reporting, documentation, customer service, data entry, maintains records, prepares reports, follows procedures, supports team, executes tasks, implements, monitors, coordinates, communicates, collaborates, operates equipment, maintains systems, provides information, reviews documents, collects data, analyzes data, prepares materials, drafts correspondence, schedules, organizes, files, tracks, updates, reviews, responds, assists customers, provides technical support.

    Your response must include:
    - **Best-Fitting Series and Title:** State the recommended job series and title based on OPM standards.
    - **Evaluation:** Clearly state if the proposed job series is appropriate or not, and explain why.
    - **Supervisory Level Fit:** Clearly state if the chosen supervisory level is appropriate for the duties described, and explain why or why not, referencing the keywords above.
    - **Explanation:** Summarize your reasoning and justification in 2–3 sentences.

    Format your response as follows:

    **Title and Series Determination**

    **Best-Fitting Series and Title:**  
    [Recommended job series and title]

    **Evaluation:**  
    [Is the proposed job series appropriate? Why or why not?]

    **Supervisory Level Fit:**  
    [Is the chosen supervisory level appropriate for the duties? Why or why not? Reference the keywords.]

    **Explanation:**  
    [2–3 sentences summarizing your reasoning and justification.]
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
        String finalUrl = url.toString();
        if (finalUrl.endsWith("&")) finalUrl = finalUrl.substring(0, finalUrl.length() - 1);

        System.out.println("Making request to: " + finalUrl);

        // RestTemplate call
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization-Key", usajobsApiKey);
        headers.set("User-Agent", usajobsUserAgent);
        headers.set("Accept", "application/json");
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(finalUrl, HttpMethod.GET, entity, String.class);
        System.out.println("Response status: " + response.getStatusCode());

        // Return ONLY the body with minimal headers
        return ResponseEntity
                .ok()  // Always return 200 OK
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.getBody());

    } catch (HttpClientErrorException | HttpServerErrorException e) {
        System.err.println("HTTP error in proxyUsaJobs: " + e.getStatusCode() + " - " + e.getMessage());
        return ResponseEntity
                .ok()  // Return 200 even for errors
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"USAJobs API error\",\"status\":" + e.getStatusCode().value() + 
                    ",\"message\":\"" + e.getMessage().replaceAll("\"", "'") + "\"}");
    } catch (Exception e) {
        System.err.println("Error in proxyUsaJobs: " + e.getMessage());
        e.printStackTrace();
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"Internal server error\",\"message\":\"" + e.getMessage().replaceAll("\"", "'") + "\"}");
    }
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

private String preserveFactorLetter(String oldLevel, String newLevel) {
    // If oldLevel is like "7-D" and newLevel is like "7-2", convert newLevel to "7-B"
    if (oldLevel != null && oldLevel.matches("\\d+-[A-Z]") && newLevel != null && newLevel.matches("\\d+-\\d+")) {
        int num = Integer.parseInt(newLevel.split("-")[1]);
        // Map 1->A, 2->B, 3->C, 4->D, etc.
        char letter = (char) ('A' + num - 1);
        return newLevel.split("-")[0] + "-" + letter;
    }
    // If oldLevel is like "7-2" and newLevel is like "7-D", convert newLevel to "7-2"
    if (oldLevel != null && oldLevel.matches("\\d+-\\d+") && newLevel != null && newLevel.matches("\\d+-[A-Z]")) {
        char letter = newLevel.split("-")[1].charAt(0);
        int num = (letter - 'A') + 1;
        return newLevel.split("-")[0] + "-" + num;
    }
    // Otherwise, keep newLevel as is
    return newLevel;
}

@PostMapping("/upload-pdf")
public ResponseEntity<Map<String, String>> uploadPdf(@RequestParam("file") MultipartFile file,
                                                @RequestParam(value = "type", defaultValue = "reference") String type) {
    try {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file provided"));
        }

        // Validate file type
        String contentType = file.getContentType();
        if (contentType == null || !contentType.equals("application/pdf")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only PDF files are allowed"));
        }

        // Create pdfs directory if it doesn't exist
        File pdfDir = new File("src/main/resources/pdfs");
        if (!pdfDir.exists()) {
            pdfDir.mkdirs();
        }

        // Generate unique filename with type prefix
        String originalFilename = file.getOriginalFilename();
        String timestamp = String.valueOf(System.currentTimeMillis());
        String filename = type + "-" + timestamp + "-" + originalFilename;
        
        // Save file
        File destinationFile = new File(pdfDir, filename);
        file.transferTo(destinationFile);

        // Extract text preview for validation
        String textPreview = extractPdfText(destinationFile.getAbsolutePath());
        String preview = textPreview.length() > 500 ? 
            textPreview.substring(0, 500) + "..." : textPreview;

        System.out.println("PDF uploaded successfully: " + filename);
        System.out.println("Text preview: " + preview);

        return ResponseEntity.ok(Map.of(
            "message", "PDF uploaded successfully",
            "filename", filename,
            "type", type,
            "size", String.valueOf(file.getSize()),
            "textPreview", preview
        ));

    } catch (Exception e) {
        System.err.println("Error uploading PDF: " + e.getMessage());
        return ResponseEntity.status(500).body(Map.of("error", "Failed to upload PDF: " + e.getMessage()));
    }
}

@PostMapping("/generate-common-duty")
    public Map<String, String> generateCommonDuty(@RequestBody Map<String, String> body) throws Exception {
        String jobSeries = body.getOrDefault("jobSeries", "");
        String positionTitle = body.getOrDefault("positionTitle", "");
        if (!"0343".equals(jobSeries) || positionTitle.isBlank()) {
                return Map.of("duty", "");
            }
            String prompt = String.format(
                "You are an expert in federal HR classification. " +
                "Write one specific, unique, and commonly expected duty for a position in the 0343 series (Management and Program Analyst) " +
                "with the title '%s'. The duty must be clear, actionable, and reflect a core responsibility for this series and title. " +
                "Return only the duty, no explanation, no numbering, no extra text.",
                positionTitle
                );
            String duty = callOpenAIWithTimeout(prompt, 10);
            return Map.of("duty", duty.trim());
            }


/**
 * List available PDF files
 */
@GetMapping("/list-pdfs")
public ResponseEntity<Map<String, Object>> listPdfs() {
    try {
        File pdfDir = new File("src/main/resources/pdfs");
        if (!pdfDir.exists()) {
            return ResponseEntity.ok(Map.of("pdfs", new ArrayList<>(), "message", "No PDF directory found"));
        }

        File[] pdfFiles = pdfDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));
        List<Map<String, Object>> pdfList = new ArrayList<>();

        if (pdfFiles != null) {
            for (File pdf : pdfFiles) {
                Map<String, Object> pdfInfo = new HashMap<>();
                pdfInfo.put("filename", pdf.getName());
                pdfInfo.put("size", pdf.length());
                pdfInfo.put("lastModified", new Date(pdf.lastModified()));
                
                // Determine type from filename prefix
                String filename = pdf.getName();
                if (filename.startsWith("omp-")) pdfInfo.put("type", "OMP Standards");
                else if (filename.startsWith("factor-")) pdfInfo.put("type", "Factor Evaluation");
                else if (filename.startsWith("series-")) pdfInfo.put("type", "Series Standards");
                else pdfInfo.put("type", "Reference");
                
                pdfList.add(pdfInfo);
            }
        }

        return ResponseEntity.ok(Map.of(
            "pdfs", pdfList,
            "total", pdfList.size()
        ));

    } catch (Exception e) {
        return ResponseEntity.status(500).body(Map.of("error", "Failed to list PDFs: " + e.getMessage()));
    }
}

/**
 * Delete a PDF file
 */
@DeleteMapping("/delete-pdf/{filename}")
public ResponseEntity<Map<String, String>> deletePdf(@PathVariable String filename) {
    try {
        File pdfFile = new File("src/main/resources/pdfs/" + filename);
        if (!pdfFile.exists()) {
            return ResponseEntity.notFound().build();
        }

        if (pdfFile.delete()) {
            return ResponseEntity.ok(Map.of("message", "PDF deleted successfully", "filename", filename));
        } else {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to delete PDF"));
        }

    } catch (Exception e) {
        return ResponseEntity.status(500).body(Map.of("error", "Failed to delete PDF: " + e.getMessage()));
    }
}

}