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
import java.util.HashMap;
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
            this.maxTokens = 20000;
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

    private String buildFactorSectionInstruction(PdRequest request) {
    Map<String, String> factorLevels = request.getFactorLevels();
    Map<String, Integer> factorPoints = request.getFactorPoints();
    Integer totalPoints = request.getTotalPoints();
    String gsGrade = request.getGsGrade();
    String gradeRange = request.getGradeRange();

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

    StringBuilder section = new StringBuilder();
    
    section.append("⚠️ CRITICAL: YOU MUST GENERATE ALL 9 FACTORS - DO NOT SKIP ANY!\n\n");
    section.append("For EACH of the 9 factors listed below, write 2-5 sentences explaining how the position meets this level.\n\n");
    
    section.append("EXACT HEADER FORMAT (with spaces): **Factor 1 – Knowledge Required by the Position Level 1-7, 1250 Points**\n\n");
    section.append("═══════════════════════════════════════════════════════\n");
    section.append("LOCKED FACTOR VALUES - GENERATE ALL 9 FACTORS:\n");
    section.append("═══════════════════════════════════════════════════════\n\n");

    // List all 9 factors with their locked levels and points - WITH PROPER SPACING
    for (int i = 1; i <= 9; i++) {
        String factorNum = String.valueOf(i);
        String level = factorLevels.get(factorNum);
        Integer points = factorPoints.get(factorNum);
        
        // Format with explicit spaces: "Factor 1 – Knowledge... Level 1-7, 1250 Points"
        section.append(String.format("**Factor %d – %s Level %s, %d Points**\n\n",
            i, factorNames[i-1], level, points));
        section.append("[Write 2-5 sentences explaining this factor level]\n\n\n");
    }

    section.append("═══════════════════════════════════════════════════════\n\n");
    section.append("**EVALUATION SUMMARY:**\n\n");
    section.append("**Total Points: " + totalPoints + "**\n\n");
    section.append("**Final Grade: " + gsGrade + "**\n\n");
    section.append("**Grade Range: " + gradeRange + "**\n\n");
    section.append("═══════════════════════════════════════════════════════\n\n");
    
    section.append("⚠️ MANDATORY REQUIREMENTS:\n");
    section.append("- YOU MUST WRITE ALL 9 FACTORS - Factors 6, 7, 8, and 9 are NOT OPTIONAL\n");
    section.append("- Copy factor headers EXACTLY with spaces: 'Factor 3 – Guidelines Level 3-3, 275 Points'\n");
    section.append("- Do NOT change any levels or point values\n");
    section.append("- Write 2-5 sentences for each factor (keep it concise)\n");
    section.append("- Add TWO blank lines between each factor\n");
    section.append("- Do NOT stop at Factor 5 - continue through Factor 9\n");
    
    return section.toString();
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
    String supervisoryLevel = request.getSupervisoryLevel() != null ? request.getSupervisoryLevel() : "Non-Supervisory";

    // Get PDF summaries
    String factorGuide = pdfProcessingService.getFactorEvaluationSummary();
    String seriesGuide = pdfProcessingService.getSeriesGuideSummary();

    // ALWAYS ensure factors are generated and locked before building prompt
    if (request.getFactorLevels() == null || request.getFactorPoints() == null
        || request.getFactorLevels().size() != 9 || request.getFactorPoints().size() != 9) {
        
        System.out.println("Auto-generating factors for " + gsGrade);
        
        // Generate default factor levels and points for the assigned grade
        Map<String, String> factorLevels = getDefaultFactorLevelsForGrade(gsGrade, supervisoryLevel);
        Map<String, Integer> factorPoints = new HashMap<>();
        for (Map.Entry<String, String> entry : factorLevels.entrySet()) {
            factorPoints.put(entry.getKey(), getPointsForFactorLevel(entry.getKey(), entry.getValue()));
        }
        
        int calculatedTotalPoints = factorPoints.values().stream().mapToInt(Integer::intValue).sum();
        String calculatedGrade = calculateGradeFromPoints(calculatedTotalPoints);
        
        // Adjust if needed to match assigned grade
        if (!calculatedGrade.equals(gsGrade)) {
            adjustFactorsToTargetGrade(factorLevels, factorPoints, gsGrade);
            calculatedTotalPoints = factorPoints.values().stream().mapToInt(Integer::intValue).sum();
        }
        
        String calculatedGradeRange = getGradeRange(gsGrade);

        // Lock these into the request
        request.setFactorLevels(factorLevels);
        request.setFactorPoints(factorPoints);
        request.setTotalPoints(calculatedTotalPoints);
        request.setGradeRange(calculatedGradeRange);
        
        System.out.println("Generated factors: " + calculatedTotalPoints + " points -> " + gsGrade);
    }

    // Build the factor section instruction with locked values
    String factorSection = buildFactorSectionInstruction(request);
    String relevantFactors = pdfProcessingService.getRelevantFactorGuide(jobSeries, historicalData, gsGrade);

    String formattingInstructions = """
    CRITICAL FORMATTING REQUIREMENTS - FOLLOW EXACTLY:
    
    1. HEADER SECTION:
       - Each variable MUST be on its own line
       - Add a blank line between EACH variable
       - Format: "Job Series: GS-0343" (then blank line)
       - Format: "Position Title: Program Analyst" (then blank line)
       - Continue for ALL header variables
    
    2. FACTOR HEADERS:
       - MUST have spaces: "Factor 1 – Knowledge Required by the Position Level 1-7, 1250 Points"
       - NOT: "Factor1" or "Level1-7" (no spaces is WRONG)
       - Add TWO blank lines after each factor's paragraphs before the next factor
    
    3. ALL 9 FACTORS REQUIRED:
       - Factor 1 – Knowledge Required by the Position
       - Factor 2 – Supervisory Controls
       - Factor 3 – Guidelines
       - Factor 4 – Complexity
       - Factor 5 – Scope and Effect
       - Factor 6 – Personal Contacts
       - Factor 7 – Purpose of Contacts
       - Factor 8 – Physical Demands
       - Factor 9 – Work Environment
       - Write 2-4 paragraphs for EACH factor (do not skip any)
    
    4. SECTION SPACING:
       - TWO blank lines before section headers
       - TWO blank lines after section headers
       - Section headers on their own line
    
    5. NO bullet points anywhere - use numbered duties and prose only
    """;

    return String.format("""
%s

You are a federal HR classification specialist with expertise in creating comprehensive, detailed position descriptions that meet OPM standards.

Create a COMPLETE, PROFESSIONAL federal position description. This must be a substantial document with comprehensive content in every section.


**HEADER**

Job Series: GS-%s

Position Title: %s

Agency: %s

Organization: %s

Lowest Organization: %s

Supervisory Level: %s


--------------------------------------------------


**INTRODUCTION**

(Write 5-7 detailed sentences here explaining role, mission, responsibilities)


--------------------------------------------------


**MAJOR DUTIES**

(Create 3-4 major duty areas with realistic percentages totaling 100%%)

1. [Duty Title] (XX%%)

[Write 3-4 detailed sentences describing this duty]

2. [Duty Title] (XX%%)

[Write 3-4 detailed sentences describing this duty]


--------------------------------------------------


**FACTOR EVALUATION - COMPLETE ANALYSIS**

YOU MUST GENERATE ALL 9 FACTORS WITH 2-4 PARAGRAPHS EACH:

%s


--------------------------------------------------


**CONDITIONS OF EMPLOYMENT**

Create 5-7 complete sentences covering required certifications, security clearance, training requirements, travel, physical requirements, and other conditions.


--------------------------------------------------


**TITLE AND SERIES DETERMINATION**

Write 2-3 detailed paragraphs explaining:
- Rationale for the specific job series assignment (GS-%s)
- How duties align with series definition
- Professional requirements and qualifications justification
- Title appropriateness


--------------------------------------------------


**FAIR LABOR STANDARDS ACT DETERMINATION**

Provide specific justification for exempt/non-exempt status with detailed reasoning based on professional duties and requirements (2 paragraphs minimum).
""",
formattingInstructions, factorGuide, seriesGuide, relevantFactors,
jobSeries, subJobSeries, federalAgency, subOrganization, lowestOrg,
supervisoryLevel,
factorSection,
jobSeries
);
}

/**
 * Get default factor levels appropriate for each grade
 */
private Map<String, String> getDefaultFactorLevelsForGrade(String grade, String supervisoryLevel) {
    Map<String, String> levels = new HashMap<>();
    
    switch (grade.toUpperCase()) {
        case "GS-15":
            levels.put("1", "1-9"); // Expert authority
            levels.put("2", "2-5"); // Administrative direction only
            levels.put("3", "3-5"); // Precedent-setting
            levels.put("4", "4-6"); // Unprecedented complexity
            levels.put("5", "5-6"); // Government-wide impact
            levels.put("6", "6-4"); // High-level external contacts
            levels.put("7", "7-4"); // Policy influence
            levels.put("8", "8-1"); // Sedentary
            levels.put("9", "9-2"); // High stress
            break;
            
        case "GS-14":
            levels.put("1", "1-8"); // Subject matter expert
            levels.put("2", "2-5"); // Considerable independence
            levels.put("3", "3-4"); // Significant interpretation
            levels.put("4", "4-5"); // Highly complex
            levels.put("5", "5-5"); // Agency-wide impact
            levels.put("6", "6-4"); // Executive-level contacts
            levels.put("7", "7-4"); // Influence/negotiate
            levels.put("8", "8-1"); // Sedentary
            levels.put("9", "9-2"); // Moderate-high stress
            break;
            
        case "GS-13":
            levels.put("1", "1-7"); // Advanced professional
            levels.put("2", "2-4"); // General supervision
            levels.put("3", "3-4"); // Requires interpretation
            levels.put("4", "4-5"); // Complex problems
            levels.put("5", "5-4"); // Program-level impact
            levels.put("6", "6-3"); // External stakeholders
            levels.put("7", "7-3"); // Coordination/influence
            levels.put("8", "8-1"); // Sedentary
            levels.put("9", "9-1"); // Normal office
            break;
            
        case "GS-12":
            levels.put("1", "1-6"); // Senior professional
            levels.put("2", "2-4"); // General direction
            levels.put("3", "3-3"); // Some interpretation
            levels.put("4", "4-4"); // Moderately complex
            levels.put("5", "5-3"); // Unit/project impact
            levels.put("6", "6-3"); // Various stakeholders
            levels.put("7", "7-2"); // Coordinate/resolve
            levels.put("8", "8-1"); // Sedentary
            levels.put("9", "9-1"); // Normal office
            break;
            
        case "GS-11":
            levels.put("1", "1-5"); // Full professional
            levels.put("2", "2-3"); // Specific instruction
            levels.put("3", "3-3"); // Clear guidelines
            levels.put("4", "4-3"); // Varied problems
            levels.put("5", "5-3"); // Moderate scope
            levels.put("6", "6-2"); // Internal/some external
            levels.put("7", "7-2"); // Information exchange
            levels.put("8", "8-1"); // Sedentary
            levels.put("9", "9-1"); // Normal office
            break;
            
        case "GS-9":
        case "GS-09":
            levels.put("1", "1-4"); // Developmental professional
            levels.put("2", "2-2"); // Close supervision
            levels.put("3", "3-2"); // Clear procedures
            levels.put("4", "4-2"); // Routine complexity
            levels.put("5", "5-2"); // Limited scope
            levels.put("6", "6-2"); // Routine contacts
            levels.put("7", "7-1"); // Exchange info
            levels.put("8", "8-1"); // Sedentary
            levels.put("9", "9-1"); // Normal office
            break;
            
        default: // GS-7
            levels.put("1", "1-3"); // Entry professional
            levels.put("2", "2-2"); // Close supervision
            levels.put("3", "3-2"); // Detailed procedures
            levels.put("4", "4-2"); // Simple problems
            levels.put("5", "5-2"); // Narrow scope
            levels.put("6", "6-1"); // Internal contacts
            levels.put("7", "7-1"); // Exchange info
            levels.put("8", "8-1"); // Sedentary
            levels.put("9", "9-1"); // Normal office
    }
    
    // Adjust Factor 2 for supervisory positions
    if ("Supervisor".equalsIgnoreCase(supervisoryLevel) || "Manager".equalsIgnoreCase(supervisoryLevel)) {
        String currentF2 = levels.get("2");
        int currentF2Level = Integer.parseInt(currentF2.split("-")[1]);
        if (currentF2Level < 4) {
            levels.put("2", "2-4"); // Bump up for supervisory
        }
    }
    
    return levels;
}

/**
 * Get point value for a factor level
 */
private int getPointsForFactorLevel(String factorNum, String level) {
    Map<String, Map<String, Integer>> FACTOR_POINTS = Map.of(
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
    
    return FACTOR_POINTS.getOrDefault(factorNum, Map.of()).getOrDefault(level, 0);
}

/**
 * Calculate grade from total points
 */
private String calculateGradeFromPoints(int points) {
    if (points >= 4055) return "GS-15";
    if (points >= 3605) return "GS-14";
    if (points >= 3155) return "GS-13";
    if (points >= 2755) return "GS-12";
    if (points >= 2355) return "GS-11";
    if (points >= 1855) return "GS-9";
    if (points >= 1355) return "GS-7";
    return "GS-5";
}

/**
 * Get grade range for a grade
 */
private String getGradeRange(String grade) {
    switch (grade.toUpperCase()) {
        case "GS-15": return "4055+";
        case "GS-14": return "3605-4050";
        case "GS-13": return "3155-3600";
        case "GS-12": return "2755-3150";
        case "GS-11": return "2355-2750";
        case "GS-9": case "GS-09": return "1855-2100";
        case "GS-7": case "GS-07": return "1355-1600";
        default: return "855-1100";
    }
}

/**
 * Adjust factors to hit target grade
 */
private void adjustFactorsToTargetGrade(Map<String, String> levels, Map<String, Integer> points, String targetGrade) {
    int targetMin = getMinPointsForTargetGrade(targetGrade);
    int currentTotal = points.values().stream().mapToInt(Integer::intValue).sum();
    
    if (currentTotal < targetMin) {
        // Boost Factor 1 or 5
        String[] boostOrder = {"1", "5", "2", "4"};
        for (String factor : boostOrder) {
            if (currentTotal >= targetMin) break;
            String currentLevel = levels.get(factor);
            String nextLevel = getNextHigherLevel(factor, currentLevel);
            if (nextLevel != null) {
                int oldPoints = points.get(factor);
                int newPoints = getPointsForFactorLevel(factor, nextLevel);
                levels.put(factor, nextLevel);
                points.put(factor, newPoints);
                currentTotal += (newPoints - oldPoints);
            }
        }
    }
}

private int getMinPointsForTargetGrade(String grade) {
    switch (grade.toUpperCase()) {
        case "GS-15": return 4055;
        case "GS-14": return 3605;
        case "GS-13": return 3155;
        case "GS-12": return 2755;
        case "GS-11": return 2355;
        case "GS-9": case "GS-09": return 1855;
        case "GS-7": case "GS-07": return 1355;
        default: return 855;
    }
}

private String getNextHigherLevel(String factor, String currentLevel) {
    String[] parts = currentLevel.split("-");
    int current = Integer.parseInt(parts[1]);
    int max = getMaxLevelForFactor(factor);
    return (current < max) ? factor + "-" + (current + 1) : null;
}

private int getMaxLevelForFactor(String factor) {
    switch (factor) {
        case "1": return 9;
        case "2": case "3": return 5;
        case "4": case "5": return 6;
        case "6": case "7": return 4;
        case "8": case "9": return 3;
        default: return 1;
    }
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
        
        String formattingInstructions = """
        IMPORTANT FORMATTING INSTRUCTIONS:
        - Every section header (e.g., **HEADER**, **INTRODUCTION**, **MAJOR DUTIES**, etc.) MUST be bolded with double asterisks.
        - There MUST be TWO blank lines before and after every section header.
        - Section headers and their content MUST NOT run together; always start content on a new line after the header.
        - Bulleted lists and paragraphs MUST be separated by blank lines for readability.
        - Summary sections (Total Points, Final Grade, Grade Range) MUST each be on their own line, bolded, and separated by blank lines.
        - Do NOT use tables or outlines; only prose paragraphs and bulleted lists.
        """;
        
        return String.format("""
            %s
            
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
            formattingInstructions,
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