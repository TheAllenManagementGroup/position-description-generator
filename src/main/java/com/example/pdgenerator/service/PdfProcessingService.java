package com.example.pdgenerator.service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PdfProcessingService {

    /**
     * Extracts text from a PDF file uploaded via MultipartFile.
     */
    public String extractTextFromPdf(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            byte[] pdfBytes = inputStream.readAllBytes();
            try (PDDocument document = Loader.loadPDF(pdfBytes)) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(1);
                stripper.setEndPage(document.getNumberOfPages());
                return stripper.getText(document);
            }
        }
    }

    /**
     * Extracts text from a PDF located on the filesystem.
     */
    public String extractTextFromPdfPath(String filePath) throws IOException {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(new File(filePath))) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * Analyzes the text extracted from a PDF for key grade indicators and complexity.
     */
    public Map<String, Object> analyzePdfContent(String pdfText, String targetGrade) throws Exception {
        Map<String, Object> analysis = new HashMap<>();
        analysis.put("extractedText", pdfText);
        analysis.put("keyTerms", extractKeyTerms(pdfText));
        analysis.put("complexityIndicators", identifyComplexityIndicators(pdfText));
        return analysis;
    }

    /**
     * Extracts keywords that suggest GS grade levels.
     */
    private List<String> extractKeyTerms(String text) {
        List<String> keyTerms = new ArrayList<>();
        String lowerText = text.toLowerCase();

        String[] gs14Terms = {"subject matter expert", "recognized authority", "policy development", "strategic planning"};
        String[] gs13Terms = {"program manager", "complex analysis", "organizational impact", "senior professional"};
        String[] gs12Terms = {"supervisory", "team leader", "program responsibility", "advanced work"};

        for (String term : gs14Terms) {
            if (lowerText.contains(term)) keyTerms.add("GS-14: " + term);
        }
        for (String term : gs13Terms) {
            if (lowerText.contains(term)) keyTerms.add("GS-13: " + term);
        }
        for (String term : gs12Terms) {
            if (lowerText.contains(term)) keyTerms.add("GS-12: " + term);
        }

        return keyTerms;
    }

    /**
     * Identifies common complexity indicators in the PDF text.
     */
    private Map<String, Integer> identifyComplexityIndicators(String text) {
        Map<String, Integer> indicators = new HashMap<>();
        String lowerText = text.toLowerCase();

        indicators.put("independent", countOccurrences(lowerText, "independent"));
        indicators.put("complex", countOccurrences(lowerText, "complex"));
        indicators.put("analysis", countOccurrences(lowerText, "analysis"));
        indicators.put("expert", countOccurrences(lowerText, "expert"));
        indicators.put("policy", countOccurrences(lowerText, "policy"));

        return indicators;
    }

    /**
     * Counts occurrences of a specific word within text.
     */
    private int countOccurrences(String text, String word) {
        return text.split("\\b" + word + "\\b").length - 1;
    }
}
