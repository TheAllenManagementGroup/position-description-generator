package com.example.pdgenerator.service;

import com.example.pdgenerator.request.PdRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.util.List;
import java.util.ArrayList;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

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
            return buildComprehensivePrompt(request);
        } catch (Exception e) {
            System.err.println("Error in buildComprehensivePrompt: " + e.getMessage());
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
    Integer totalPoints = request.getTotalPoints() != null ? request.getTotalPoints() : null;
    String supervisoryLevel = request.getSupervisoryLevel() != null ? request.getSupervisoryLevel() : "Non-Supervisory";

    // Compose the summary block for the AI to use
    String summaryBlock = String.format("""
        IMPORTANT: The position description MUST use these values from the official grade analysis:
        - Final Grade: %s
        - Grade Range: %s
        - Total Points: %s

        Do NOT invent or recalculate these values. Use them exactly as provided.
        """, gsGrade, gradeRange, (totalPoints != null ? totalPoints : "[not provided]"));

    return String.format("""
        You are a federal HR classification specialist with expertise in creating comprehensive, detailed position descriptions that meet OPM standards.

        %s

        Create a COMPLETE, PROFESSIONAL federal position description using the EXACT format and level of detail shown in the provided DOE and DOJ samples. This must be a substantial document with comprehensive content in every section.

        CRITICAL REQUIREMENTS:
        - Use real, specific, technical content throughout - NO placeholders, brackets, or generic text
        - Each factor evaluation must include 3-4 detailed paragraphs explaining the specific knowledge, skills, and responsibilities
        - Major duties must be comprehensive with detailed task descriptions and realistic percentages
        - Include all sections: Introduction, Major Duties, complete Factor Analysis, Conditions of Employment, Title/Series Determination, and FLSA determination
        - Match the professional tone, technical depth, and comprehensive detail of the DOE/DOJ samples
        - Use proper federal terminology and classification standards throughout

        POSITION DETAILS:
        Job Series: GS-%s
        Position Title: %s
        Agency: %s
        Organization: %s %s
        Supervisory Level: %s
        Duties/Responsibilities Context: %s

        REQUIRED STRUCTURE AND CONTENT DEPTH:

        **HEADER:**
        U.S. %s
        %s %s
        %s
        GS-%s-[appropriate grade level]
        Organizational Title: %s

        **INTRODUCTION:** (Substantial paragraph, 4-6 sentences)
        Write a comprehensive introduction explaining the position's critical role, organizational context, key responsibilities, and impact. Include specific technical requirements and working relationships. Match the depth of the DOE/DOJ samples.

        MAJOR DUTIES:

        **MAJOR DUTIES 1** (with realistic percentages)
        Create comprehensive duty statement that includes:
        - Specific technical tasks and responsibilities
        - Detailed methodology and processes
        - Required knowledge and skills application
        - Measurable outcomes and impacts
        - Professional interactions and coordination requirements
        The duty should be 3-4 sentences minimum with technical depth.

        **MAJOR DUTIES 2 (with realistic percentages)**
        Create comprehensive duty statement that includes:
        - Specific technical tasks and responsibilities
        - Detailed methodology and processes
        - Required knowledge and skills application
        - Measurable outcomes and impacts
        - Professional interactions and coordination requirements
        The duty should be 3-4 sentences minimum with technical depth.

        **MAJOR DUTIES 3 (with realistic percentages)**
        Create comprehensive duty statement that includes:
        - Specific technical tasks and responsibilities
        - Detailed methodology and processes
        - Required knowledge and skills application
        - Measurable outcomes and impacts
        - Professional interactions and coordination requirements
        The duty should be 3-4 sentences minimum with technical depth.

        FACTOR EVALUATION - COMPLETE ANALYSIS:

        Factor 1 - Knowledge Required by the Position Level 1-X, XXXX Points**
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

        **Total Points: %s**
        **Final Grade: %s**
        **Grade Range: %s**

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

        Generate a complete, comprehensive position description that matches the professional quality, technical depth, and detailed analysis of the DOE and DOJ samples provided. Every section must contain substantial, specific content with no placeholders or generic statements.
        """,
        summaryBlock,
        jobSeries, subJobSeries, federalAgency, subOrganization, lowestOrg, supervisoryLevel, historicalData,
        federalAgency, subOrganization, lowestOrg, subJobSeries, jobSeries, subJobSeries,
        (gsGrade != null ? gsGrade.replace("GS-", "") : ""),
        (totalPoints != null ? totalPoints : "[not provided]"),
        (gsGrade != null ? gsGrade : ""),
        (gradeRange != null ? gradeRange : "")
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

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
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