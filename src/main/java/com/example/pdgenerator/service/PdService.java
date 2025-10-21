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
     * Helper to sanitize header fields (removes line breaks and extra spaces)
     */
    private String cleanHeaderField(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\r\\n]+", " ").replaceAll("\\s{2,}", " ").trim();
    }

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

    public String buildConcisePrompt(PdRequest request) {
        // Use user-selected values (no defaults)
        String jobSeries = cleanHeaderField(request.getJobSeries());
        String positionTitle = cleanHeaderField(request.getPositionTitle());
        String agency = cleanHeaderField(request.getFederalAgency());
        String organization = cleanHeaderField(request.getSubOrganization());
        String lowestOrg = cleanHeaderField(request.getLowestOrg());
        String supervisoryLevel = cleanHeaderField(request.getSupervisoryLevel());
        String gsGrade = cleanHeaderField(request.getGsGrade());

        // Log what will be used in the prompt
        System.out.println("[Prompt] Using jobSeries: " + jobSeries);
        System.out.println("[Prompt] Using positionTitle: " + positionTitle);

        // Ensure factors and points are locked and aligned with assigned grade
        Map<String, String> factorLevels = request.getFactorLevels();
        Map<String, Integer> factorPoints = request.getFactorPoints();

        if (factorLevels == null || factorPoints == null || factorLevels.size() != 9 || factorPoints.size() != 9) {
            factorLevels = getDefaultFactorLevelsForGrade(gsGrade, supervisoryLevel);
            factorPoints = new HashMap<>();
            for (Map.Entry<String, String> entry : factorLevels.entrySet()) {
                factorPoints.put(entry.getKey(), getPointsForFactorLevel(entry.getKey(), entry.getValue()));
            }
        }

        int totalPoints = factorPoints.values().stream().mapToInt(Integer::intValue).sum();
        String calculatedGrade = calculateGradeFromPoints(totalPoints);

        // Adjust factors if they don't match the assigned grade
        if (!calculatedGrade.equals(gsGrade)) {
            adjustFactorsToTargetGrade(factorLevels, factorPoints, gsGrade);
            totalPoints = factorPoints.values().stream().mapToInt(Integer::intValue).sum();
        }

        String gradeRange = getPointRangeForGrade(gsGrade);

        // Build factor evaluation section with correct formatting
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
        StringBuilder factorEval = new StringBuilder();
        for (int i = 1; i <= 9; i++) {
            String factorNum = String.valueOf(i);
            String level = factorLevels.get(factorNum);
            Integer points = factorPoints.get(factorNum);
            // Header on one line, blank line, then description on next line
            factorEval.append(String.format(
                "**Factor %d – %s Level %s, %d Points**\n\n[Write 1-2 sentences explaining this factor level.]\n\n\n",
                i, factorNames[i-1], level, points
            ));
        }

        return String.format("""
You are an expert federal HR classification specialist.
Create a concise, high-quality federal position description (PD) that adheres to federal HR standards. Be brief and to the point.

**HEADER**

Job Series: GS-%s

Position Title: %s

Agency: %s

Organization: %s

Lowest Organization: %s

Supervisory Level: %s

**INTRODUCTION**

[1 sentence on role and mission]

**MAJOR DUTIES**

1. [Duty Title] (%%): [1 sentence of detail.]
2. [Duty Title] (%%): [1 sentence of detail.]
3. [Duty Title] (%%): [1 sentence of detail.]

**FACTOR EVALUATION**

%s

**EVALUATION SUMMARY**

Total Points: %d

Final Grade: %s

Grade Range: %s

**CONDITIONS OF EMPLOYMENT**

[1 sentence on requirements]

**TITLE AND SERIES DETERMINATION**

Assess whether the user-selected job series and position title below are appropriate for the provided duties. 
- If they are appropriate, briefly justify why.
- If they are NOT appropriate, explain which job series and/or position title would be a better fit for the duties, and why, but DO NOT change the actual Job Series or Position Title in the HEADER or elsewhere in the document.

User selections:
Job Series: GS-%s
Position Title: %s

**FAIR LABOR STANDARDS ACT DETERMINATION**

[1 sentence on FLSA status]
""",
        jobSeries, positionTitle, agency, organization, lowestOrg, supervisoryLevel,
        factorEval.toString(),
        totalPoints, gsGrade, gradeRange,
        jobSeries, positionTitle
        );
    }

    // Helper to build factor headers for concise prompt
    private String buildFactorHeaders(PdRequest request) {
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
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 9; i++) {
            String level = "";
            Integer points = 0;
            if (request.getFactorLevels() != null && request.getFactorLevels().get(String.valueOf(i)) != null) {
                level = String.valueOf(request.getFactorLevels().get(String.valueOf(i)));
            }
            if (request.getFactorPoints() != null && request.getFactorPoints().get(String.valueOf(i)) != null) {
                points = request.getFactorPoints().get(String.valueOf(i));
            }
            sb.append(String.format("**Factor %d – %s Level %s, %d Points**\n", i, factorNames[i-1], level, points));
        }
        return sb.toString();
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
        section.append("For EACH of the 9 factors listed below, write 2-4 sentences explaining how the position meets this level.\n\n");
        
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
            // CRITICAL: Factor header on one line, then blank line, then description on a new line
            section.append(String.format("**Factor %d – %s Level %s, %d Points**\n\n",
                i, factorNames[i-1], level, points));
            section.append("(Write 2-5 sentences explaining this factor level here - on a separate line from the header)\n\n\n");
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
        section.append("- CRITICAL: Factor header must be on its own line, then a blank line, THEN the description on a new line\n");
        section.append("- Do NOT change any levels or point values - copy them exactly as shown\n");
        section.append("- Write 2-5 sentences for each factor (keep it concise)\n");
        section.append("- Add TWO blank lines after each factor's description before the next factor header\n");
        section.append("- Do NOT stop at Factor 5 - continue through Factor 9\n");
        section.append("- Example format:\n");
        section.append("  **Factor 1 – Knowledge Required by the Position Level 1-7, 1250 Points**\n\n");
        section.append("  The incumbent must possess extensive knowledge... (description here)\n\n\n");
        section.append("  **Factor 2 – Supervisory Controls Level 2-4, 450 Points**\n\n");
        section.append("  The incumbent works under general supervision... (description here)\n");
        
        return section.toString();
    }

    /**
     * Build a GSSG-style 6-factor section for supervisory positions (4A/4B terminology noted if needed).
     */
    private String buildGssgFactorSectionInstruction(PdRequest request) {
    Map<String, String> factorLevels = request.getFactorLevels();
    Map<String, Integer> factorPoints = request.getFactorPoints();
    Integer totalPoints = request.getTotalPoints();
    String gsGrade = request.getGsGrade();
    String gradeRange = request.getGradeRange();

    StringBuilder section = new StringBuilder();

    section.append("⚠️⚠️⚠️ CRITICAL FORMATTING REQUIREMENT ⚠️⚠️⚠️\n\n");
    section.append("WHEN YOU WRITE FACTOR 4A AND 4B, YOU MUST PUT A SPACE AFTER 'Factor':\n");
    section.append("CORRECT: **Factor 4A – PERSONAL CONTACTS (NATURE OF CONTACTS) Level 4-4, 100 Points**\n");
    section.append("WRONG: **Factor4A – PERSONAL CONTACTS (NATURE OF CONTACTS) Level4-4,100 Points**\n\n");
    section.append("CORRECT: **Factor 4B – PERSONAL CONTACTS (PURPOSE OF CONTACTS) Level 4-4, 125 Points**\n");
    section.append("WRONG: **Factor4B – PERSONAL CONTACTS (PURPOSE OF CONTACTS) Level4-4,125 Points**\n\n");
    
    section.append("SPACING CHECKLIST FOR EVERY FACTOR:\n");
    section.append("□ Space after 'Factor': 'Factor 4A' NOT 'Factor4A'\n");
    section.append("□ Space after 'Level': 'Level 4-4, 100' NOT 'Level4-4,100'\n");
    section.append("□ Space after comma: '4-4, 100' NOT '4-4,100'\n");
    section.append("□ Never use 'G:' prefix\n\n");
    
    section.append("═══════════════════════════════════════════════════════\n");
    section.append("GENERATE ALL 6 FACTORS WITH EXACT FORMATTING:\n");
    section.append("═══════════════════════════════════════════════════════\n\n");

    // List the 6 GSSG factors with their locked levels and points
    for (int i = 1; i <= 6; i++) {
        if (i == 4) {
            String lvlA = (factorLevels != null && factorLevels.get("4A") != null) ? factorLevels.get("4A") : "";
            String lvlA_clean = lvlA.startsWith("G:") ? lvlA.substring(2) : lvlA;
            Integer ptsA = (factorPoints != null && factorPoints.get("4A") != null) ? factorPoints.get("4A") : 0;
            
            // Show the EXACT format they should copy
            section.append("COPY THIS EXACTLY (with the space after Factor):\n");
            section.append(String.format("**Factor 4A – PERSONAL CONTACTS (NATURE OF CONTACTS) Level %s, %d Points**\n\n",
                lvlA_clean, ptsA));
            section.append("Write 2-5 sentences explaining this subfactor level.\n\n\n");

            String lvlB = (factorLevels != null && factorLevels.get("4B") != null) ? factorLevels.get("4B") : "";
            String lvlB_clean = lvlB.startsWith("G:") ? lvlB.substring(2) : lvlB;
            Integer ptsB = (factorPoints != null && factorPoints.get("4B") != null) ? factorPoints.get("4B") : 0;
            
            section.append("COPY THIS EXACTLY (with the space after Factor):\n");
            section.append(String.format("**Factor 4B – PERSONAL CONTACTS (PURPOSE OF CONTACTS) Level %s, %d Points**\n\n",
                lvlB_clean, ptsB));
            section.append("Write 2-5 sentences explaining this subfactor level.\n\n\n");
            continue;
        }

        String factorNum = String.valueOf(i);
        String level = (factorLevels != null && factorLevels.get(factorNum) != null) ? factorLevels.get(factorNum) : "";
        String level_clean = level.startsWith("G:") ? level.substring(2) : level;
        Integer points = (factorPoints != null && factorPoints.get(factorNum) != null) ? factorPoints.get(factorNum) : 0;

        section.append(String.format("**Factor %d – %s Level %s, %d Points**\n\n",
            i, getSupervisoryFactorName(i), level_clean, points));
        section.append("Write 2-5 sentences explaining this factor level.\n\n\n");
    }

    section.append("═══════════════════════════════════════════════════════\n\n");
    section.append("**EVALUATION SUMMARY:**\n\n");
    section.append("**Total Points: " + totalPoints + "**\n\n");
    section.append("**Final Grade: " + gsGrade + "**\n\n");
    section.append("**Grade Range: " + gradeRange + "**\n\n");
    section.append("═══════════════════════════════════════════════════════\n\n");

    section.append("⚠️ FINAL REMINDER:\n");
    section.append("When you write Factor 4A, you MUST write: 'Factor 4A' (with space)\n");
    section.append("When you write Factor 4B, you MUST write: 'Factor 4B' (with space)\n");
    section.append("When you write Level, you MUST write: 'Level 4-4, 100' (with spaces)\n");
    section.append("DO NOT write: 'Factor4A', 'Factor4B', 'Level4-4,100', or 'Level4-4,125'\n\n");

    return section.toString();
}

    // Add authoritative supervisory factor name helper
    private String getSupervisoryFactorName(int i) {
        switch (i) {
            case 1: return "PROGRAM SCOPE AND EFFECT";
            case 2: return "ORGANIZATIONAL SETTING";
            case 3: return "SUPERVISORY & MANAGERIAL AUTHORITY EXERCISED";
            case 4: return "PERSONAL CONTACTS (NATURE & PURPOSE)";
            case 5: return "DIFFICULTY OF TYPICAL WORK DIRECTED";
            case 6: return "OTHER CONDITIONS";
            default: return "Factor " + i;
        }
    }

    private boolean isSupervisoryPosition(String supervisoryLevel) {
    if (supervisoryLevel == null) return false;
    String level = supervisoryLevel.trim();
    
    // Exact match for the three valid values
    return level.equalsIgnoreCase("Supervisor") 
        || level.equalsIgnoreCase("Team Lead");
}

/**
 * Validate that factor configuration matches supervisory status
 */
private void validateFactorConsistency(PdRequest request, boolean isSupervisory) {
    Map<String, String> levels = request.getFactorLevels();
    if (levels == null) {
        System.err.println("⚠️ Factor levels are null!");
        return;
    }
    
    int expectedSize = isSupervisory ? 6 : 9;
    if (levels.size() != expectedSize) {
        System.err.println("⚠️ FACTOR MISMATCH: Expected " + expectedSize + 
                          " factors for supervisory=" + isSupervisory + 
                          " but got " + levels.size());
        System.err.println("   Factor keys: " + levels.keySet());
    }
    
    // Check for GSSG markers
    boolean hasGSSGMarkers = levels.values().stream()
        .anyMatch(v -> v != null && v.startsWith("G:"));
    System.out.println("Has GSSG markers: " + hasGSSGMarkers + 
                      ", should have: " + isSupervisory);
}

/**
 * Enhanced prompt using cached PDF content from PdfProcessingService
 */
private String buildComprehensivePromptWithPDFs(PdRequest request) {
    String jobSeries = cleanHeaderField(request.getJobSeries());
    String positionTitle = cleanHeaderField(request.getPositionTitle());
    String federalAgency = cleanHeaderField(request.getFederalAgency());
    String subOrganization = cleanHeaderField(request.getSubOrganization());
    String lowestOrg = cleanHeaderField(request.getLowestOrg());
    String historicalData = request.getHistoricalData();
    String gsGrade = cleanHeaderField(request.getGsGrade());
    String supervisoryLevel = cleanHeaderField(request.getSupervisoryLevel());

    // Use centralized check
    boolean isSupervisory = isSupervisoryPosition(supervisoryLevel);

    // --- Add logging for all header variables ---
    System.out.println("[PD GENERATION] Variables for prompt:");
    System.out.println("  jobSeries: " + jobSeries);
    System.out.println("  positionTitle: " + positionTitle);
    System.out.println("  federalAgency: " + federalAgency);
    System.out.println("  subOrganization: " + subOrganization);
    System.out.println("  lowestOrg: " + lowestOrg);
    System.out.println("  historicalData: " + (historicalData != null ? historicalData.substring(0, Math.min(100, historicalData.length())) + (historicalData.length() > 100 ? "..." : "") : "null"));
    System.out.println("  gsGrade: " + gsGrade);
    System.out.println("  supervisoryLevel: " + supervisoryLevel);
    System.out.println("  isSupervisory: " + isSupervisory);

    // Get PDF summaries
    String factorGuide = pdfProcessingService.getFactorEvaluationSummary();
    String seriesGuide = pdfProcessingService.getSeriesGuideSummary();

    // ALWAYS ensure factors are generated and locked before building prompt
    if (request.getFactorLevels() == null || request.getFactorPoints() == null
        || (isSupervisory && request.getFactorLevels().size() != 6)
        || (!isSupervisory && request.getFactorLevels().size() != 9)) {
        
        System.out.println("Auto-generating factors for " + gsGrade + " (supervisory: " + isSupervisory + ")");
        
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
        System.out.println("Factor count: " + factorLevels.size());
        System.out.println("Factor keys: " + factorLevels.keySet());
    }

    // Validate factor consistency
    validateFactorConsistency(request, isSupervisory);

    // Build the factor section instruction with locked values
    String factorSection;
    if (isSupervisory) {
        System.out.println("Using GSSG 6-factor template");
        factorSection = buildGssgFactorSectionInstruction(request);
    } else {
        System.out.println("Using standard 9-factor template");
        factorSection = buildFactorSectionInstruction(request);
    }

    String relevantFactors = pdfProcessingService.getRelevantFactorGuide(jobSeries, historicalData, gsGrade);

    String formattingInstructions = """
        CRITICAL FORMATTING REQUIREMENTS - FOLLOW EXACTLY:
        
        1. HEADER SECTION - EACH VARIABLE ON ITS OWN LINE WITH BLANK LINE AFTER:
           Job Series: GS-0343
           
           Position Title: Program Analyst
           
           Agency: U.S. Department of Justice
           
           Lowest Organization: Office of Budget
           
           Supervisory Level: Non-Supervisory

           GS Grade: GS-13
        
        2. FACTOR HEADERS - THREE COMPONENTS WITH PROPER SPACING:
           - Header on one line: **Factor 1 – Knowledge Required by the Position Level 1-7, 1250 Points**
           - Then a blank line
           - Then description (2-5 sentences) on the next line
           - Then TWO blank lines before next factor
           
           CORRECT FORMAT EXAMPLE:
           **Factor 1 – Knowledge Required by the Position Level 1-7, 1250 Points**
           
           The incumbent must possess extensive knowledge of analytical techniques...
           
           
           **Factor 2 – Supervisory Controls Level 2-4, 450 Points**
           
           The incumbent works under general supervision...
        
        3. ALL 9 FACTORS REQUIRED - DO NOT SKIP:
           - Factor 1 – Knowledge Required by the Position
           - Factor 2 – Supervisory Controls
           - Factor 3 – Guidelines
           - Factor 4 – Complexity
           - Factor 5 – Scope and Effect
           - Factor 6 – Personal Contacts
           - Factor 7 – Purpose of Contacts
           - Factor 8 – Physical Demands
           - Factor 9 – Work Environment
        
        4. MAJOR DUTIES - EACH DUTY REQUIRES:
           - Duty number and title with percentage
           - 4-5 detailed sentences describing the duty
           - Blank line before next duty
        
        5. SECTION SPACING:
           - TWO blank lines before section headers
           - TWO blank lines after section headers
        """;

    String summaryInstructions = String.format("""
CRITICAL: In the **EVALUATION SUMMARY** section, you MUST include the following, each on its own line and bolded:
**Total Points: %d**

**Final Grade: %s**

**Grade Range: %s**

Copy these values exactly as shown. Do NOT invent or change them.
""", request.getTotalPoints(), request.getGsGrade(), request.getGradeRange());

    return String.format("""
%s

Duties may be listed with percentages. Duties with higher percentages must be given greater weight and considered more important in your analysis. Percentages if present depict the importance of that duty.

You are a federal HR classification specialist with expertise in creating comprehensive, detailed position descriptions that meet OPM standards.

Create a COMPLETE, PROFESSIONAL federal position description. This must be a substantial document with comprehensive content in every section.


**HEADER**

Job Series: GS-%s

Position Title: %s

Agency: %s

Organization: %s

Lowest Organization: %s

Supervisory Level: %s

Grade: %s

**INTRODUCTION**

(Write 5-7 detailed sentences here explaining role, mission, responsibilities)

**MAJOR DUTIES**

(Create 3-4 major duty areas with realistic percentages totaling 100%%)

1. [Duty Title] (XX%%)

[Write 3-4 detailed sentences describing this duty]

2. [Duty Title] (XX%%)

[Write 3-4 detailed sentences describing this duty]


--------------------------------------------------


**FACTOR EVALUATION COMPLETE ANALYSIS**

YOU MUST GENERATE ALL FACTORS WITH 2-4 SENTENCES EACH:

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

%s
""",
        formattingInstructions, jobSeries, positionTitle, federalAgency, subOrganization,
        lowestOrg, supervisoryLevel, gsGrade, factorSection, jobSeries, summaryInstructions,historicalData, relevantFactors, seriesGuide, factorGuide
        );
}

/**
 * Get default factor levels appropriate for each grade
 * - For supervisory positions returns GSSG 6-factor keys (1,2,3,4A,4B,5,6)
 *   with a "G:" prefix on levels so getPointsForFactorLevel knows to use GSSG mapping.
 */
public Map<String, String> getDefaultFactorLevelsForGrade(String grade, String supervisoryLevel) {
    boolean isSup = isSupervisoryPosition(supervisoryLevel);
    Map<String, String> levels = new HashMap<>();

    if (isSup) {
        System.out.println("[getDefaultFactorLevelsForGrade] Generating GSSG 6 factors for grade " + grade);
        // GSSG 6-factor defaults (level strings prefixed with "G:" to indicate GSSG mapping)
        switch (grade.toUpperCase()) {
            case "GS-15":
                levels.put("1", "G:1-4");
                levels.put("2", "G:2-4");
                levels.put("3", "G:3-5");
                levels.put("4A", "G:4-4");
                levels.put("4B", "G:4-4");
                levels.put("5", "G:5-6");
                levels.put("6", "G:6-2");
                break;
            case "GS-14":
                levels.put("1", "G:1-4");
                levels.put("2", "G:2-4");
                levels.put("3", "G:3-4");
                levels.put("4A", "G:4-3");
                levels.put("4B", "G:4-3");
                levels.put("5", "G:5-5");
                levels.put("6", "G:6-2");
                break;
            case "GS-13":
            default:
                // Example defaults aligned with supervisory sample you provided
                levels.put("1", "G:1-3");   // PROGRAM SCOPE AND EFFECT
                levels.put("2", "G:2-3");   // ORGANIZATIONAL SETTING
                levels.put("3", "G:3-4");   // SUPERVISORY & MANAGERIAL AUTHORITY EXERCISED
                levels.put("4A", "G:4-4");  // PERSONAL CONTACTS (NATURE)
                levels.put("4B", "G:4-4");  // PERSONAL CONTACTS (PURPOSE)
                levels.put("5", "G:5-5");   // DIFFICULTY OF TYPICAL WORK DIRECTED
                levels.put("6", "G:6-2");   // OTHER CONDITIONS
        }

        System.out.println("[getDefaultFactorLevelsForGrade] Generated GSSG factors: " + levels.keySet());
        return levels;
    }

    System.out.println("[getDefaultFactorLevelsForGrade] Generating standard 9 factors for grade " + grade);
    // Non-supervisory (existing 9-factor logic)
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

    System.out.println("[getDefaultFactorLevelsForGrade] Generated standard factors: " + levels.keySet());
    return levels;
}

    public String getPointRangeForGrade(String grade) {
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
     * Get point value for a factor level
     * - Recognizes levels prefixed with "G:" as GSSG (supervisory) factors and uses a separate GSSG points map.
     */
    public int getPointsForFactorLevel(String factorNum, String level) {
        if (level == null) return 0;

        // GSSG (supervisory) levels are prefixed with "G:" by getDefaultFactorLevelsForGrade
        if (level.startsWith("G:")) {
            String lvl = level.substring(2); // e.g., "1-3" or "4-4"
            // Delegate to centralized GSSG mapping (covers 4A/4B and full ranges)
            return getGssgPointsForLevel(factorNum, lvl);
        }

        // Non-supervisory / FES mapping (existing map)
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

        String base = factorNum.replaceAll("[^0-9]", "");
        String lvl = level.trim();
        return FACTOR_POINTS.getOrDefault(base, Map.of()).getOrDefault(lvl, 0);
    }

    /**
     * GSSG-specific point mapping for Factors 1..6 with separate handling for 4A and 4B
     * Covers common valid level strings (e.g., "1-3", "4A-3", "4-3").
     */
    private int getGssgPointsForLevel(String factorNum, String level) {
        if (factorNum == null || level == null) return 0;
        try {
            level = level.trim();

            // Normalize inputs like "4-3" to allow 4A/4B lookups when appropriate
            // Handle explicit 4A / 4B first
            if (factorNum.equalsIgnoreCase("4A")) {
                switch (level) {
                    case "4A-1": case "4-1": return 25;
                    case "4A-2": case "4-2": return 50;
                    case "4A-3": case "4-3": return 75;
                    case "4A-4": case "4-4": return 100;
                    default:
                        if (level.contains("-")) {
                            String[] parts = level.split("-");
                            int lv = Integer.parseInt(parts[1].replaceAll("[^0-9]", ""));
                            switch (lv) { case 1: return 25; case 2: return 50; case 3: return 75; case 4: return 100; }
                        }
                        return 0;
                }
            }
            if (factorNum.equalsIgnoreCase("4B")) {
                switch (level) {
                    case "4B-1": case "4-1": return 30;
                    case "4B-2": case "4-2": return 75;
                    case "4B-3": case "4-3": return 100;
                    case "4B-4": case "4-4": return 125;
                    default:
                        if (level.contains("-")) {
                            String[] parts = level.split("-");
                            int lv = Integer.parseInt(parts[1].replaceAll("[^0-9]", ""));
                            switch (lv) { case 1: return 30; case 2: return 75; case 3: return 100; case 4: return 125; }
                        }
                        return 0;
                }
            }

            // Factor 1
            if (factorNum.equals("1")) {
                switch (level) {
                    case "1-1": return 175;
                    case "1-2": return 350;
                    case "1-3": return 550;
                    case "1-4": return 775;
                    case "1-5": return 900;
                    case "1-6": return 1050;
                    case "1-7": return 1250;
                    case "1-8": return 1550;
                    case "1-9": return 1850;
                }
            }

            // Factor 2
            if (factorNum.equals("2")) {
                switch (level) {
                    case "2-1": return 100;
                    case "2-2": return 250;
                    case "2-3": return 350;
                    case "2-4": return 450;
                    case "2-5": return 650;
                }
            }

            // Factor 3
            if (factorNum.equals("3")) {
                switch (level) {
                    case "3-1": return 250;
                    case "3-2": return 450;
                    case "3-3": return 775;
                    case "3-4": return 900;
                    case "3-5": return 1200;
                }
            }

            // Factor 5
            if (factorNum.equals("5")) {
                switch (level) {
                    case "5-1": return 75;
                    case "5-2": return 205;
                    case "5-3": return 340;
                    case "5-4": return 505;
                    case "5-5": return 650;
                    case "5-6": return 800;
                    case "5-7": return 930;
                    case "5-8": return 1030;
                }
            }

            // Factor 6 (Other Conditions)
            if (factorNum.equals("6")) {
                switch (level) {
                    case "6-1": return 310;
                    case "6-2": return 575;
                    case "6-3": return 975;
                    case "6-4": return 1120;
                    case "6-5": return 1225;
                    case "6-6": return 1325;
                }
            }

            // If caller provided "4-3" for factor 4 (no A/B) and factorNum is "4" try reasonable defaults:
            if (factorNum.equals("4") && level.matches("4-\\d+")) {
                int lv = Integer.parseInt(level.split("-")[1].replaceAll("[^0-9]", ""));
                // split proportionally between 4A and 4B if needed externally; here return sum of typical 4A+4B for that lv
                int a = 0, b = 0;
                switch (lv) {
                    case 1: a = 25; b = 30; break;
                    case 2: a = 50; b = 75; break;
                    case 3: a = 75; b = 100; break;
                    case 4: a = 100; b = 125; break;
                }
                return a + b;
            }

        } catch (Exception e) {
            System.err.println("getGssgPointsForLevel error: " + e.getMessage());
        }
        return 0;
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
        String jobSeries = cleanHeaderField(request.getJobSeries() != null ? request.getJobSeries() : "0343");
        String subJobSeries = cleanHeaderField(request.getSubJobSeries() != null ? request.getSubJobSeries() : "Management Analyst");
        String federalAgency = cleanHeaderField(request.getFederalAgency() != null ? request.getFederalAgency() : "Department of Homeland Security");
        String historicalData = request.getHistoricalData() != null ? request.getHistoricalData() : "Administrative duties";
        String gsGrade = cleanHeaderField(request.getGsGrade() != null ? request.getGsGrade() : "GS-13");
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

    private void validatePromptFormatting(String prompt) {
    System.out.println("[Validation] Checking prompt for proper factor formatting...");
    
    // Check if prompt contains the correct examples
    boolean has4AExample = prompt.contains("Factor 4A – PERSONAL CONTACTS");
    boolean has4BExample = prompt.contains("Factor 4B – PERSONAL CONTACTS");
    boolean hasBadExample = prompt.contains("Factor4A") || prompt.contains("Factor4B");
    
    if (!has4AExample) {
        System.err.println("⚠️ WARNING: Prompt missing correct Factor 4A example!");
    } else {
        System.out.println("✓ Prompt contains correct Factor 4A example");
    }
    
    if (!has4BExample) {
        System.err.println("⚠️ WARNING: Prompt missing correct Factor 4B example!");
    } else {
        System.out.println("✓ Prompt contains correct Factor 4B example");
    }
    
    if (hasBadExample) {
        System.err.println("⚠️ CRITICAL: Prompt contains BAD examples (Factor4A/Factor4B without space)!");
    }
    
    // Count how many times we show the correct format
    int correctExamples = 0;
    if (prompt.contains("Factor 4A – ")) correctExamples++;
    if (prompt.contains("Factor 4B – ")) correctExamples++;
    if (prompt.contains("Level 4-4, 100 Points")) correctExamples++;
    if (prompt.contains("Level 4-4, 125 Points")) correctExamples++;
    
    System.out.println("[Validation] Found " + correctExamples + " correct formatting examples in prompt");
    
    if (correctExamples < 3) {
        System.err.println("⚠️ WARNING: Prompt has insufficient correct examples (found " + correctExamples + ", need at least 3)!");
    } else {
        System.out.println("✓ Prompt has sufficient formatting examples");
    }
    
    // Check if the buildGssgFactorSectionInstruction formatting is present
    boolean hasSpacingChecklist = prompt.contains("SPACING CHECKLIST FOR EVERY FACTOR");
    if (hasSpacingChecklist) {
        System.out.println("✓ Prompt includes spacing checklist");
    } else {
        System.out.println("ℹ️ Note: Prompt does not include spacing checklist (may be non-supervisory position)");
    }
    
    System.out.println("[Validation] Prompt validation complete\n");
}

public void streamPD(PdRequest request, PrintWriter writer) {
    try {
        String prompt = buildPrompt(request);
        System.out.println("Prompt built, length: " + prompt.length() + " chars (~" + (prompt.length() / 4) + " tokens)");

        validatePromptFormatting(prompt);

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system",
            "You are an expert federal HR classification specialist. You MUST generate COMPLETE, DETAILED position descriptions with NO placeholders, NO outlines, NO incomplete sections. " +
            "Every section requires full prose paragraphs with specific details. Factor evaluations must have 3-5 full paragraphs EACH explaining the justification. " +
            "Continue generating until the ENTIRE document is complete - typically 8000-12000 words. DO NOT stop early or use shortcuts.\n\n" +
            "🚨 CRITICAL FORMATTING RULES - READ CAREFULLY:\n" +
            "1. When you write 'Factor 4A', you MUST include a SPACE between 'Factor' and '4A'\n" +
            "   ✅ CORRECT: Factor 4A\n" +
            "   ❌ WRONG: Factor4A\n" +
            "2. When you write 'Factor 4B', you MUST include a SPACE between 'Factor' and '4B'\n" +
            "   ✅ CORRECT: Factor 4B\n" +
            "   ❌ WRONG: Factor4B\n" +
            "3. When you write Level values, ALWAYS include spaces:\n" +
            "   ✅ CORRECT: Level 4-4, 100\n" +
            "   ❌ WRONG: Level4-4,100\n" +
            "4. Example of CORRECT format:\n" +
            "   **Factor 4A – PERSONAL CONTACTS (NATURE OF CONTACTS) Level 4-4, 100 Points**\n" +
            "5. Example of WRONG format (DO NOT USE):\n" +
            "   **Factor4A – PERSONAL CONTACTS (NATURE OF CONTACTS) Level4-4,100 Points**\n\n" +
            "You MUST generate COMPLETE, DETAILED position descriptions with NO placeholders. " +
            "Every section requires full prose paragraphs with specific details. " +
            "Factor evaluations must have 3-5 full paragraphs EACH explaining the justification. " +
            "Continue generating until the ENTIRE document is complete - typically 8000-12000 words. " +
            "DO NOT stop early or use shortcuts."
        ));
        messages.add(new Message("user", prompt));

        OpenAIRequest openaiRequest = new OpenAIRequest("gpt-4", messages, true);
        openaiRequest.setMaxTokens(16000);
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

        HttpResponse<InputStream> response = client.send(httpRequest,
            HttpResponse.BodyHandlers.ofInputStream());

        System.out.println("[streamPD] OpenAI response status: " + response.statusCode());

        if (response.statusCode() == 200) {
            StringBuilder fullContent = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6);
                        if (!"[DONE]".equals(data.trim())) {
                            try {
                                JsonNode jsonNode = objectMapper.readTree(data);
                                if (jsonNode.has("choices") && jsonNode.get("choices").size() > 0) {
                                    JsonNode choice = jsonNode.get("choices").get(0);
                                    if (choice.has("delta") && choice.get("delta").has("content")) {
                                        String content = choice.get("delta").get("content").asText();
                                        fullContent.append(content);
                                    }
                                }
                            } catch (Exception e) {
                                System.err.println("[streamPD] Error parsing JSON chunk at line " + lineCount + ": " + e.getMessage());
                            }
                        }
                    }
                }
                System.out.println("[streamPD] Streamed " + lineCount + " total lines, accumulated " + fullContent.length() + " chars");
            }

            // === GET RAW OUTPUT ===
            String rawOutput = fullContent.toString();
            
            // === SAVE RAW OUTPUT TO FILE FOR INSPECTION ===
            try {
                java.nio.file.Files.write(
                    java.nio.file.Paths.get("/tmp/pd_raw_output.txt"),
                    rawOutput.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                );
                System.out.println("[streamPD] Saved raw output to /tmp/pd_raw_output.txt");
            } catch (Exception e) {
                System.err.println("[streamPD] Could not save raw output: " + e.getMessage());
            }

            // === LOG FACTOR HEADERS IN RAW OUTPUT ===
            System.err.println("\n========== FACTOR HEADERS IN RAW OUTPUT ==========");
            String[] lines = rawOutput.split("\n");
            int factorLineCount = 0;
            for (String l : lines) {
                if (l.toLowerCase().contains("factor") && l.toLowerCase().contains("level") && l.toLowerCase().contains("points")) {
                    factorLineCount++;
                    System.err.println("[Factor Line " + factorLineCount + "]: " + l);
                }
            }
            System.err.println("Total factor lines found: " + factorLineCount);
            System.err.println("========== END FACTOR HEADERS ==========\n");
            System.err.flush();

            System.out.println("\n========== STARTING FORMATTING PIPELINE ==========");
            
            // ============================================================
            // === STEP 1: IMMEDIATE EMERGENCY FIXES ===
            // ============================================================
            System.out.println("Step 1: Emergency inline fixes for common spacing issues...");
            
            int before4A = countOccurrences(rawOutput, "Factor4A");
            int before4B = countOccurrences(rawOutput, "Factor4B");
            int beforeLevel100 = countOccurrences(rawOutput, "Level4-4,100");
            int beforeLevel125 = countOccurrences(rawOutput, "Level4-4,125");
            
            // Fix Factor spacing
            rawOutput = rawOutput.replaceAll("Factor4A", "Factor 4A");
            rawOutput = rawOutput.replaceAll("Factor4B", "Factor 4B");
            rawOutput = rawOutput.replaceAll("Factor1([^0-9])", "Factor 1$1");
            rawOutput = rawOutput.replaceAll("Factor2([^0-9])", "Factor 2$1");
            rawOutput = rawOutput.replaceAll("Factor3([^0-9])", "Factor 3$1");
            rawOutput = rawOutput.replaceAll("Factor5([^0-9])", "Factor 5$1");
            rawOutput = rawOutput.replaceAll("Factor6([^0-9])", "Factor 6$1");
            
            // Fix Level spacing
            rawOutput = rawOutput.replaceAll("Level4-4,100", "Level 4-4, 100");
            rawOutput = rawOutput.replaceAll("Level4-4,125", "Level 4-4, 125");
            rawOutput = rawOutput.replaceAll("Level([0-9])", "Level $1");
            
            // Remove any G: prefixes
            rawOutput = rawOutput.replaceAll("G:", "");
            
            int after4A = countOccurrences(rawOutput, "Factor4A");
            int after4B = countOccurrences(rawOutput, "Factor4B");
            int afterLevel100 = countOccurrences(rawOutput, "Level4-4,100");
            int afterLevel125 = countOccurrences(rawOutput, "Level4-4,125");
            
            System.out.println("  Fixed Factor4A: " + (before4A - after4A) + " instances (remaining: " + after4A + ")");
            System.out.println("  Fixed Factor4B: " + (before4B - after4B) + " instances (remaining: " + after4B + ")");
            System.out.println("  Fixed Level4-4,100: " + (beforeLevel100 - afterLevel100) + " instances (remaining: " + afterLevel100 + ")");
            System.out.println("  Fixed Level4-4,125: " + (beforeLevel125 - afterLevel125) + " instances (remaining: " + afterLevel125 + ")");
            
            // ============================================================
            // === STEP 2: DETERMINISTIC HEADER REPLACEMENT ===
            // ============================================================
            System.out.println("\nStep 2: Deterministic Factor 4A/4B header replacement...");
            rawOutput = replaceFactor4A4BHeaders(rawOutput, request);
            
            // ============================================================
            // === STEP 3: GENERAL FORMATTING CLEANUP ===
            // ============================================================
            System.out.println("\nStep 3: General formatting cleanup...");
            String formattedPD = fixPDFormatting(rawOutput);
            System.out.println("  fixPDFormatting completed, output length: " + formattedPD.length());

            // ============================================================
            // === STEP 4: FINAL VERIFICATION AND SAFETY PASS ===
            // ============================================================
            System.out.println("\nStep 4: Final verification...");
            
            int finalBad4A = countOccurrences(formattedPD, "Factor4A");
            int finalBad4B = countOccurrences(formattedPD, "Factor4B");
            int finalBadLevel100 = countOccurrences(formattedPD, "Level4-4,100");
            int finalBadLevel125 = countOccurrences(formattedPD, "Level4-4,125");
            
            if (finalBad4A > 0 || finalBad4B > 0 || finalBadLevel100 > 0 || finalBadLevel125 > 0) {
                System.err.println("⚠️ CRITICAL: Bad spacing detected after all fixes!");
                System.err.println("  Factor4A remaining: " + finalBad4A);
                System.err.println("  Factor4B remaining: " + finalBad4B);
                System.err.println("  Level4-4,100 remaining: " + finalBadLevel100);
                System.err.println("  Level4-4,125 remaining: " + finalBadLevel125);
                
                // One more emergency pass
                System.out.println("  Applying final emergency fixes...");
                formattedPD = formattedPD.replaceAll("Factor4A", "Factor 4A");
                formattedPD = formattedPD.replaceAll("Factor4B", "Factor 4B");
                formattedPD = formattedPD.replaceAll("Level4-4,100", "Level 4-4, 100");
                formattedPD = formattedPD.replaceAll("Level4-4,125", "Level 4-4, 125");
                
                // Re-check
                int final2Bad4A = countOccurrences(formattedPD, "Factor4A");
                int final2Bad4B = countOccurrences(formattedPD, "Factor4B");
                if (final2Bad4A == 0 && final2Bad4B == 0) {
                    System.out.println("  ✓ Emergency fixes successful");
                } else {
                    System.err.println("  ⚠️ Some issues persist after emergency fixes");
                }
            } else {
                System.out.println("  ✓ Factor 4A/4B spacing verified correct");
            }
            
            // Count final occurrences of correct format
            int correct4A = countOccurrences(formattedPD, "Factor 4A");
            int correct4B = countOccurrences(formattedPD, "Factor 4B");
            System.out.println("  Found 'Factor 4A' (correct): " + correct4A + " times");
            System.out.println("  Found 'Factor 4B' (correct): " + correct4B + " times");
            
            System.out.println("========== FORMATTING COMPLETE ==========\n");

            // === SAVE FORMATTED OUTPUT TO FILE FOR INSPECTION ===
            try {
                java.nio.file.Files.write(
                    java.nio.file.Paths.get("/tmp/pd_formatted_output.txt"),
                    formattedPD.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                );
                System.out.println("[streamPD] Saved formatted output to /tmp/pd_formatted_output.txt");
            } catch (Exception e) {
                System.err.println("[streamPD] Could not save formatted output: " + e.getMessage());
            }

            // === LOG FACTOR HEADERS IN FORMATTED OUTPUT ===
            System.err.println("\n========== FACTOR HEADERS IN FORMATTED OUTPUT ==========");
            String[] formattedLines = formattedPD.split("\n");
            int formattedFactorLineCount = 0;
            for (String l : formattedLines) {
                if (l.toLowerCase().contains("factor") && l.toLowerCase().contains("level") && l.toLowerCase().contains("points")) {
                    formattedFactorLineCount++;
                    System.err.println("[Factor Line " + formattedFactorLineCount + "]: " + l);
                }
            }
            System.err.println("Total formatted factor lines found: " + formattedFactorLineCount);
            System.err.println("========== END FORMATTED FACTOR HEADERS ==========\n");
            System.err.flush();

            // Send to client
            String jsonLine = "{\"response\":\"" + escapeJson(formattedPD) + "\"}";
            writer.println(jsonLine);
            writer.flush();
            
        } else {
            String errorMsg = "{\"response\":\"Error: OpenAI API returned status " + response.statusCode() + "\"}";
            writer.println(errorMsg);
            writer.flush();
        }
    } catch (Exception e) {
        System.err.println("[streamPD] Exception: " + e.getMessage());
        e.printStackTrace(System.err);
        String errorMsg = "{\"response\":\"Error: " + escapeJson(e.getMessage()) + "\"}";
        writer.println(errorMsg);
    }
}

private String replaceFactor4A4BHeaders(String aiOutput, PdRequest request) {
    if (aiOutput == null || request == null) return aiOutput;
    
    Map<String, String> factorLevels = request.getFactorLevels();
    Map<String, Integer> factorPoints = request.getFactorPoints();
    
    if (factorLevels == null || factorPoints == null) {
        return aiOutput;
    }
    
    // Get correct 4A values
    String lvlA = factorLevels.getOrDefault("4A", "4-4");
    lvlA = lvlA.startsWith("G:") ? lvlA.substring(2) : lvlA;
    if (!lvlA.contains("-")) {
        lvlA = "4-" + lvlA;
    }
    Integer ptsA = factorPoints.getOrDefault("4A", 100);
    
    // Get correct 4B values
    String lvlB = factorLevels.getOrDefault("4B", "4-4");
    lvlB = lvlB.startsWith("G:") ? lvlB.substring(2) : lvlB;
    if (!lvlB.contains("-")) {
        lvlB = "4-" + lvlB;
    }
    Integer ptsB = factorPoints.getOrDefault("4B", 125);
    
    // Build the EXACT correct headers
    String correct4AHeader = String.format("**Factor 4A – PERSONAL CONTACTS (NATURE OF CONTACTS) Level %s, %d Points**",
                                           lvlA, ptsA);
    
    String correct4BHeader = String.format("**Factor 4B – PERSONAL CONTACTS (PURPOSE OF CONTACTS) Level %s, %d Points**",
                                           lvlB, ptsB);
    
    System.out.println("[replaceFactor4A4BHeaders] Target 4A: " + correct4AHeader);
    System.out.println("[replaceFactor4A4BHeaders] Target 4B: " + correct4BHeader);
    
    // PASS 1: Replace complete Factor 4A lines (any variation)
    // Matches: **Factor4A – ..., Factor 4A –, **Factor 4A –, etc.
    aiOutput = aiOutput.replaceAll(
        "(?i)\\*{0,2}\\s*Factor\\s*4\\s*A\\s*[-–—]\\s*PERSONAL CONTACTS.*?Points\\s*\\*{0,2}",
        correct4AHeader
    );
    
    // PASS 2: Replace complete Factor 4B lines
    aiOutput = aiOutput.replaceAll(
        "(?i)\\*{0,2}\\s*Factor\\s*4\\s*B\\s*[-–—]\\s*PERSONAL CONTACTS.*?Points\\s*\\*{0,2}",
        correct4BHeader
    );
    
    // PASS 3: Fix any remaining "Factor4A" or "Factor4B" without space
    aiOutput = aiOutput.replaceAll("(?i)Factor4A", "Factor 4A");
    aiOutput = aiOutput.replaceAll("(?i)Factor4B", "Factor 4B");
    
    // PASS 4: Fix Level spacing issues
    aiOutput = aiOutput.replaceAll("Level4-4,100", "Level 4-4, 100");
    aiOutput = aiOutput.replaceAll("Level4-4,125", "Level 4-4, 125");
    aiOutput = aiOutput.replaceAll("Level4-", "Level 4-");
    
    // PASS 5: Line-by-line scan for any missed headers
    String[] lines = aiOutput.split("\n", -1);
    StringBuilder result = new StringBuilder();
    int replaced4A = 0;
    int replaced4B = 0;
    
    for (int i = 0; i < lines.length; i++) {
        String line = lines[i];
        String cleanLine = line.replaceAll("\\*", "").trim().toLowerCase();
        
        // Check if this line mentions Factor 4A and PERSONAL CONTACTS
        if (cleanLine.contains("factor") && 
            cleanLine.matches(".*factor\\s*4\\s*a.*") && 
            cleanLine.contains("personal")) {
            
            System.out.println("[replaceFactor4A4BHeaders] Replacing 4A: " + line);
            result.append(correct4AHeader);
            replaced4A++;
        }
        // Check if this line mentions Factor 4B and PERSONAL CONTACTS
        else if (cleanLine.contains("factor") && 
                 cleanLine.matches(".*factor\\s*4\\s*b.*") && 
                 cleanLine.contains("personal")) {
            
            System.out.println("[replaceFactor4A4BHeaders] Replacing 4B: " + line);
            result.append(correct4BHeader);
            replaced4B++;
        }
        else {
            result.append(line);
        }
        
        if (i < lines.length - 1) {
            result.append("\n");
        }
    }
    
    System.out.println("[replaceFactor4A4BHeaders] Replaced " + replaced4A + " Factor 4A headers");
    System.out.println("[replaceFactor4A4BHeaders] Replaced " + replaced4B + " Factor 4B headers");
    
    if (replaced4A == 0) {
        System.err.println("⚠️ WARNING: No Factor 4A headers were replaced!");
    }
    if (replaced4B == 0) {
        System.err.println("⚠️ WARNING: No Factor 4B headers were replaced!");
    }
    
    return result.toString();
}

private int countOccurrences(String text, String pattern) {
    if (text == null || pattern == null) return 0;
    try {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(text);
        int count = 0;
        while (m.find()) count++;
        return count;
    } catch (Exception e) {
        return 0;
    }
}

    /**
     * Call OpenAI API for non-streaming requests
     */
    public String callOpenAI(String prompt, String historicalData) throws Exception {
        List<Message> messages = new ArrayList<>();
        // Add historical data as context for the AI, but not in the output
        String systemPrompt = "You are an expert federal HR classification specialist. " +
            "Use the following background information to inform your response, but do not display it directly: " +
            historicalData +
            " Generate concise, complete content with full paragraphs. NO placeholders or shortcuts.";
        messages.add(new Message("system", systemPrompt));
        messages.add(new Message("user", prompt));

        // Estimate prompt tokens
        int promptTokens = prompt.length() / 4;
        int maxModelTokens = 8192;
        int safeOutputTokens = Math.min(1200, maxModelTokens - promptTokens - 200); // Lower output tokens

        System.out.println("[OpenAI] Prompt length: " + prompt.length() + " chars (~" + promptTokens + " tokens)");
        System.out.println("[OpenAI] Token allocation: Input=" + promptTokens + ", Output=" + safeOutputTokens);

        OpenAIRequest openaiRequest = new OpenAIRequest("gpt-4", messages, false);
        openaiRequest.setMaxTokens(safeOutputTokens); // Lower value for speed
        openaiRequest.setTemperature(0.3);

        ObjectMapper objectMapper = new ObjectMapper();
        String requestBody = objectMapper.writeValueAsString(openaiRequest);

        System.out.println("[OpenAI] Request JSON: " + requestBody);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
            .header("Authorization", "Bearer " + openaiApiKey)
            .header("Content-Type", "application/json")
            .timeout(java.time.Duration.ofSeconds(60)) // Increase from 30s to 60s or more
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        System.out.println("[OpenAI] Response status: " + response.statusCode());
        System.out.println("[OpenAI] Response body: " + response.body());

        if (response.statusCode() != 200) {
            throw new Exception("OpenAI API returned status: " + response.statusCode() + " Body: " + response.body());
        }

        JsonNode responseJson = objectMapper.readTree(response.body());
        if (responseJson.has("choices") && responseJson.get("choices").size() > 0) {
            JsonNode choice = responseJson.get("choices").get(0);
            if (choice.has("message") && choice.get("message").has("content")) {
                String content = choice.get("message").get("content").asText().trim();
                System.out.println("[OpenAI] Parsed content: " + content.substring(0, Math.min(500, content.length())));
                return content;
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
                .replace("\"", "\\\"");
    }
    
public String fixPDFormatting(String pdText) {
    if (pdText == null || pdText.trim().isEmpty()) return "";

    String text = pdText.replaceAll("\\r\\n?", "\n");
    text = text.replace("\u00A0", " ").replace("\u200B", " ");
    text = text.replaceAll("(?m)^\\s*\\*+\\s*$\\n?", "");
    text = text.replaceAll("(?i)\\bG:\\s*", "");
    
    // === CRITICAL: Fix Factor 4A/4B FIRST before other factor processing ===
    System.out.println("[fixPDFormatting] Starting Factor 4A/4B fixes...");
    
    // Pass 1: Fix "Factor4A" and "Factor4B" (no space)
    int before4A = countOccurrences(text, "(?i)Factor4A");
    int before4B = countOccurrences(text, "(?i)Factor4B");
    
    text = text.replaceAll("(?i)Factor4A", "Factor 4A");
    text = text.replaceAll("(?i)Factor4B", "Factor 4B");
    
    int after4A = countOccurrences(text, "(?i)Factor4A");
    int after4B = countOccurrences(text, "(?i)Factor4B");
    
    System.out.println("[fixPDFormatting] Fixed Factor4A: " + (before4A - after4A) + " instances");
    System.out.println("[fixPDFormatting] Fixed Factor4B: " + (before4B - after4B) + " instances");
    
    // Pass 2: Fix Level spacing for 4A/4B specifically
    text = text.replaceAll("Level4-4,100", "Level 4-4, 100");
    text = text.replaceAll("Level4-4,125", "Level 4-4, 125");
    text = text.replaceAll("Level4-4, 100", "Level 4-4, 100"); // ensure space after comma
    text = text.replaceAll("Level4-4, 125", "Level 4-4, 125");
    
    // Pass 3: Generic Level fixes (for all factors)
    text = text.replaceAll("Level([0-9])", "Level $1");
    text = text.replaceAll("Level\\s+([0-9]+)-([0-9]+),([0-9]+)", "Level $1-$2, $3");
    
    // === NOW apply general factor formatting ===
    text = text.replaceAll("(?i)\\bFactor[\\s\\u00A0\\u200B]*([0-9]{1,2}[A-Za-z]?)\\b", "Factor $1");
    text = text.replaceAll("(?m)(?i)(Factor\\s+[0-9]{1,2}[A-Za-z]?)\\s*[-–—:\\u2014\\u2013\\s]+\\s*", "$1 – ");
    text = text.replaceAll("(?i)Level\\s*([0-9]+)\\s*[-–—]?\\s*([0-9]+)\\s*,?\\s*([0-9]{1,6})\\s*Points", "Level $1-$2, $3 Points");
    
    // Canonicalize section headers
    String[] sectionHeaders = {
        "HEADER","INTRODUCTION","MAJOR DUTIES","FACTOR EVALUATION COMPLETE ANALYSIS",
        "FACTOR EVALUATION","EVALUATION SUMMARY","CONDITIONS OF EMPLOYMENT",
        "TITLE AND SERIES DETERMINATION","FAIR LABOR STANDARDS ACT DETERMINATION"
    };
    for (String h : sectionHeaders) {
        text = text.replaceAll("(?i)\\*{0,2}\\s*" + java.util.regex.Pattern.quote(h) + "\\s*:?\\s*\\*{0,2}", 
                              "\n\n**" + h + "**\n\n");
    }

    // Bold and space factor headers
    text = text.replaceAll("(?m)^(\\s*)(Factor\\s+[0-9]{1,2}[A-Z]?\\s+–\\s+.*?Level\\s+[0-9]+-[0-9]+,\\s*\\d+\\s*Points)\\s*$", 
                          "$1**$2**");
    text = text.replaceAll("(?m)(\\*\\*Factor\\s+[0-9]{1,2}[A-Z]?\\s+–\\s+.*?Points\\*\\*)\\n(?!\\n)", "$1\n\n");
    
    // === FINAL SAFETY PASS: One more check for 4A/4B ===
    text = text.replaceAll("(?i)Factor4A", "Factor 4A");
    text = text.replaceAll("(?i)Factor4B", "Factor 4B");
    text = text.replaceAll("Level4-4,", "Level 4-4,");
    
    // Clean up whitespace
    text = text.replaceAll("\\n{4,}", "\n\n\n");
    text = text.replaceAll("[ \\t]+\\n", "\n");
    text = text.replaceAll("\\n[ \\t]+", "\n");
    text = text.replaceAll("[ ]{2,}", " ");
    text = text.trim();

    // Final diagnostic
    if (text.contains("Factor4A") || text.contains("Factor4B") || text.contains("Level4-4,")) {
        System.err.println("⚠️ CRITICAL: Residual spacing issues remain in output!");
        // Log where they appear
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].toLowerCase();
            if (line.contains("factor4a") || line.contains("factor4b") || line.contains("level4-4")) {
                System.err.println("  Line " + (i+1) + ": " + lines[i]);
            }
        }
    } else {
        System.out.println("✓ Factor 4A/4B spacing verified correct");
    }

    return text;
}

public String buildLockedPrompt(PdRequest request) {
    // Use locked values from request
    String jobSeries = request.getJobSeries();
    String positionTitle = request.getSubJobSeries();
    String agency = request.getFederalAgency();
    String organization = request.getSubOrganization();
    String lowestOrg = request.getLowestOrg();
    String supervisoryLevel = request.getSupervisoryLevel();
    String gsGrade = request.getGsGrade();
    String gradeRange = request.getGradeRange();
    Map<String, String> factorLevels = request.getFactorLevels();
    Map<String, Integer> factorPoints = request.getFactorPoints();
    int totalPoints = request.getTotalPoints() != null ? request.getTotalPoints() : 0;

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

    StringBuilder factorEval = new StringBuilder();
    for (int i = 1; i <= 9; i++) {
        String factorNum = String.valueOf(i);
        String level = factorLevels.get(factorNum);
        Integer points = factorPoints.get(factorNum);
    // Header on one line, blank line, then description on next line
        factorEval.append(String.format(
        "Factor %d – %s Level %s, %d Points\n\n[Write 2-4 sentences explaining this factor level.]\n\n",
            i, factorNames[i-1], level, points
    ));
}

    return String.format("""
You are an expert federal HR classification specialist. 
Generate a complete, professional federal position description using the EXACT factor levels and points provided below. 
DO NOT change any factor levels or points. Copy them exactly into the Factor Evaluation section.

**HEADER**

Job Series: GS-%s

Position Title: %s

Agency: %s

Organization: %s

Lowest Organization: %s

Supervisory Level: %s

**INTRODUCTION**

[Write 3-4 sentences describing the role, mission, and main responsibilities.]

**MAJOR DUTIES**

1. [Duty Title] (XX%%): [2-3 sentences of detail.]
2. [Duty Title] (XX%%): [2-3 sentences of detail.]
3. [Duty Title] (XX%%): [2-3 sentences of detail.]
4. [Duty Title] (XX%%): [2-3 sentences of detail.]
5. [Duty Title] (XX%%): [2-3 sentences of detail.]

**FACTOR EVALUATION**

%s

**EVALUATION SUMMARY**

Total Points: %d

Final Grade: %s

Grade Range: %s

**CONDITIONS OF EMPLOYMENT**

[3-4 sentences covering certifications, clearance, training, travel, and other requirements.]

**TITLE AND SERIES DETERMINATION**

[1 paragraph explaining the rationale for series and title.]

**FAIR LABOR STANDARDS ACT DETERMINATION**

[1 paragraph justifying exempt/non-exempt status based on duties.]
""",
jobSeries, positionTitle, agency, organization, lowestOrg, supervisoryLevel,
factorEval.toString(),
totalPoints, gsGrade, gradeRange
);
}
}