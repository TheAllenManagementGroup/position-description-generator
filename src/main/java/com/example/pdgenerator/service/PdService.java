package com.example.pdgenerator.service;

import com.example.pdgenerator.request.PdRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.util.List;
import java.util.ArrayList;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Enhanced service class for generating high-quality federal position descriptions
 * with proper federal HR standards and detailed factor analysis using OpenAI API.
 */
@Service
public class PdService {

    @Value("${openai.api.key}")
    private String openaiApiKey;

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
            this.temperature = 0.3; // Lower temperature for more consistent output
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
     * Builds a focused, effective prompt for generating federal position descriptions
     */
    public String buildPrompt(PdRequest request) {
        try {
            // Always use the simplified, effective prompt
            return buildComprehensivePrompt(request);
        } catch (Exception e) {
            System.err.println("Error in buildComprehensivePrompt: " + e.getMessage());
            // Return basic fallback prompt
            return buildBasicPrompt(request);
        }
    }

    /**
     * Simplified but effective prompt that produces real content
     */
    private String buildComprehensivePrompt(PdRequest request) {
    String jobSeries = request.getJobSeries() != null ? request.getJobSeries() : "0610";
    String subJobSeries = request.getSubJobSeries() != null ? request.getSubJobSeries() : "Pediatric Nurse";
    String federalAgency = request.getFederalAgency() != null ? request.getFederalAgency() : "Department of Homeland Security";
    String subOrganization = request.getSubOrganization() != null ? request.getSubOrganization() : "Federal Emergency Management Agency (FEMA)";
    String lowestOrg = request.getLowestOrg() != null ? request.getLowestOrg() : "";
    String historicalData = request.getHistoricalData() != null ? request.getHistoricalData() : "Administrative and analytical duties";
    String gsGrade = request.getGsGrade() != null ? request.getGsGrade() : "GS-13";
    String gradeRange = request.getGradeRange() != null ? request.getGradeRange() : "";
    String totalPoints = request.getTotalPoints() != null ? request.getTotalPoints().toString() : "";
    String supervisoryLevel = request.getSupervisoryLevel() != null ? request.getSupervisoryLevel() : "Non-Supervisory";

    return String.format("""

        You are a federal HR classification specialist with expertise in creating comprehensive position descriptions that meet OPM standards.

        CRITICAL REQUIREMENTS - USE EXACT VALUES PROVIDED:
        - Final Grade: %s (DO NOT CHANGE OR RECALCULATE THIS)
        - Grade Range: %s (DO NOT CHANGE THIS)  
        - Total Points: %s (DO NOT CHANGE THIS)
        
        These values are from official OPM factor evaluation and MUST be used exactly as provided.

        Create a COMPLETE, PROFESSIONAL federal position description using the EXACT format shown in official OPM samples.

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
        Write a comprehensive 4-6 sentence introduction explaining the position's critical role, organizational context, key responsibilities, and impact on the agency's mission. Include specific technical requirements and working relationships.

        **MAJOR DUTIES:** 
        Create 3-4 comprehensive major duty statements that:
        - Include realistic percentages that total 100%% (e.g., 40%%, 30%%, 20%%, 10%%)
        - Contain 3-4 detailed sentences each with specific technical tasks
        - Include measurable outcomes and professional interactions
        - Use proper federal terminology throughout

        **FACTOR EVALUATION - COMPLETE ANALYSIS:**
        Create detailed factor evaluations for all 9 factors that justify the %s grade with %s total points:

        **Factor 1 - Knowledge Required by the Position Level 1-X, XXXX Points**
        Write 3-4 paragraphs explaining the specific professional knowledge, technical expertise, educational requirements, and application in complex situations.

        **Factor 2 - Supervisory Controls Level 2-X, XXX Points**
        Detail 2-3 paragraphs on supervision received, independence level, decision-making authority, and review processes.

        **Factor 3 - Guidelines Level 3-X, XXX Points**
        Explain 2-3 paragraphs covering applicable laws, regulations, professional standards, and interpretation requirements.

        **Factor 4 - Complexity Level 4-X, XXX Points**
        Describe 2-3 paragraphs on problem complexity, analytical requirements, innovation needs, and decision challenges.

        **Factor 5 - Scope and Effect Level 5-X, XXX Points**
        Detail 2-3 paragraphs on work scope, organizational impact, stakeholder effects, and program contributions.

        **Factor 6 - Personal Contacts Level 6-X, XX Points**
        Explain the range and nature of professional contacts with internal and external stakeholders.

        **Factor 7 - Purpose of Contacts Level 7-X, XXX Points**
        Detail interaction purposes including coordination, negotiation, consultation, and relationship management.

        **Factor 8 - Physical Demands Level 8-X, XX Points**
        Describe physical requirements, work postures, mobility needs, and special considerations.

        **Factor 9 - Work Environment Level 9-X, XX Points**
        Detail work settings, environmental conditions, safety considerations, and occupational factors.

        **EVALUATION SUMMARY:**
        **Total Points: %s**
        **Final Grade: %s** 
        **Grade Range: %s** (MUST be shown as a numeric range, e.g., 2356-2855. Do NOT use GS-9 to GS-11.)

        **CONDITIONS OF EMPLOYMENT:**
        Create comprehensive sections covering:
        - Required certifications and licenses
        - Security clearance requirements if applicable
        - Training and continuing education needs
        - Travel requirements and schedule flexibility
        - Physical and medical requirements
        - Other special employment conditions

        **TITLE AND SERIES DETERMINATION:**
        Write 2-3 detailed paragraphs explaining:
        - Rationale for the GS-%s series assignment
        - How duties align with series definition standards
        - Professional requirements and qualification justification
        - Title appropriateness and any interdisciplinary considerations

        **FAIR LABOR STANDARDS ACT DETERMINATION:**
        Provide specific justification for exempt/non-exempt status with detailed reasoning based on professional duties, decision-making authority, and OPM guidelines.

        CRITICAL INSTRUCTIONS:
        - Use the EXACT grade values provided (%s, %s points, %s range)
        - The grade range MUST be shown as a numeric interval (e.g., 2356-2855), never as GS-9 to GS-11 or similar.
        - Create factor evaluations that logically justify these exact values
        - Generate substantial, professional content with no placeholders
        - Match the quality and comprehensiveness of official OPM position descriptions
        - Ensure all factor points mathematically add up to the provided total

        Generate a complete position description that uses real, substantial content throughout and maintains consistency with the provided evaluation results.
        """,
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
     * Basic fallback prompt
     */
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

    /**
     * Enhanced streaming method using OpenAI API
     */
    public void streamPD(PdRequest request, PrintWriter writer) {
        try {
            String prompt = buildPrompt(request);
            System.out.println("Using prompt length: " + prompt.length());
            
            // Create OpenAI API request
            List<Message> messages = new ArrayList<>();
            messages.add(new Message("system",
                "You are an expert federal HR classification specialist. " +
                "Create complete, professional position descriptions using real content, never placeholders. " +
                "Always use specific values, actual duties, and concrete point assignments."
            ));
            messages.add(new Message("user", prompt));

            OpenAIRequest openaiRequest = new OpenAIRequest("gpt-4", messages, true);
            openaiRequest.setTemperature(0.3); // Lower temperature for consistency
            
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

            System.out.println("Sending request to OpenAI...");
            HttpResponse<InputStream> response = client.send(httpRequest,
                HttpResponse.BodyHandlers.ofInputStream());

            System.out.println("OpenAI response status: " + response.statusCode());

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
                                    // Skip invalid JSON lines
                                    System.err.println("Error parsing JSON: " + e.getMessage());
                                }
                            }
                        }
                    }
                }
            } else {
                String errorMsg = "{\"response\":\"Error: OpenAI API returned status " + response.statusCode() + ". Check your API key.\"}";
                writer.println(errorMsg);
                writer.flush();
                
                // Try to read error response
                try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(response.body()))) {
                    String errorLine;
                    while ((errorLine = errorReader.readLine()) != null) {
                        System.err.println("OpenAI Error: " + errorLine);
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("Exception in streamPD: " + e.getMessage());
            e.printStackTrace();
            String errorMsg = "{\"response\":\"Error: " + escapeJson(e.getMessage()) + "\"}";
            writer.println(errorMsg);
            writer.flush();
        }
    }

    /**
     * Call OpenAI API synchronously for non-streaming requests
     */
    public String callOpenAI(String prompt) throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system",
            "You are an expert federal HR classification specialist. " +
            "Provide complete, professional responses using real content, never placeholders."
        ));
        messages.add(new Message("user", prompt));

        OpenAIRequest openaiRequest = new OpenAIRequest("gpt-3.5-turbo", messages, false);
        openaiRequest.setMaxTokens(2000);
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
            throw new Exception("OpenAI API returned status: " + response.statusCode() +
                            ". Response: " + response.body());
        }

        JsonNode responseJson = objectMapper.readTree(response.body());
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
        
        throw new Exception("Invalid response format from OpenAI API: " + response.body());
    }

    /**
     * Builds a prompt specifically for rewriting/improving existing duties
     */
    public String buildDutyEnhancementPrompt(String existingDuties) {
        return String.format("""
            Rewrite these federal job duties to be more professional and detailed:
            
            %s
            
            Requirements:
            - Use proper federal terminology
            - Include specific percentages (like 40%%, 30%%, 20%%, 10%%)
            - Add measurable outcomes
            - Make duties specific and actionable
            - Use professional language
            
            Return only the improved duties as a numbered list.
            """, existingDuties);
    }

    /**
     * Build a prompt for the evaluation statement that uses the provided grade, range, and points.
     */
    public String buildEvaluationPrompt(String duties, String gsGrade, String gradeRange, String totalPoints, String supervisoryLevel, String jobSeries, String jobTitle) {
        return String.format("""
            You are a federal HR classification specialist creating an official OPM factor evaluation.

            CRITICAL: Use ONLY these exact factor levels and points for each factor, and ensure the total points, grade, and grade range match exactly:
            - Final Grade: %s
            - Grade Range: %s
            - Total Points: %s

            Supervisory Level: %s
            Job Series: %s
            Job Title: %s

            Duties:
            %s

            For each factor, provide:
            - The correct level (e.g., 1-7)
            - The correct points for that level
            - A 3-5 sentence rationale for why this level is appropriate

            At the end, provide:
            **Total Points: %s**
            **Final Grade: %s**
            **Grade Range: %s**
            """, gsGrade, gradeRange, totalPoints, supervisoryLevel, jobSeries, jobTitle, duties, totalPoints, gsGrade, gradeRange);
    }

    /**
     * Build a prompt for the Title and Series Determination section.
     */
    public String buildTitleSeriesPrompt(String duties, String gsGrade, String jobSeries, String jobTitle, String supervisoryLevel) {
        return String.format("""
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
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Autowired
    private PdfProcessingService pdfProcessingService;

    public String extractPdfText(String pdfPath) throws Exception {
        return pdfProcessingService.extractTextFromPdfPath(pdfPath);
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