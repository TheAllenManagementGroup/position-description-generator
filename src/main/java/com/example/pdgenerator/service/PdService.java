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
            this.maxTokens = 4000; // Conservative limit
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
     * Enhanced buildPrompt method using PdfProcessingService
     */
    public String buildPrompt(PdRequest request) {
        try {
            return buildComprehensivePromptWithPDFs(request);
        } catch (Exception e) {
            System.err.println("Error in buildComprehensivePromptWithPDFs: " + e.getMessage());
            return buildBasicPrompt(request);
        }
    }

    /**
     * Enhanced prompt using cached PDF content from PdfProcessingService
     */
    private String buildComprehensivePromptWithPDFs(PdRequest request) {
        String jobSeries = request.getJobSeries() != null ? request.getJobSeries() : "0610";
        String subJobSeries = request.getSubJobSeries() != null ? request.getSubJobSeries() : "Analyst";
        String federalAgency = request.getFederalAgency() != null ? request.getFederalAgency() : "Department of Homeland Security";
        String subOrganization = request.getSubOrganization() != null ? request.getSubOrganization() : "FEMA";
        String lowestOrg = request.getLowestOrg() != null ? request.getLowestOrg() : "";
        String historicalData = request.getHistoricalData() != null ? request.getHistoricalData() : "Administrative and analytical duties";
        String gsGrade = request.getGsGrade() != null ? request.getGsGrade() : "GS-13";
        String gradeRange = request.getGradeRange() != null ? request.getGradeRange() : "3155-3600";
        String totalPoints = request.getTotalPoints() != null ? request.getTotalPoints().toString() : "3400";
        String supervisoryLevel = request.getSupervisoryLevel() != null ? request.getSupervisoryLevel() : "Non-Supervisory";

        // Get SUMMARIZED PDF content from PdfProcessingService (pre-cached and limited)
        String factorSummary = pdfProcessingService.getFactorEvaluationSummary();
        String seriesGuidance = pdfProcessingService.getSeriesGuidance(jobSeries);

        System.out.println("Building prompt with cached PDF summaries");

        return String.format("""
            Create COMPLETE federal position description. This is CRITICAL: Generate the ENTIRE document.

            REFERENCE STANDARDS (Summarized):
            %s

            SERIES GUIDANCE:
            %s

            EXACT VALUES TO USE:
            - Final Grade: %s (USE EXACTLY)
            - Grade Range: %s (USE EXACTLY)
            - Total Points: %s (USE EXACTLY)

            POSITION DETAILS:
            Job Series: GS-%s
            Position Title: %s
            Agency: %s
            Organization: %s %s
            Supervisory Level: %s
            Duties/Responsibilities: %s

            === GENERATE COMPLETE POSITION DESCRIPTION ===

            **HEADER:**
            U.S. %s
            %s %s
            Position: %s, Series: GS-%s
            Grade: %s

            **INTRODUCTION:**
            [Write 4-6 comprehensive sentences explaining the position's role, organizational context, key responsibilities, and mission impact. Make this DETAILED and SPECIFIC.]

            **MAJOR DUTIES:**
            Create 3-4 COMPLETE major duty statements with realistic percentages totaling 100%%:

            1. [Duty Title] (XX%%)
            [Write 4-5 detailed sentences describing specific tasks, responsibilities, technical requirements, and deliverables.]

            2. [Duty Title] (XX%%)
            [Write 4-5 detailed sentences with specific examples and measurable outcomes.]

            3. [Duty Title] (XX%%)
            [Write 4-5 detailed sentences covering all aspects of this duty area.]

            4. [Duty Title] (XX%%)
            [Write 3-4 detailed sentences for remaining duties.]

            **FACTOR EVALUATION - COMPLETE ANALYSIS FOR ALL 9 FACTORS:**

            **Factor 1 – Knowledge Required by the Position Level 1-X, XXXX Points**
            [Write 4-5 paragraphs detailing: Required education and professional knowledge, technical expertise, complexity of concepts, professional judgment, level justification]

            **Factor 2 – Supervisory Controls Level 2-X, XXX Points**
            [Write 3-4 paragraphs explaining: Nature of supervision, independence in decision-making, review process, authority level]

            **Factor 3 – Guidelines Level 3-X, XXX Points**
            [Write 3-4 paragraphs covering: Types of guidelines, judgment in application, interpretation needed, development of approaches]

            **Factor 4 – Complexity Level 4-X, XXX Points**
            [Write 3-4 paragraphs on: Problems encountered, variables and interrelationships, analytical processes, innovation]

            **Factor 5 – Scope and Effect Level 5-X, XXX Points**
            [Write 3-4 paragraphs explaining: Work purpose and breadth, organizational impact, external effects, consequences]

            **Factor 6 – Personal Contacts Level 6-X, XXX Points**
            [Write 2-3 paragraphs describing: Who is contacted, frequency and settings, interaction complexity]

            **Factor 7 – Purpose of Contacts Level 7-X, XXX Points**
            [Write 2-3 paragraphs covering: Contact reasons, influence level needed, negotiation requirements]

            **Factor 8 – Physical Demands Level 8-X, XXX Points**
            [Write 2 paragraphs on: Physical activities, special demands or conditions]

            **Factor 9 – Work Environment Level 9-X, XXX Points**
            [Write 2 paragraphs describing: Work setting and conditions, risks and stress factors]

            **EVALUATION SUMMARY:**
            **Total Points: %s**
            **Final Grade: %s**
            **Grade Range: %s**

            **CONDITIONS OF EMPLOYMENT:**
            [Write 6-8 bullet points covering: security clearance, certifications, travel, schedule, physical requirements, special conditions]

            **TITLE AND SERIES DETERMINATION:**
            [Write 3-4 paragraphs explaining: Series selection rationale, duty alignment with series definition, OPM standards justification, alternatives considered]

            **FAIR LABOR STANDARDS ACT DETERMINATION:**
            [Write 2-3 paragraphs covering: FLSA exemption status, duty-based justification, professional requirements]

            CRITICAL INSTRUCTIONS:
            - Generate EVERY section COMPLETELY - NO placeholders or brackets
            - Write FULL paragraphs with SPECIFIC details
            - Use EXACT grade values provided (%s, %s points, %s range)
            - Make factor evaluations DETAILED and SUBSTANTIVE
            - Continue generating until ENTIRE document is complete
            """,
            factorSummary, seriesGuidance,
            gsGrade, gradeRange, totalPoints,
            jobSeries, subJobSeries, federalAgency, subOrganization, lowestOrg, supervisoryLevel, historicalData,
            federalAgency, subOrganization, lowestOrg, subJobSeries, jobSeries, gsGrade,
            totalPoints, gsGrade, gradeRange,
            gsGrade, totalPoints, gradeRange
        );
    }

    /**
     * Enhanced prompt building for evaluation statements using PdfProcessingService
     */
    public String buildEvaluationPromptWithPDFs(String duties, String gsGrade, String gradeRange, 
                                               String totalPoints, String supervisoryLevel, 
                                               String jobSeries, String jobTitle) {
        // Get summarized factor guide from PdfProcessingService
        String factorGuide = pdfProcessingService.getFactorEvaluationSummary();
        
        return String.format("""
            You are a federal HR classification specialist creating an official OPM factor evaluation.

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

            Using the Factor Evaluation System reference above, create detailed evaluations for all 9 factors 
            that justify the %s grade with %s total points. Each factor must include the correct level, 
            points, and rationale based on official OPM standards.

            Format each factor as:
            **Factor X - [Factor Name] Level X-X, XXX Points**
            [3-4 sentence rationale referencing the official standards]

            End with:
            **Total Points: %s**
            **Final Grade: %s**
            **Grade Range: %s**
            """, 
            factorGuide, gsGrade, gradeRange, totalPoints, supervisoryLevel, jobSeries, jobTitle, 
            duties, gsGrade, totalPoints, totalPoints, gsGrade, gradeRange
        );
    }

    /**
     * Basic prompt fallback (no PDF content)
     */
    private String buildBasicPrompt(PdRequest request) {
        String jobSeries = request.getJobSeries() != null ? request.getJobSeries() : "0343";
        String subJobSeries = request.getSubJobSeries() != null ? request.getSubJobSeries() : "Management Analyst";
        String federalAgency = request.getFederalAgency() != null ? request.getFederalAgency() : "Department of Homeland Security";
        String historicalData = request.getHistoricalData() != null ? request.getHistoricalData() : "Administrative duties";
        String gsGrade = request.getGsGrade() != null ? request.getGsGrade() : "GS-13";
        
        return String.format("""
            Create a complete federal position description for a %s (GS-%s) position at %s.
            
            Job duties include: %s
            
            Create sections:
            - Header with agency and title
            - Introduction paragraph
            - 3-4 major duties with percentages
            - All 9 classification factors with specific point values
            - Total Points, Grade Range, and Final Grade
            - Conditions of employment
            - Classification determination
            
            Use real content, not placeholders. Make it professional and complete.
            Target grade: %s
            """,
            subJobSeries, jobSeries, federalAgency, historicalData, gsGrade
        );
    }

    /**
     * Stream PD generation to client
     */
    public void streamPD(PdRequest request, PrintWriter writer) {
        try {
            String prompt = buildPrompt(request);
            System.out.println("Prompt built with PDF references, length: " + prompt.length() + " chars (~" + (prompt.length() / 4) + " tokens)");
            
            // Create OpenAI API request
            List<Message> messages = new ArrayList<>();
            messages.add(new Message("system",
                "You are an expert federal HR classification specialist with access to official OPM standards. " +
                "Create complete, professional position descriptions using real content based on official guidance. " +
                "Generate the ENTIRE document with NO placeholders."
            ));
            messages.add(new Message("user", prompt));

            OpenAIRequest openaiRequest = new OpenAIRequest("gpt-4", messages, true);
            openaiRequest.setMaxTokens(6000); // Generous for complete generation
            openaiRequest.setTemperature(0.3);
            
            ObjectMapper objectMapper = new ObjectMapper();
            String requestBody = objectMapper.writeValueAsString(openaiRequest);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + openaiApiKey)
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(180))
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
                                    System.err.println("Error parsing JSON chunk: " + e.getMessage());
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

    /**
     * Call OpenAI API for non-streaming requests
     */
    public String callOpenAI(String prompt) throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system",
            "You are an expert federal HR classification specialist with access to official standards."
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

    /**
     * Analyze uploaded PDF for classification
     */
    public Map<String, Object> analyzePdfForClassification(MultipartFile pdfFile, String targetGrade) throws Exception {
        String pdfText = pdfProcessingService.extractTextFromPdf(pdfFile);
        return pdfProcessingService.analyzePdfContent(pdfText, targetGrade);
    }

    /**
     * Escape JSON special characters
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}