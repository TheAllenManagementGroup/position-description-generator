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
            ⚠️ CRITICAL INSTRUCTION: You MUST generate a COMPLETE, FULLY-DETAILED position description with NO placeholders, NO brackets, NO incomplete sections. Every section must contain full prose paragraphs with specific details.
            
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
            
            **═══ HEADER SECTION ═══**
            
            U.S. %s
            %s %s
            
            **Position Title:** %s
            **Series:** GS-%s
            **Grade:** %s
            **Supervisory Level:** %s
            
            ---
            
            **═══ INTRODUCTION ═══**
            
            [Write 5-7 comprehensive sentences that:
            - Explain the position's role within the organization
            - Describe the organizational mission and context
            - Detail primary areas of responsibility
            - Explain the impact and importance of the work
            - Describe key relationships and reporting structure
            - Highlight the complexity and scope of duties
            Make this SPECIFIC and DETAILED - use concrete examples and real responsibilities.]
            
            ---
            
            **═══ MAJOR DUTIES AND RESPONSIBILITIES ═══**
            
            **1. [First Major Duty Area] (35%%)**
            
            [Write 5-6 detailed sentences explaining:
            - Specific tasks and activities performed
            - Technical skills and knowledge applied
            - Deliverables and work products created
            - Frequency and duration of activities
            - Tools, systems, or methodologies used
            - Impact and importance of this duty area]
            
            **2. [Second Major Duty Area] (30%%)**
            
            [Write 5-6 detailed sentences covering:
            - Primary responsibilities in this area
            - Complexity of work performed
            - Coordination with others required
            - Decision-making authority involved
            - Standards and requirements followed
            - Outcomes and results expected]
            
            **3. [Third Major Duty Area] (25%%)**
            
            [Write 5-6 detailed sentences describing:
            - Nature of work in this area
            - Level of independence exercised
            - Problem-solving required
            - Stakeholder interactions involved
            - Quality and timeliness expectations
            - Contribution to organizational goals]
            
            **4. [Fourth Major Duty Area] (10%%)**
            
            [Write 3-4 detailed sentences explaining:
            - Additional responsibilities
            - Supporting activities performed
            - Administrative or collateral duties
            - Other contributions to mission]
            
            ---
            
            **═══ FACTOR EVALUATION SYSTEM ═══**
            
            **Factor 1 – Knowledge Required by the Position: Level 1-X, XXXX Points**
            
            [Write 4-5 full paragraphs (minimum 6 sentences each) that thoroughly explain:
            
            Paragraph 1: Detail the educational requirements, professional knowledge base, and foundational competencies required. Reference specific degree requirements, certifications, or professional credentials needed.
            
            Paragraph 2: Describe the technical and specialized knowledge required for the position. Explain the depth of expertise in specific subject matter areas, methodologies, systems, or professional practices.
            
            Paragraph 3: Explain the complexity of concepts the position must understand and apply. Discuss theoretical frameworks, analytical models, or advanced professional principles involved.
            
            Paragraph 4: Describe the level of professional judgment and decision-making authority required. Explain how knowledge is applied to complex, ambiguous, or unprecedented situations.
            
            Paragraph 5: Justify why this specific factor level is appropriate based on the OPM standards. Compare to benchmark positions and explain how the knowledge requirements align with the factor level description.]
            
            **Factor 2 – Supervisory Controls: Level 2-X, XXX Points**
            
            [Write 4 full paragraphs (minimum 5 sentences each) covering:
            
            Paragraph 1: Describe how assignments are received and the level of detail provided by the supervisor. Explain the nature of instructions given and the expectations for work planning.
            
            Paragraph 2: Explain the degree of independence the employee has in carrying out work. Describe the decision-making authority and when supervisor consultation is required.
            
            Paragraph 3: Detail the review process for completed work. Explain what aspects are reviewed, the frequency of review, and the criteria used for evaluation.
            
            Paragraph 4: Justify the factor level based on OPM standards. Explain how the supervisory relationship aligns with the level description.]
            
            **Factor 3 – Guidelines: Level 3-X, XXX Points**
            
            [Write 4 full paragraphs (minimum 5 sentences each) explaining:
            
            Paragraph 1: Identify the types of guidelines available (laws, regulations, policies, procedures, precedents, etc.). Describe their specificity and comprehensiveness.
            
            Paragraph 2: Explain the judgment required in selecting and applying guidelines. Describe situations where guidelines must be interpreted, adapted, or integrated from multiple sources.
            
            Paragraph 3: Discuss instances where guidelines are incomplete, conflicting, or absent. Explain how the employee must develop new approaches or recommend changes to existing guidelines.
            
            Paragraph 4: Justify the factor level by referencing OPM standards. Explain how guideline usage aligns with the level criteria.]
            
            **Factor 4 – Complexity: Level 4-X, XXX Points**
            
            [Write 4 full paragraphs (minimum 5 sentences each) describing:
            
            Paragraph 1: Describe the nature and variety of problems, issues, or situations encountered. Explain the diversity of work assignments and their difficulty.
            
            Paragraph 2: Explain the variables, factors, and interrelationships that must be analyzed. Describe how different elements interact and affect outcomes.
            
            Paragraph 3: Detail the analytical processes, problem-solving approaches, and creative thinking required. Explain how solutions are developed for complex or unprecedented situations.
            
            Paragraph 4: Justify the complexity level based on OPM standards. Compare work complexity to benchmark descriptions and explain the alignment.]
            
            **Factor 5 – Scope and Effect: Level 5-X, XXX Points**
            
            [Write 4 full paragraphs (minimum 5 sentences each) covering:
            
            Paragraph 1: Describe the purpose and breadth of the work performed. Explain what the position is designed to accomplish and the range of activities involved.
            
            Paragraph 2: Detail the impact within the immediate organization. Explain how the work affects operations, programs, policies, or services.
            
            Paragraph 3: Describe effects beyond the immediate organization. Explain impacts on other agencies, external stakeholders, the public, or policy development.
            
            Paragraph 4: Justify the scope and effect level using OPM standards. Explain how both scope and effect align with the level description.]
            
            **Factor 6 – Personal Contacts: Level 6-X, XXX Points**
            
            [Write 3 full paragraphs (minimum 4 sentences each) explaining:
            
            Paragraph 1: Identify the typical contacts (internal staff, management, other agencies, contractors, public, etc.). Describe their organizational levels and roles.
            
            Paragraph 2: Explain the settings and circumstances of contacts. Describe whether contacts are planned or unplanned, the formality level, and the frequency.
            
            Paragraph 3: Justify the personal contacts level based on OPM standards. Explain how contact characteristics align with the level criteria.]
            
            **Factor 7 – Purpose of Contacts: Level 7-X, XXX Points**
            
            [Write 3 full paragraphs (minimum 4 sentences each) describing:
            
            Paragraph 1: Explain the purposes for which contacts are made. Describe what must be accomplished through interactions (information exchange, coordination, negotiation, persuasion, etc.).
            
            Paragraph 2: Detail the level of influence, persuasion, or negotiation required. Explain the difficulty in achieving contact objectives, including any resistance or conflicting interests.
            
            Paragraph 3: Justify the purpose level using OPM standards. Explain how contact purposes align with the level description.]
            
            **Factor 8 – Physical Demands: Level 8-X, XX Points**
            
            [Write 2 full paragraphs (minimum 4 sentences each) covering:
            
            Paragraph 1: Describe the physical activities required (sitting, standing, walking, lifting, etc.). Explain the duration and frequency of physical demands.
            
            Paragraph 2: Note any special physical requirements, dexterity needs, or environmental conditions. Justify the factor level based on OPM standards.]
            
            **Factor 9 – Work Environment: Level 9-X, XX Points**
            
            [Write 2 full paragraphs (minimum 4 sentences each) explaining:
            
            Paragraph 1: Describe the work setting and typical environmental conditions. Explain where work is performed and the physical environment characteristics.
            
            Paragraph 2: Identify any risks, hazards, discomforts, or stress factors. Justify the work environment level using OPM standards.]
            
            ---
            
            **═══ EVALUATION SUMMARY ═══**
            
            | Factor | Level | Points |
            |--------|-------|--------|
            | 1 - Knowledge Required | 1-X | XXXX |
            | 2 - Supervisory Controls | 2-X | XXX |
            | 3 - Guidelines | 3-X | XXX |
            | 4 - Complexity | 4-X | XXX |
            | 5 - Scope and Effect | 5-X | XXX |
            | 6 - Personal Contacts | 6-X | XXX |
            | 7 - Purpose of Contacts | 7-X | XXX |
            | 8 - Physical Demands | 8-X | XX |
            | 9 - Work Environment | 9-X | XX |
            | **TOTAL** | | **%s** |
            
            **Final Grade:** %s
            **Grade Range:** %s points
            
            ---
            
            **═══ CONDITIONS OF EMPLOYMENT ═══**
            
            [Write 8-10 specific bullet points covering:
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
            
            ---
            
            **═══ CLASSIFICATION DETERMINATION ═══**
            
            **Title and Series Determination**
            
            [Write 4-5 full paragraphs (minimum 5 sentences each) explaining:
            
            Paragraph 1: State the official position title and series (GS-%s, %s). Explain the basis for this series selection according to OPM standards.
            
            Paragraph 2: Describe how the primary duties align with the series definition. Reference specific elements from the series standard that match the position's work.
            
            Paragraph 3: Explain why this series is more appropriate than other potential series. Discuss any alternative series considered and why they were rejected.
            
            Paragraph 4: Describe how the position's specialized requirements, knowledge base, and work products support the series determination.
            
            Paragraph 5: Conclude with the official classification decision and its justification.]
            
            **Fair Labor Standards Act (FLSA) Determination**
            
            [Write 3 full paragraphs (minimum 5 sentences each) covering:
            
            Paragraph 1: State whether the position is FLSA Exempt or Non-Exempt. Explain the basis for this determination under FLSA regulations.
            
            Paragraph 2: Describe the specific duties and responsibilities that support the FLSA classification. Reference the professional, administrative, or executive exemption criteria as applicable.
            
            Paragraph 3: Explain how the position's requirements for discretion, independent judgment, and specialized knowledge support the FLSA determination.]
            
            ---
            
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