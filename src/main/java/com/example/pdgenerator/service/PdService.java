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
            CRITICAL INSTRUCTION: You MUST generate a COMPLETE, FULLY-DETAILED position description with NO placeholders, NO brackets, NO incomplete sections. Every section must contain full prose paragraphs with specific details.
            
            === REFERENCE MATERIALS ===
            
            FACTOR EVALUATION STANDARDS:
            %s
            
            JOB SERIES STANDARDS:
            %s
            
            === MANDATORY VALUES - USE EXACTLY AS PROVIDED ===
            - Final Grade: %s (DO NOT CALCULATE - USE THIS EXACT VALUE)
            - Grade Range: %s (USE EXACTLY)
            - Total Points: %s (USE EXACTLY)
            - Job Series: GS-%s
            - Position Title: %s
            - Agency: %s
            - Organization: %s %s
            - Supervisory Level: %s
            
            === POSITION CONTEXT ===
            Duties and Responsibilities Background:
            %s
            
            ═══════════════════════════════════════════════════════
            ⚠️ GENERATE THE COMPLETE DOCUMENT BELOW ⚠️
            ═══════════════════════════════════════════════════════
            
            **HEADER SECTION**
            
            U.S. %s
            %s %s
            
            **Position Title: ** %s
            **Series: ** GS-%s
            **Grade: ** %s
            **Supervisory Level: ** %s
            
            **INTRODUCTION**
            
            [Write 4-5 comprehensive sentences that:
            - Explain the position's role within the organization
            - Describe the organizational mission and context
            - Detail primary areas of responsibility
            - Explain the impact and importance of the work
            - Describe key relationships and reporting structure
            - Highlight the complexity and scope of duties
            Make this SPECIFIC and DETAILED - use concrete examples and real responsibilities.]
            
            **MAJOR DUTIES**
            
            **1. [First Major Duty Area] (35%%)**
            
            [Write 3-4 detailed sentences explaining:
            - Specific tasks and activities performed
            - Technical skills and knowledge applied
            - Deliverables and work products created
            - Frequency and duration of activities
            - Tools, systems, or methodologies used
            - Impact and importance of this duty area]
            
            **2. [Second Major Duty Area] (30%%)**
            
            [Write 3-4 detailed sentences covering:
            - Primary responsibilities in this area
            - Complexity of work performed
            - Coordination with others required
            - Decision-making authority involved
            - Standards and requirements followed
            - Outcomes and results expected]
            
            **3. [Third Major Duty Area] (25%%)**
            
            [Write 3-4 detailed sentences describing:
            - Nature of work in this area
            - Level of independence exercised
            - Problem-solving required
            - Stakeholder interactions involved
            - Quality and timeliness expectations
            - Contribution to organizational goals]
            
            **4. [Fourth Major Duty Area] (10%%)**
            
            [Write 2-3 detailed sentences explaining:
            - Additional responsibilities
            - Supporting activities performed
            - Administrative or collateral duties
            - Other contributions to mission]
            
            **FACTOR EVALUATION SYSTEM**
            
            **Factor 1 – Knowledge Required by the Position: Level 1-X, XXXX Points**
            Summarize the education, certifications, and professional knowledge required, including foundational competencies. Describe the technical and specialized expertise needed, highlighting relevant methodologies, systems, or professional practices. Explain the complexity of concepts the position must apply and how professional judgment is used in ambiguous or novel situations. Justify why the factor level is appropriate using OPM standards and benchmark comparisons.

            **Factor 2 – Supervisory Controls: Level 2-X, XXX Points**
            Explain how assignments are given, the level of guidance, and expectations for work planning. Describe the degree of independence, decision-making authority, and when supervisor consultation is needed. Summarize the review process and evaluation criteria for completed work. Justify the factor level with reference to OPM standards and alignment of supervisory relationships.

            **Factor 3 – Guidelines: Level 3-X, XXX Points**
            Identify the types of guidelines used, their specificity, and comprehensiveness. Explain how judgment is applied to interpret, adapt, or integrate guidelines, including cases where guidance is incomplete or conflicting. Describe situations requiring the development of new approaches. Justify the factor level by referencing OPM standards and guideline usage.

            **Factor 4 – Complexity: Level 4-X, XXX Points**
            Summarize the variety and difficulty of problems, issues, or assignments encountered. Explain the factors and interrelationships analyzed and how they influence outcomes. Describe the analytical, problem-solving, and creative thinking required for complex situations. Justify the complexity level using OPM standards and comparisons to benchmark positions.

            **Factor 5 – Scope and Effect: Level 5-X, XXX Points**
            Describe the purpose, breadth, and range of work performed. Explain impacts within the organization and beyond, including other agencies, stakeholders, or policy development. Justify the scope and effect level based on OPM standards and alignment with position responsibilities.

            **Factor 6 – Personal Contacts: Level 6-X, XXX Points**
            Identify typical contacts, their roles, and organizational levels. Describe the settings, frequency, and formality of contacts. Justify the level based on OPM standards and how contact characteristics align with criteria.

            **Factor 7 – Purpose of Contacts: Level 7-X, XXX Points**
            Explain the objectives of contacts, such as information exchange, coordination, or negotiation. Describe the level of influence or persuasion required and challenges in achieving objectives. Justify the purpose level using OPM standards and alignment with position responsibilities.

            **Factor 8 – Physical Demands: Level 8-X, XX Points**
            Summarize physical activities required, their frequency, and duration. Note special requirements, dexterity needs, or environmental conditions, and justify the factor level using OPM standards.

            **Factor 9 – Work Environment: Level 9-X, XX Points**
            Describe the work setting and typical environmental conditions. Identify risks, hazards, or stress factors, and justify the work environment level using OPM standards.
            
            **Total Points: %s**
            **Final Grade: %s**
            **Grade Range: %s**
            
            **CONDITIONS OF EMPLOYMENT**
            
            [Write 4-5 specific sentences covering:
            - U.S. Citizenship requirement
            - Security clearance level required
            - Background investigation requirements
            - Professional certifications or licenses needed
            - Travel requirements (percentage and frequency)
            - Work schedule and flexibility requirements
            - Physical requirements or special conditions
            - Probationary period requirements
            - Drug testing or medical examination requirements
            - Any position-specific requirements]
            
            **Title and Series Determination**
            
            [Write 4-5 full sentences explaining:
            
            State the official position title and series (GS-%s, %s). Explain the basis for this series selection according to OPM standards. Describe how the primary duties align with the series definition. Reference specific elements from the series standard that match the position's work.
            Explain why this series is more appropriate than other potential series. Discuss any alternative series considered and why they were rejected.
            Describe how the position's specialized requirements, knowledge base, and work products support the series determination.
            Conclude with the official classification decision and its justification.]
            
            **Fair Labor Standards Act (FLSA) Determination**
            
            [Write 3-5 full sentences covering:
            
            State whether the position is FLSA Exempt or Non-Exempt. Explain the basis for this determination under FLSA regulations.
            Describe the specific duties and responsibilities that support the FLSA classification. Reference the professional, administrative, or executive exemption criteria as applicable.
            Explain how the position's requirements for discretion, independent judgment, and specialized knowledge support the FLSA determination.]
            
            ⚠️ REMEMBER: This document must be COMPLETE and DETAILED. Every section must contain full paragraphs with specific information. NO placeholders, brackets, or incomplete content. The total length should be 8000-12000 words for a complete, professional position description.
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