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
            this.maxTokens = 16000; // Increased significantly for complete generation
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

    // Get PDF summaries
    String factorGuide = pdfProcessingService.getFactorEvaluationSummary();
    String seriesGuide = pdfProcessingService.getSeriesGuideSummary();

    // Build complete factor section with locked values
    String completeFactorSection = buildCompleteFactorSectionForPD(request);

    return String.format("""

        You are a federal HR classification specialist with expertise in creating comprehensive, detailed position descriptions that meet OPM standards.

        Create a COMPLETE, PROFESSIONAL federal position description using the EXACT format and level of detail shown in the provided DOE and DOJ samples. This must be a substantial document with comprehensive content in every section.

        POSITION DETAILS:
        Job Series: GS-%s
        Position Title: %s
        Agency: %s
        Organization: %s %s
        Supervisory Level: %s
        Duties/Responsibilities Context: %s

        REQUIRED STRUCTURE:

        **HEADER:**
        U.S. %s
        %s %s
        %s
        GS-%s-%s
        Organizational Title: %s

        **INTRODUCTION:** (4-6 sentences)
        Write a comprehensive introduction explaining the position's critical role, organizational context, key responsibilities, and impact. Include specific technical requirements and working relationships. Match the depth of the DOE/DOJ samples.

        **MAJOR DUTIES:** (Create 3-4 major duty areas with realistic percentages totaling 100%%)
        
        Each duty must include:
        - Specific technical tasks and responsibilities (3-4 sentences)
        - Detailed methodology and processes
        - Required knowledge and skills application
        - Measurable outcomes and impacts
        - Professional interactions and coordination requirements

        **FACTOR EVALUATION - COMPLETE ANALYSIS:**

        %s

        **CONDITIONS OF EMPLOYMENT:**
        Create 4-5 specific sentences covering:
        - Required certifications, licenses, and qualifications
        - Security clearance requirements if applicable
        - Training and continuing education requirements
        - Travel requirements and work schedule flexibility
        - Physical and medical requirements
        - Other special conditions

        **TITLE AND SERIES DETERMINATION:**
        Write 2-3 detailed paragraphs explaining:
        - Rationale for the specific job series assignment (GS-%s)
        - How duties align with series definition
        - Professional requirements and qualifications justification
        - Title appropriateness

        **FAIR LABOR STANDARDS ACT DETERMINATION:**
        Provide specific justification for exempt/non-exempt status with detailed reasoning based on professional duties and requirements.

        Generate a complete, comprehensive position description that matches the professional quality, technical depth, and detailed analysis of the DOE and DOJ samples provided. Every section must contain substantial, specific content with no placeholders or generic statements.
        """,
        jobSeries, subJobSeries,
        federalAgency, subOrganization, lowestOrg,
        supervisoryLevel, historicalData,
        federalAgency, subOrganization, lowestOrg, subJobSeries,
        jobSeries, gsGrade.replace("GS-", ""), subJobSeries,
        completeFactorSection,
        jobSeries
    );
}

/**
 * Build COMPLETE factor section with locked values formatted exactly as they should appear in PD
 */
private String buildCompleteFactorSectionForPD(PdRequest request) {
    Map<String, String> factorLevels = request.getFactorLevels();
    Map<String, Integer> factorPoints = request.getFactorPoints();
    
    if (factorLevels == null || factorPoints == null || factorLevels.size() != 9) {
        System.out.println("WARNING: No locked factor values - cannot generate accurate PD");
        return "[ERROR: Factor evaluations must be generated first using the evaluation statement endpoint]";
    }
    
    StringBuilder section = new StringBuilder();
    
    String[] factorNames = {
        "Knowledge Required by the Position",
        "Supervisory Controls",
        "Guidelines",
        "Complexity",
        "Scope and Effect",
        "Personal Contacts",
        "Purpose of Contacts",
        "Physical Demands",
        "Work Environment"
    };
    
    // Generate each factor with the exact locked values
    for (int i = 1; i <= 9; i++) {
        String factorNum = String.valueOf(i);
        String level = factorLevels.get(factorNum);
        Integer points = factorPoints.get(factorNum);
        
        if (level != null && points != null) {
            section.append(String.format("**Factor %d - %s Level %s, %d Points**\n", 
                i, factorNames[i-1], level, points));
            section.append("Write 2-3 detailed paragraphs explaining this factor level based on the position duties. ");
            section.append("Focus on what knowledge/skills/responsibilities justify this specific level. ");
            section.append("Reference OPM standards and provide specific examples from the duties.\n\n");
        }
    }
    
    // Add the summary with exact locked values
    section.append(String.format("**Total Points: %d**\n", request.getTotalPoints()));
    section.append(String.format("**Final Grade: %s**\n", request.getGsGrade()));
    section.append(String.format("**Grade Range: %s**\n\n", request.getGradeRange()));
    
    return section.toString();
}

/**
 * Build locked factor section that AI must copy exactly
 */
private String buildLockedFactorSection(PdRequest request) {
    Map<String, String> factorLevels = request.getFactorLevels();
    Map<String, Integer> factorPoints = request.getFactorPoints();
    
    if (factorLevels == null || factorPoints == null || factorLevels.size() != 9) {
        return "⚠️ NO LOCKED VALUES - Generate appropriate factor evaluations";
    }
    
    StringBuilder section = new StringBuilder();
    section.append("⚠️ MANDATORY: Copy these EXACT factor values into the position description:\n\n");
    
    String[] factorNames = {
        "Knowledge Required by the Position",
        "Supervisory Controls",
        "Guidelines",
        "Complexity",
        "Scope and Effect",
        "Personal Contacts",
        "Purpose of Contacts",
        "Physical Demands",
        "Work Environment"
    };
    
    for (int i = 1; i <= 9; i++) {
        String factorNum = String.valueOf(i);
        String level = factorLevels.get(factorNum);
        Integer points = factorPoints.get(factorNum);
        
        if (level != null && points != null) {
            section.append(String.format("Factor %d - %s Level %s, %d Points\n", 
                i, factorNames[i-1], level, points));
        }
    }
    
    section.append(String.format("\nTotal Points: %d\n", request.getTotalPoints()));
    section.append(String.format("Final Grade: %s\n", request.getGsGrade()));
    section.append(String.format("Grade Range: %s\n\n", request.getGradeRange()));
    
    section.append("YOU MUST USE THESE EXACT VALUES. DO NOT RECALCULATE.\n");
    
    return section.toString();
}

/**
 * Build detailed instructions for factor write-ups
 */
private String buildDetailedFactorInstructions(PdRequest request) {
    Map<String, String> factorLevels = request.getFactorLevels();
    Map<String, Integer> factorPoints = request.getFactorPoints();
    
    if (factorLevels == null || factorPoints == null) {
        return "Generate complete factor evaluations with proper justification.";
    }
    
    StringBuilder instructions = new StringBuilder();
    String[] factorNames = {
        "Knowledge Required by the Position",
        "Supervisory Controls",
        "Guidelines",
        "Complexity",
        "Scope and Effect",
        "Personal Contacts",
        "Purpose of Contacts",
        "Physical Demands",
        "Work Environment"
    };
    
    for (int i = 1; i <= 9; i++) {
        String level = factorLevels.get(String.valueOf(i));
        Integer points = factorPoints.get(String.valueOf(i));
        
        if (level != null && points != null) {
            instructions.append(String.format(
                "**Factor %d – %s Level %s, %d Points**\n" +
                "Write 2-3 detailed paragraphs explaining this factor level based on the position duties.\n\n",
                i, factorNames[i-1], level, points
            ));
        }
    }
    
    instructions.append(String.format("**Total Points: %d**\n", request.getTotalPoints()));
    instructions.append(String.format("**Final Grade: %s**\n", request.getGsGrade()));
    instructions.append(String.format("**Grade Range: %s**\n", request.getGradeRange()));
    
    return instructions.toString();
}

/**
 * Build factor evaluation section with exact locked values
 */
private String buildFactorSectionFromRequest(PdRequest request) {
    Map<String, String> factorLevels = request.getFactorLevels();
    Map<String, Integer> factorPoints = request.getFactorPoints();
    
    if (factorLevels == null || factorPoints == null || factorLevels.size() != 9) {
        System.out.println("WARNING: No locked factor values provided, using template");
        return buildGenericFactorSection(request);
    }
    
    StringBuilder section = new StringBuilder();
    section.append("⚠️ CRITICAL: USE THESE EXACT FACTOR VALUES (DO NOT CHANGE LEVELS OR POINTS):\n\n");
    
    String[] factorNames = {
        "Knowledge Required by the Position",
        "Supervisory Controls",
        "Guidelines",
        "Complexity",
        "Scope and Effect",
        "Personal Contacts",
        "Purpose of Contacts",
        "Physical Demands",
        "Work Environment"
    };
    
    for (int i = 1; i <= 9; i++) {
        String factorNum = String.valueOf(i);
        String level = factorLevels.get(factorNum);
        Integer points = factorPoints.get(factorNum);
        
        if (level != null && points != null) {
            section.append(String.format(
                "**Factor %d - %s Level %s, %d Points**\n",
                i, factorNames[i-1], level, points
            ));
            section.append("[Write 2-3 detailed paragraphs explaining this factor level]\n\n");
        }
    }
    
    section.append(String.format("**Total Points: %d**\n", request.getTotalPoints()));
    section.append(String.format("**Final Grade: %s**\n", request.getGsGrade()));
    section.append(String.format("**Grade Range: %s**\n\n", request.getGradeRange()));
    
    return section.toString();
}

/**
 * Fallback generic factor section when no locked values provided
 */
private String buildGenericFactorSection(PdRequest request) {
    return String.format("""
        Generate ALL 9 factor evaluations with proper OPM levels and points.
        The factors must total to %s points for grade %s (range %s).
        Each factor must include detailed justification based on the duties provided.
        """,
        request.getTotalPoints() != null ? request.getTotalPoints() : "appropriate",
        request.getGsGrade() != null ? request.getGsGrade() : "target",
        request.getGradeRange() != null ? request.getGradeRange() : "appropriate"
    );
}

    /**
     * Enhanced prompt building for evaluation statements using PdfProcessingService
     */
    public String buildEvaluationPromptWithPDFs(String duties, String gsGrade, String gradeRange, 
                                            String totalPoints, String supervisoryLevel, 
                                            String jobSeries, String jobTitle) {
        String factorGuide = pdfProcessingService.getFactorEvaluationSummary();
        
        return String.format("""
            ⚠️ CRITICAL: Generate COMPLETE factor evaluations with FULL paragraphs. NO placeholders or outlines.
            
            REFERENCE - FACTOR EVALUATION SYSTEM:
            %s

            MANDATORY VALUES (USE EXACTLY):
            - Final Grade: %s
            - Grade Range: %s  
            - Total Points: %s

            Position Details:
            - Supervisory Level: %s
            - Job Series: %s
            - Job Title: %s

            Duties:
            %s

            === GENERATE ALL 9 FACTOR EVALUATIONS ===
            
            For EACH factor, write:
            1. Factor title with level and points (e.g., "Factor 1 – Knowledge Required: Level 1-7, 1250 Points")
            2. 3-5 full paragraphs (minimum 4 sentences each) explaining:
               - What the factor measures
               - How the position meets this level
               - Specific examples from the duties
               - Reference to OPM standards
               - Justification for the point value
            
            Write in complete prose - NO bullet points, NO outlines, NO brackets.
            
            After all 9 factors, provide:
            
            **EVALUATION SUMMARY:**
            Total Points: %s
            Final Grade: %s
            Grade Range: %s
            
            Each factor evaluation must be substantive and detailed. Total response should be 2000-3000 words.
            """, 
            factorGuide, gsGrade, gradeRange, totalPoints, supervisoryLevel, jobSeries, jobTitle, 
            duties, totalPoints, gsGrade, gradeRange
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
        String totalPoints = request.getTotalPoints() != null ? request.getTotalPoints().toString() : "3400";
        String gradeRange = request.getGradeRange() != null ? request.getGradeRange() : "3155-3600";
        
        return String.format("""
            ⚠️ CRITICAL: Generate a COMPLETE, FULLY-DETAILED federal position description with NO placeholders.
            
            Create a comprehensive federal position description for:
            - Position: %s (GS-%s)
            - Agency: %s
            - Grade: %s (%s points, range %s)
            
            Duties: %s
            
            YOU MUST INCLUDE ALL SECTIONS WITH COMPLETE CONTENT:
            
            1. HEADER (agency, organization, title, series, grade)
            
            2. INTRODUCTION (5-7 detailed sentences explaining role, mission, responsibilities)
            
            3. MAJOR DUTIES (4 duty areas with percentages totaling 100%%, each with 5-6 detailed sentences)
            
            4. FACTOR EVALUATIONS (All 9 factors, EACH with):
               - Factor name, level, and points
               - 3-5 full paragraphs (4-6 sentences each) of detailed justification
               - NO outlines or bullet points - only complete prose
            
            5. EVALUATION SUMMARY (table with all factors, total %s points, grade %s, range %s)
            
            6. CONDITIONS OF EMPLOYMENT (8-10 specific bullet points)
            
            7. CLASSIFICATION DETERMINATION (4-5 paragraphs on series selection, 3 paragraphs on FLSA)
            
            Total document should be 8000-10000 words. Generate the ENTIRE document with NO shortcuts or placeholders.
            """,
            subJobSeries, jobSeries, federalAgency, gsGrade, totalPoints, gradeRange, historicalData,
            totalPoints, gsGrade, gradeRange
        );
    }

    /**
     * Stream PD generation to client
     */
    public void streamPD(PdRequest request, PrintWriter writer) {
        try {
            String prompt = buildPrompt(request);
            System.out.println("Prompt built, length: " + prompt.length() + " chars (~" + (prompt.length() / 4) + " tokens)");
            
            List<Message> messages = new ArrayList<>();
            messages.add(new Message("system",
                "You are an expert federal HR classification specialist. You MUST generate COMPLETE, DETAILED position descriptions with NO placeholders, NO outlines, NO incomplete sections. " +
                "Every section requires full prose paragraphs with specific details. Factor evaluations must have 3-5 full paragraphs EACH explaining the justification. " +
                "Continue generating until the ENTIRE document is complete - typically 8000-12000 words. DO NOT stop early or use shortcuts."
            ));
            messages.add(new Message("user", prompt));

            OpenAIRequest openaiRequest = new OpenAIRequest("gpt-4", messages, true);
            openaiRequest.setMaxTokens(16000); // Significantly increased
            openaiRequest.setTemperature(0.3);
            
            ObjectMapper objectMapper = new ObjectMapper();
            String requestBody = objectMapper.writeValueAsString(openaiRequest);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + openaiApiKey)
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(300)) // Increased timeout
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
            "You are an expert federal HR classification specialist. Generate COMPLETE, DETAILED content with full paragraphs. NO placeholders or shortcuts."
        ));
        messages.add(new Message("user", prompt));

        OpenAIRequest openaiRequest = new OpenAIRequest("gpt-4", messages, false);
        openaiRequest.setMaxTokens(8000); // Increased
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