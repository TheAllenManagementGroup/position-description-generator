package com.example.pdgenerator.service;

import com.example.pdgenerator.request.PdRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Enhanced service class for generating high-quality federal position descriptions
 * with proper federal HR standards, detailed factor analysis, and PDF integration using OpenAI API.
 */
@Service
public class PdService {

    @Value("${openai.api.key}")
    private String openaiApiKey;

    @Autowired
    private PdfProcessingService pdfProcessingService;

    // Cache for frequently used PDF content
    private static final Map<String, String> pdfCache = new HashMap<>();

    /**
     * Inner classes for OpenAI API requests
     */
    public static class OpenAIRequest {
        private String model;
        private List<Message> messages;
        private boolean stream;
        @JsonProperty("max_tokens")
        private int maxTokens;
        private double temperature;

        public OpenAIRequest(String model, List<Message> messages, boolean stream) {
            this.model = model;
            this.messages = messages;
            this.stream = stream;
            this.maxTokens = 4000;
            this.temperature = 0.3;
        }

        // Getters and setters
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public List<Message> getMessages() { return messages; }
        public void setMessages(List<Message> messages) { this.messages = messages; }
        public boolean isStream() { return stream; }
        public void setStream(boolean stream) { this.stream = stream; }
        public int getMaxTokens() { return maxTokens; }
        @JsonProperty("max_tokens")
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
    }

    public static class Message {
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
     * Getter for OpenAI API key (for controller access)
     */
    public String getOpenaiApiKey() {
        return openaiApiKey;
    }

    /**
     * Enhanced buildPrompt method that includes PDF content
     */
    public String buildPrompt(PdRequest request) {
        try {
            // Always use PDF-enhanced version
            return buildComprehensivePromptWithPDFs(request);
        } catch (Exception e) {
            System.err.println("Error in buildComprehensivePromptWithPDFs: " + e.getMessage());
            return buildBasicPrompt(request);
        }
    }

    /**
     * Get PDF context string to prepend to ALL AI prompts
     */
    private String getPdfContextForPrompts() {
        StringBuilder context = new StringBuilder();
        
        context.append("REFERENCE MATERIALS - Use these official OPM standards for accurate classification:\n\n");
        
        // OPM Classification Standards
        String ompStandards = getOmpStandardsContent();
        if (!ompStandards.isEmpty() && !ompStandards.contains("Use official")) {
            context.append("OPM CLASSIFICATION STANDARDS:\n").append(ompStandards).append("\n\n");
        }
        
        // Factor Evaluation Guide
        String factorGuide = getFactorEvaluationGuide();
        if (!factorGuide.isEmpty() && !factorGuide.contains("Use the nine-factor")) {
            context.append("FACTOR EVALUATION SYSTEM:\n").append(factorGuide).append("\n\n");
        }
        
        context.append("Apply these official standards to all analysis and recommendations.\n\n");
        context.append("---\n\n");
        
        return context.toString();
    }

    /**
     * Enhanced method to add PDF context to any prompt
     */
    private String enhancePromptWithPdfs(String originalPrompt) {
        String pdfContext = getPdfContextForPrompts();
        return pdfContext + originalPrompt;
    }

    /**
     * Enhanced prompt that incorporates OPM PDF references for accurate classification
     */
    private String buildComprehensivePromptWithPDFs(PdRequest request) {
        String jobSeries = request.getJobSeries() != null ? request.getJobSeries() : "0610";
        String subJobSeries = request.getSubJobSeries() != null ? request.getSubJobSeries() : "Analyst";
        String federalAgency = request.getFederalAgency() != null ? request.getFederalAgency() : "Department of Homeland Security";
        String subOrganization = request.getSubOrganization() != null ? request.getSubOrganization() : "Federal Emergency Management Agency (FEMA)";
        String lowestOrg = request.getLowestOrg() != null ? request.getLowestOrg() : "";
        String historicalData = request.getHistoricalData() != null ? request.getHistoricalData() : "Administrative and analytical duties";
        String gsGrade = request.getGsGrade() != null ? request.getGsGrade() : "GS-13";
        String gradeRange = request.getGradeRange() != null ? request.getGradeRange() : "";
        String totalPoints = request.getTotalPoints() != null ? request.getTotalPoints().toString() : "";
        String supervisoryLevel = request.getSupervisoryLevel() != null ? request.getSupervisoryLevel() : "Non-Supervisory";

        // Get relevant PDF content for context
        String ompStandards = getOmpStandardsContent();
        String factorEvaluationGuide = getFactorEvaluationGuide();
        String seriesSpecificContent = getSeriesSpecificContent(jobSeries);

        return String.format("""
        You are a federal HR classification specialist with expertise in creating comprehensive position descriptions that meet OMP standards.

        REFERENCE MATERIALS - USE THESE FOR ACCURATE CLASSIFICATION:
        
        OMP CLASSIFICATION STANDARDS:
        %s

        FACTOR EVALUATION SYSTEM GUIDE:
        %s

        SERIES %s SPECIFIC STANDARDS:
        %s

        CRITICAL REQUIREMENTS - USE EXACT VALUES PROVIDED:
        - Final Grade: %s (DO NOT CHANGE OR RECALCULATE THIS)
        - Grade Range: %s (DO NOT CHANGE THIS)  
        - Total Points: %s (DO NOT CHANGE THIS)
        
        These values are from official OMP factor evaluation and MUST be used exactly as provided.

        POSITION DETAILS:
        Job Series: GS-%s
        Position Title: %s
        Agency: %s
        Organization: %s %s
        Supervisory Level: %s
        Duties/Responsibilities Context: %s

        REQUIRED STRUCTURE WITH SUBSTANTIAL CONTENT:

        **HEADER:**
        U.S. %s
        %s
        %s
        GS-%s-XX (Use appropriate grade step)
        Organizational Title: %s

        **INTRODUCTION:** 
        Write a comprehensive 4-6 sentence introduction explaining the position's critical role, organizational context, key responsibilities, and impact on the agency's mission. Include specific technical requirements and working relationships based on the reference materials.

        **MAJOR DUTIES:** 
        Create 3-4 comprehensive major duty statements that align with OMP standards and the reference materials:
        - Include realistic percentages that total 100%% (e.g., 40%%, 30%%, 20%%, 10%%)
        - Contain 3-4 detailed sentences each with specific technical tasks
        - Include measurable outcomes and professional interactions
        - Use proper federal terminology from the reference materials

        **FACTOR EVALUATION - COMPLETE ANALYSIS:**
        Using the OMP Factor Evaluation System Guide provided above, create detailed factor evaluations for all 9 factors that justify the %s grade with %s total points:

        **Factor 1 - Knowledge Required by the Position Level 1-X, XXXX Points**
        Reference the specific knowledge requirements from the series standards and write 3-4 paragraphs explaining the professional knowledge, technical expertise, and educational requirements.

        **Factor 2 - Supervisory Controls Level 2-X, XXX Points**
        Using the Factor Evaluation System Guide, detail 2-3 paragraphs on supervision received, independence level, and decision-making authority.

        [Continue for all 9 factors using the reference materials...]

        **EVALUATION SUMMARY:**
        **Total Points: %s**
        **Final Grade: %s** 
        **Grade Range: %s**

        **CONDITIONS OF EMPLOYMENT:**
        Based on the series standards and OMP guidelines, create comprehensive sections covering required certifications, security clearance, and other employment conditions.

        **TITLE AND SERIES DETERMINATION:**
        Using the series %s standards provided, write 2-3 detailed paragraphs explaining the rationale for series assignment and how duties align with official standards.

        **FAIR LABOR STANDARDS ACT DETERMINATION:**
        Provide specific justification based on OMP guidelines and the professional duties outlined in the reference materials.

        CRITICAL INSTRUCTIONS:
        - Use the reference materials to ensure accuracy and compliance with official standards
        - Use the EXACT grade values provided (%s, %s points, %s range)
        - Create factor evaluations that align with official OMP criteria from the reference materials
        - Generate substantial, professional content with no placeholders
        - Ensure consistency with official classification standards
        """,
        ompStandards, factorEvaluationGuide, seriesSpecificContent, jobSeries,
        gsGrade, gradeRange, totalPoints,
        jobSeries, subJobSeries, federalAgency, subOrganization, lowestOrg, supervisoryLevel, historicalData,
        federalAgency, subOrganization, lowestOrg, subJobSeries, jobSeries, subJobSeries,
        gsGrade, totalPoints,
        totalPoints, gsGrade, gradeRange,
        jobSeries,
        gsGrade, totalPoints, gradeRange
        );
    }

    /**
     * Get OMP Classification Standards content from PDF
     */
    private String getOmpStandardsContent() {
        String cacheKey = "omp_standards";
        if (pdfCache.containsKey(cacheKey)) {
            return pdfCache.get(cacheKey);
        }

        try {
            // Try to load from resources directory
            String pdfPath = "src/main/resources/pdfs/omp-classification-standards.pdf";
            String content = pdfProcessingService.extractTextFromPdfPath(pdfPath);
            
            // Extract relevant sections and truncate to manageable size
            String relevantContent = extractRelevantSections(content, "classification", "factor evaluation");
            String truncatedContent = truncateText(relevantContent, 2000);
            
            pdfCache.put(cacheKey, truncatedContent);
            return truncatedContent;
        } catch (Exception e) {
            System.err.println("Failed to load OMP standards PDF: " + e.getMessage());
            return "Use official OMP classification standards for accurate factor evaluation and grade determination.";
        }
    }

    /**
     * Get Factor Evaluation System Guide content
     */
    private String getFactorEvaluationGuide() {
        String cacheKey = "factor_evaluation_guide";
        if (pdfCache.containsKey(cacheKey)) {
            return pdfCache.get(cacheKey);
        }

        try {
            String pdfPath = "src/main/resources/pdfs/factor-evaluation-system.pdf";
            String content = pdfProcessingService.extractTextFromPdfPath(pdfPath);
            
            String relevantContent = extractRelevantSections(content, "factor", "points", "level");
            String truncatedContent = truncateText(relevantContent, 1500);
            
            pdfCache.put(cacheKey, truncatedContent);
            return truncatedContent;
        } catch (Exception e) {
            System.err.println("Failed to load Factor Evaluation Guide PDF: " + e.getMessage());
            return "Use the nine-factor evaluation system with specific point values for each factor level.";
        }
    }

    /**
     * Get series-specific content based on job series
     */
    private String getSeriesSpecificContent(String jobSeries) {
        String cacheKey = "series_" + jobSeries;
        if (pdfCache.containsKey(cacheKey)) {
            return pdfCache.get(cacheKey);
        }

        try {
            // Try to find series-specific PDF
            String pdfPath = String.format("src/main/resources/pdfs/series-%s-standards.pdf", jobSeries);
            String content = pdfProcessingService.extractTextFromPdfPath(pdfPath);
            
            String truncatedContent = truncateText(content, 1000);
            pdfCache.put(cacheKey, truncatedContent);
            return truncatedContent;
        } catch (Exception e) {
            System.err.println("No series-specific PDF found for " + jobSeries + ": " + e.getMessage());
            return "Apply standard professional series requirements for GS-" + jobSeries + " positions.";
        }
    }

    /**
     * Extract relevant sections from PDF content based on keywords
     */
    private String extractRelevantSections(String fullText, String... keywords) {
        String[] paragraphs = fullText.split("\\n\\s*\\n");
        StringBuilder relevantContent = new StringBuilder();
        
        for (String paragraph : paragraphs) {
            String lowerParagraph = paragraph.toLowerCase();
            for (String keyword : keywords) {
                if (lowerParagraph.contains(keyword.toLowerCase())) {
                    relevantContent.append(paragraph).append("\n\n");
                    break;
                }
            }
        }
        
        return relevantContent.length() > 0 ? relevantContent.toString() : fullText;
    }

    /**
     * Truncate text to stay within token limits
     */
    private String truncateText(String text, int maxTokens) {
        // Rough estimation: 1 token ≈ 4 characters
        int maxChars = maxTokens * 4;
        if (text.length() <= maxChars) {
            return text;
        }
        
        // Try to truncate at a sentence boundary
        String truncated = text.substring(0, maxChars);
        int lastSentence = truncated.lastIndexOf(". ");
        if (lastSentence > maxChars * 0.8) {
            return truncated.substring(0, lastSentence + 1);
        }
        
        return truncated + "...";
    }

    /**
     * Enhanced method to analyze uploaded PDFs for classification guidance
     */
    public Map<String, Object> analyzePdfForClassification(MultipartFile pdfFile, String targetGrade) throws Exception {
        String pdfText = pdfProcessingService.extractTextFromPdf(pdfFile);
        return pdfProcessingService.analyzePdfContent(pdfText, targetGrade);
    }

    /**
     * Enhanced prompt building for evaluation statements with PDF context
     */
    public String buildEvaluationPromptWithPDFs(String duties, String gsGrade, String gradeRange, 
                                               String totalPoints, String supervisoryLevel, 
                                               String jobSeries, String jobTitle) {
        String factorGuide = getFactorEvaluationGuide();
        
        return String.format("""
            You are a federal HR classification specialist creating an official OMP factor evaluation.

            REFERENCE MATERIAL - FACTOR EVALUATION SYSTEM:
            %s

            CRITICAL: Use ONLY these exact values:
            - Final Grade: %s
            - Grade Range: %s  
            - Total Points: %s

            Position Details:
            - Supervisory Level: %s
            - Job Series: %s
            - Job Title: %s

            Duties:
            %s

            Using the Factor Evaluation System reference material above, create detailed evaluations for all 9 factors that justify the %s grade with %s total points. Each factor must include the correct level, points, and rationale based on official OMP standards.

            Format each factor as:
            **Factor X - [Factor Name] Level X-X, XXX Points**
            [3-4 sentence rationale referencing the official standards]

            End with:
            **Total Points: %s**
            **Final Grade: %s**
            **Grade Range: %s**
            """, 
            factorGuide, gsGrade, gradeRange, totalPoints, supervisoryLevel, jobSeries, jobTitle, 
            duties, gsGrade, totalPoints, totalPoints, gsGrade, gradeRange);
    }

    // Keep existing methods for backward compatibility
    private String buildBasicPrompt(PdRequest request) {
        String jobSeries = request.getJobSeries() != null ? request.getJobSeries() : "0343";
        String subJobSeries = request.getSubJobSeries() != null ? request.getSubJobSeries() : "Management Analyst";
        String federalAgency = request.getFederalAgency() != null ? request.getFederalAgency() : "Department of Homeland Security";
        String historicalData = request.getHistoricalData() != null ? request.getHistoricalData() : "Administrative duties";
        
        return String.format("""
            Write a federal position description for a %s (GS-%s) position at %s.
            
            Job duties include: %s
            
            Create a complete position description with:
            - Header with agency and title
            - Introduction paragraph
            - 3-4 major duties with percentages
            - All 9 classification factors with specific point values
            - Total Points, Grade Range, and Final Grade
            - Conditions of employment
            - Classification determination
            
            Use real content, not placeholders. Make it professional and complete.
            """,
            subJobSeries, jobSeries, federalAgency, historicalData
        );
    }

    // Existing methods remain the same
    public void streamPD(PdRequest request, PrintWriter writer) {
        try {
            String prompt = buildPrompt(request);
            System.out.println("Using enhanced prompt with PDF references, length: " + prompt.length());
            
            // Create OpenAI API request
            List<Message> messages = new ArrayList<>();
            messages.add(new Message("system",
                "You are an expert federal HR classification specialist with access to official OMP standards. " +
                "Create complete, professional position descriptions using real content based on official guidance."
            ));
            messages.add(new Message("user", prompt));

            OpenAIRequest openaiRequest = new OpenAIRequest("gpt-4", messages, true);
            openaiRequest.setTemperature(0.3);
            
            ObjectMapper objectMapper = new ObjectMapper();
            String requestBody = objectMapper.writeValueAsString(openaiRequest);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + openaiApiKey)
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            HttpResponse<InputStream> response = client.send(httpRequest,
                HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
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
                                            String jsonLine = "{\"response\":\"" + escapeJson(content) + "\"}";
                                            writer.println(jsonLine);
                                            writer.flush();
                                        }
                                    }
                                } catch (Exception e) {
                                    System.err.println("Error parsing JSON: " + e.getMessage());
                                }
                            }
                        }
                    }
                }
            } else {
                String errorMsg = "{\"response\":\"Error: OpenAI API returned status " + response.statusCode() + "\"}";
                writer.println(errorMsg);
                writer.flush();
            }
        } catch (Exception e) {
            System.err.println("Exception in streamPD: " + e.getMessage());
            String errorMsg = "{\"response\":\"Error: " + escapeJson(e.getMessage()) + "\"}";
            writer.println(errorMsg);
        }
    }

    // Keep existing utility methods
    public String callOpenAI(String prompt) throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system",
            "You are an expert federal HR classification specialist with access to official standards."
        ));
        messages.add(new Message("user", prompt));

        OpenAIRequest openaiRequest = new OpenAIRequest("gpt-3.5-turbo", messages, false);
        openaiRequest.setMaxTokens(3000);
        openaiRequest.setTemperature(0.3);

        ObjectMapper objectMapper = new ObjectMapper();
        String requestBody = objectMapper.writeValueAsString(openaiRequest);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
            .header("Authorization", "Bearer " + openaiApiKey)
            .header("Content-Type", "application/json")
            .timeout(java.time.Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new Exception("OpenAI API returned status: " + response.statusCode());
        }

        JsonNode responseJson = objectMapper.readTree(response.body());
        if (responseJson.has("choices") && responseJson.get("choices").size() > 0) {
            JsonNode choice = responseJson.get("choices").get(0);
            if (choice.has("message") && choice.get("message").has("content")) {
                return choice.get("message").get("content").asText().trim();
            }
        }
        
        throw new Exception("Invalid response format from OpenAI API");
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}