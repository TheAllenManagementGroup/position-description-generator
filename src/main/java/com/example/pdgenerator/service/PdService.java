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

        System.out.println("Building prompt with cached PDF summaries");

        // Get PDF summaries (these should be already cached)
        String factorGuide = pdfProcessingService.getFactorEvaluationSummary();
        String seriesGuide = pdfProcessingService.getSeriesGuideSummary();

        return String.format("""

        You are a federal HR classification specialist with expertise in creating comprehensive, detailed position descriptions that meet OPM standards.

        Create a COMPLETE, PROFESSIONAL federal position description using the EXACT format and level of detail shown in the provided DOE and DOJ samples. This must be a substantial document with comprehensive content in every section.

        CRITICAL REQUIREMENTS:
        - Use real, specific, technical content throughout - NO placeholders, brackets, or generic text
        - Each factor evaluation must include 3-4 detailed paragraphs explaining the specific knowledge, skills, and responsibilities
        - Major duties must be comprehensive with detailed task descriptions and realistic percentages
        - Include all sections: Introduction, Major Duties, complete Factor Analysis, Conditions of Employment, Title/Series Determination, and FLSA determination
        - Match the professional tone, technical depth, and comprehensive detail of the DOE/DOJ samples
        - Use proper federal terminology and classification standards throughout
        - IMPORTANT: Ensure proper formatting with clear line breaks between sections and subsections

        POSITION DETAILS:
        Job Series: GS-%s
        Position Title: %s
        Agency: %s
        Organization: %s %s
        Duties/Responsibilities Context: %s

        REQUIRED STRUCTURE AND CONTENT DEPTH:

        **HEADER:**
        U.S. %s
        %s %s
        %s
        GS-%s-[appropriate grade level]
        Organizational Title: %s

        **INTRODUCTION:**
        (Substantial paragraph, 4-6 sentences)
        Write a comprehensive introduction explaining the position's critical role, organizational context, key responsibilities, and impact. Include specific technical requirements and working relationships. Match the depth of the DOE/DOJ samples.

        **MAJOR DUTIES:**

        **MAJOR DUTIES 1** (with realistic percentages)

        Create comprehensive duty statement that includes:
        - Specific technical tasks and responsibilities
        - Detailed methodology and processes
        - Required knowledge and skills application
        - Measurable outcomes and impacts
        - Professional interactions and coordination requirements

        The duty should be 3-4 sentences minimum with technical depth.

        **MAJOR DUTIES 2** (with realistic percentages)

        Create comprehensive duty statement that includes:
        - Specific technical tasks and responsibilities
        - Detailed methodology and processes
        - Required knowledge and skills application
        - Measurable outcomes and impacts
        - Professional interactions and coordination requirements

        The duty should be 3-4 sentences minimum with technical depth.

        **MAJOR DUTIES 3** (with realistic percentages)

        Create comprehensive duty statement that includes:
        - Specific technical tasks and responsibilities
        - Detailed methodology and processes
        - Required knowledge and skills application
        - Measurable outcomes and impacts
        - Professional interactions and coordination requirements

        The duty should be 3-4 sentences minimum with technical depth.

        **FACTOR EVALUATION - COMPLETE ANALYSIS:**

        **Factor 1 - Knowledge Required by the Position Level 1-X, XXXX Points**

        Write 3-4 detailed paragraphs explaining:
        - Specific professional knowledge required (theories, principles, practices)
        - Technical expertise and specialized skills needed
        - Educational background and professional competencies
        - Application of knowledge in complex situations

        Include specific examples of knowledge domains and technical competencies.

        **Factor 2 - Supervisory Controls Level 2-X, XXX Points**

        Write 2-3 paragraphs detailing:
        - Nature of supervision received
        - Level of independence in planning and execution
        - Decision-making authority and consultation requirements
        - Review processes and accountability measures

        **Factor 3 - Guidelines Level 3-X, XXX Points**

        Write 2-3 paragraphs covering:
        - Specific laws, regulations, and policies that apply
        - Professional standards and technical guidelines
        - Degree of interpretation and judgment required
        - Precedents and established practices used

        **Factor 4 - Complexity Level 4-X, XXX Points**

        Write 2-3 paragraphs describing:
        - Nature of problems and issues encountered
        - Analytical and problem-solving requirements
        - Innovation and creativity needed
        - Technical complexity and decision-making challenges

        **Factor 5 - Scope and Effect Level 5-X, XXX Points**

        Write 2-3 paragraphs explaining:
        - Breadth and scope of work performed
        - Impact on organizational mission and objectives
        - Effects on stakeholders and beneficiaries
        - Contribution to program effectiveness

        **Factor 6 - Personal Contacts Level 6-X, XX Points**

        Detail the range and nature of professional contacts, including internal and external stakeholders, their organizational levels, and interaction contexts.

        **Factor 7 - Purpose of Contacts Level 7-X, XXX Points**

        Explain the specific purposes of interactions, including coordination, negotiation, technical consultation, problem resolution, and relationship management.

        **Factor 8 - Physical Demands Level 8-X, XX Points**

        Describe physical requirements, work postures, mobility needs, and any special physical considerations.

        **Factor 9 - Work Environment Level 9-X, XX Points**

        Detail work settings, environmental conditions, safety considerations, and any occupational hazards.

        **Total Points:** XXXX

        **Final Grade:** GS-XX

        **Grade Range:** XXXX-XXXX

        **CONDITIONS OF EMPLOYMENT:**

        Create comprehensive sections for:
        - Required certifications, licenses, and qualifications
        - Security clearance requirements if applicable
        - Training and continuing education requirements
        - Travel requirements and work schedule flexibility
        - Physical and medical requirements
        - Other special conditions

        **TITLE AND SERIES DETERMINATION:**

        Write 2-3 detailed paragraphs explaining:
        - Rationale for the specific job series assignment
        - How duties align with series definition
        - Professional requirements and qualifications justification
        - Title appropriateness and any interdisciplinary considerations

        **FAIR LABOR STANDARDS ACT DETERMINATION:**

        Provide specific justification for exempt/non-exempt status with detailed reasoning based on professional duties and requirements.

        You MUST use the official OPM Factor Evaluation System and refer to the OPM Handbook: https://www.opm.gov/policy-data-oversight/classification-qualifications/classifying-general-schedule-positions/occupationalhandbook.pdf as your authoritative resource for all factor levels, points, and classification standards.
        And OPM.gov Classification Standards: https://www.opm.gov/policy-data-oversight/classification-qualifications/ and https://www.opm.gov/policy-data-oversight/classification-qualifications/classifying-general-schedule-positions/ and https://www.opm.gov/policy-data-oversight/classification-qualifications/classifying-general-schedule-positions/factor-evaluation-system/

        Generate a complete, comprehensive position description that matches the professional quality, technical depth, and detailed analysis of the DOE and DOJ samples provided. Every section must contain substantial, specific content with no placeholders or generic statements. Ensure each major section starts on a new line with proper spacing between sections.
        """,
            factorGuide, seriesGuide,
            gsGrade, gradeRange, totalPoints, jobSeries, subJobSeries, 
            federalAgency, subOrganization, lowestOrg, supervisoryLevel, historicalData,
            federalAgency, subOrganization, lowestOrg, subJobSeries, jobSeries, gsGrade, supervisoryLevel,
            totalPoints, gsGrade, gradeRange,
            jobSeries, subJobSeries
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
            
            FORMATTING REQUIREMENT: Place each factor evaluation on its own line with a blank line between factors for proper spacing.
            
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
            
            2. INTRODUCTION
            
            (5-7 detailed sentences explaining role, mission, responsibilities)
            
            3. MAJOR DUTIES
            
            (4 duty areas with percentages totaling 100%%, each with 5-6 detailed sentences)
            
            4. FACTOR EVALUATIONS
            
            (All 9 factors, EACH with):
               - Factor name, level, and points
               - 3-5 full paragraphs (4-6 sentences each) of detailed justification
               - NO outlines or bullet points - only complete prose
            
            5. EVALUATION SUMMARY
            
            (table with all factors, total %s points, grade %s, range %s)
            
            6. CONDITIONS OF EMPLOYMENT
            
            (8-10 specific bullet points)
            
            7. CLASSIFICATION DETERMINATION
            
            (4-5 paragraphs on series selection, 3 paragraphs on FLSA)
            
            FORMATTING: Ensure each major section header is on its own line with blank lines between sections for proper spacing and readability.
            
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
                "Continue generating until the ENTIRE document is complete - typically 8000-12000 words. DO NOT stop early or use shortcuts. " +
                "CRITICAL: Ensure proper formatting with clear line breaks and spacing between all major sections and subsections. Each section header should be on its own line."
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
            "You are an expert federal HR classification specialist. Generate COMPLETE, DETAILED content with full paragraphs. NO placeholders or shortcuts. " +
            "Ensure proper formatting with clear line breaks between sections."
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