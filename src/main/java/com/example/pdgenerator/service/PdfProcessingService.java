package com.example.pdgenerator.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Service for processing and caching PDF content with strict token limits
 */
@Service
public class PdfProcessingService {

    private final Map<String, String> pdfCache = new HashMap<>();
    private final Map<String, String> summarizedCache = new HashMap<>();

    /**
     * Load and cache PDFs at application startup
     */
    @PostConstruct
    public void initializePdfCache() {
        System.out.println("Initializing PDF cache...");
        
        // Load and cache with strict limits
        cachePdfWithLimit("opm_handbook", "/static/pdfs/occupationalhandbook.pdf", 500);
        cachePdfWithLimit("factor_guide", "/static/pdfs/gsadmn.pdf", 400);
        cachePdfWithLimit("grade_guide", "/static/pdfs/gssg.pdf", 300);
        cachePdfWithLimit("cs_guide", "/static/pdfs/interpretive-guidance-for-cybersecurity-positions.pdf", 200);
        
        System.out.println("PDF cache initialized with " + pdfCache.size() + " documents");
    }

    /**
     * Cache PDF content with token limit
     */
    private void cachePdfWithLimit(String key, String resourcePath, int maxTokens) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                System.out.println("PDF not found: " + resourcePath);
                return;
            }

            byte[] pdfBytes = is.readAllBytes();
            PDDocument document = Loader.loadPDF(pdfBytes);
            PDFTextStripper stripper = new PDFTextStripper();
            
            // Only extract first 2-3 pages
            int totalPages = document.getNumberOfPages();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(2, totalPages));
            
            String fullText = stripper.getText(document);
            document.close();
            
            // Truncate to token limit
            String truncated = truncateToTokenLimit(fullText, maxTokens);
            pdfCache.put(key, truncated);
            
            System.out.println("Cached " + key + ": " + truncated.length() + " chars (~" + 
                             (truncated.length() / 4) + " tokens)");
            
        } catch (Exception e) {
            System.err.println("Failed to cache PDF " + resourcePath + ": " + e.getMessage());
        }
    }

    /**
     * Get cached PDF content by key
     */
    public String getCachedPdf(String key) {
        return pdfCache.getOrDefault(key, "");
    }

    /**
     * Get summarized OPM standards for factor evaluation
     */
    public String getFactorEvaluationSummary() {
        if (summarizedCache.containsKey("factor_summary")) {
            return summarizedCache.get("factor_summary");
        }

        String summary = """
            OPM FACTOR EVALUATION STANDARDS (Summary):
            
            Factor 1 - Knowledge: 1-1 (50pts) to 1-9 (1850pts)
            - Professional knowledge depth and complexity
            - Educational requirements and experience
            
            Factor 2 - Supervisory Controls: 2-1 (25pts) to 2-5 (650pts)
            - Independence level and decision authority
            - Nature of supervision received
            
            Factor 3 - Guidelines: 3-1 (25pts) to 3-5 (650pts)
            - Guidance availability and interpretation needed
            - Judgment required in application
            
            Factor 4 - Complexity: 4-1 (25pts) to 4-6 (450pts)
            - Problem difficulty and variety
            - Analytical processes required
            
            Factor 5 - Scope and Effect: 5-1 (25pts) to 5-6 (450pts)
            - Work purpose and organizational impact
            - External effects and consequences
            
            Factors 6-9: Contacts, Purpose, Physical, Environment
            Two-grade intervals only: GS-5, 7, 9, 11, 12, 13, 14, 15
            """;
        
        summarizedCache.put("factor_summary", summary);
        return summary;
    }

    /**
     * Get series guide summary (for job series standards)
     */
    public String getSeriesGuideSummary() {
        if (summarizedCache.containsKey("series_guide_summary")) {
            return summarizedCache.get("series_guide_summary");
        }

        // Try to get from cached OPM handbook
        String cachedHandbook = pdfCache.getOrDefault("opm_handbook", "");
        
        if (!cachedHandbook.isEmpty()) {
            // Extract series-related content if available
            String seriesContent = extractRelevantSections(cachedHandbook, 
                "series", "occupation", "position", "classification", "duties");
            
            if (seriesContent.length() > 100) {
                String summary = truncateToTokenLimit(seriesContent, 300);
                summarizedCache.put("series_guide_summary", summary);
                return summary;
            }
        }

        // Default series guidance
        String defaultSummary = """
            JOB SERIES CLASSIFICATION GUIDANCE:
            
            Position classification based on:
            - Primary purpose and duties (what work is done)
            - Level of difficulty and responsibility (how work is done)
            - Qualifications required (knowledge and skills needed)
            
            Professional series typically require:
            - Specialized education or experience
            - Application of professional principles and theories
            - Independent judgment in problem-solving
            
            Administrative series focus on:
            - Program management and coordination
            - Policy analysis and implementation
            - Stakeholder engagement and communication
            
            Technical series emphasize:
            - Practical application of procedures
            - Specialized technical knowledge
            - Support to professional staff
            """;
        
        summarizedCache.put("series_guide_summary", defaultSummary);
        return defaultSummary;
    }

    /**
     * Get series-specific guidance for a job series
     */
    public String getSeriesGuidance(String seriesCode) {
        String cacheKey = "series_" + seriesCode;
        
        if (summarizedCache.containsKey(cacheKey)) {
            return summarizedCache.get(cacheKey);
        }

        // Try to load series-specific PDF
        String resourcePath = "/static/pdfs/series-" + seriesCode + ".pdf";
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is != null) {
                byte[] pdfBytes = is.readAllBytes();
                PDDocument document = Loader.loadPDF(pdfBytes);
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(1);
                stripper.setEndPage(1); // Only first page
                
                String text = stripper.getText(document);
                document.close();
                
                String truncated = truncateToTokenLimit(text, 200);
                summarizedCache.put(cacheKey, truncated);
                return truncated;
            }
        } catch (Exception e) {
            System.out.println("No series-specific PDF for " + seriesCode);
        }

        // Default guidance
        String defaultGuidance = "Apply standard OPM professional series requirements for GS-" + seriesCode;
        summarizedCache.put(cacheKey, defaultGuidance);
        return defaultGuidance;
    }

    /**
     * Extract relevant sections from PDF based on keywords
     */
    public String extractRelevantSections(String fullText, String... keywords) {
        String[] paragraphs = fullText.split("\n\n");
        StringBuilder relevant = new StringBuilder();
        
        for (String paragraph : paragraphs) {
            String lower = paragraph.toLowerCase();
            for (String keyword : keywords) {
                if (lower.contains(keyword.toLowerCase())) {
                    relevant.append(paragraph).append("\n\n");
                    break;
                }
            }
        }
        
        return relevant.length() > 0 ? relevant.toString() : fullText;
    }

    /**
     * Truncate text to approximate token limit
     */
    private String truncateToTokenLimit(String text, int maxTokens) {
        int maxChars = maxTokens * 4; // Rough estimate: 1 token = 4 chars
        
        if (text.length() <= maxChars) {
            return text;
        }
        
        String truncated = text.substring(0, maxChars);
        
        // Try to cut at sentence boundary
        int lastPeriod = truncated.lastIndexOf(". ");
        if (lastPeriod > maxChars * 0.7) {
            truncated = truncated.substring(0, lastPeriod + 1);
        }
        
        return truncated + "\n[Content truncated for token limit]";
    }

    /**
     * Extract text from uploaded PDF
     */
    public String extractTextFromPdf(MultipartFile file) throws Exception {
        byte[] pdfBytes = file.getBytes();
        PDDocument document = Loader.loadPDF(pdfBytes);
        PDFTextStripper stripper = new PDFTextStripper();
        String text = stripper.getText(document);
        document.close();
        return text;
    }

    /**
     * Extract text from PDF path
     */
    public String extractTextFromPdfPath(String pdfPath) throws Exception {
        try (InputStream is = getClass().getResourceAsStream(pdfPath)) {
            if (is == null) {
                throw new Exception("PDF not found: " + pdfPath);
            }
            
            byte[] pdfBytes = is.readAllBytes();
            PDDocument document = Loader.loadPDF(pdfBytes);
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            document.close();
            return text;
        }
    }

    /**
     * Analyze PDF content for classification
     */
    public Map<String, Object> analyzePdfContent(String pdfText, String targetGrade) {
        Map<String, Object> analysis = new HashMap<>();
        
        // Extract key information
        String[] keywords = {"factor", "grade", "level", "points", "knowledge", "supervision"};
        String relevantContent = extractRelevantSections(pdfText, keywords);
        
        analysis.put("relevantContent", truncateToTokenLimit(relevantContent, 300));
        analysis.put("targetGrade", targetGrade);
        analysis.put("contentLength", pdfText.length());
        
        return analysis;
    }

    /**
     * Get total cached content size
     */
    public int getTotalCacheSize() {
        return pdfCache.values().stream()
            .mapToInt(String::length)
            .sum();
    }

    /**
     * Clear cache (for testing/reloading)
     */
    public void clearCache() {
        pdfCache.clear();
        summarizedCache.clear();
        System.out.println("PDF cache cleared");
    }
}