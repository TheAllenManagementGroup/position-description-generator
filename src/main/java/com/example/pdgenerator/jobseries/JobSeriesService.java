package com.example.pdgenerator.jobseries;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class JobSeriesService {

    @Value("${openai.api.key}")
    private String openaiApiKey;

    private final Map<String, JobSeriesData> jobSeriesData = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Simplified data structure
    public static class JobSeriesData {
        private String code;
        private String title;
        private List<String> keywords;
        private List<String> positions;
        private int totalJobs;
        
        // constructors, getters, setters
        public JobSeriesData() {}
        
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public List<String> getKeywords() { return keywords; }
        public void setKeywords(List<String> keywords) { this.keywords = keywords; }
        public List<String> getPositions() { return positions; }
        public void setPositions(List<String> positions) { this.positions = positions; }
        public int getTotalJobs() { return totalJobs; }
        public void setTotalJobs(int totalJobs) { this.totalJobs = totalJobs; }
    }

    // Inner class for matching results - MOVED INSIDE THE CLASS
    private static class SeriesMatch {
        final JobSeriesData series;
        final double score;
        
        SeriesMatch(JobSeriesData series, double score) {
            this.series = series;
            this.score = score;
        }
    }

    // JobSeriesBuilder class - MOVED INSIDE THE CLASS
    private static class JobSeriesBuilder {
    private String code;
    private String title;
    private Set<String> keywords = new HashSet<>();
    private Map<String, Integer> positionCounts = new HashMap<>();
    private int totalJobs = 0;
    
    JobSeriesBuilder(String code) {
        this.code = code;
    }
    
    void addJob(JsonNode job) {
    totalJobs++;
    
    System.out.println("Processing job for series " + code + ". Total jobs so far: " + totalJobs);
    
    if (title == null) {
        title = extractTitle(job);
    }
    
    // Extract position title - this is the key fix
    String positionTitle = extractPositionTitle(job);
    if (positionTitle != null && !positionTitle.trim().isEmpty()) {
        String cleanTitle = cleanPositionTitle(positionTitle);
        if (isValidPositionTitle(cleanTitle)) {
            int currentCount = positionCounts.getOrDefault(cleanTitle, 0);
            positionCounts.put(cleanTitle, currentCount + 1);
            System.out.println("Added position '" + cleanTitle + "' for series " + code + 
                            ". Count: " + (currentCount + 1));
        } else {
            System.out.println("Rejected invalid position title: " + cleanTitle);
        }
    } else {
        System.out.println("No position title extracted for job in series " + code);
    }
    
    // Extract keywords from job content
    String[] textFields = {
        job.path("UserArea").path("Details").path("JobSummary").asText(),
        job.path("QualificationSummary").asText(),
        job.path("PositionTitle").asText() // Also use position title for keywords
    };
    
    for (String text : textFields) {
        if (text != null && !text.isEmpty()) {
            keywords.addAll(extractKeywords(text));
        }
    }
}
    
    private String extractPositionTitle(JsonNode job) {
    // USAJobs API has PositionTitle directly in MatchedObjectDescriptor
    String positionTitle = job.path("PositionTitle").asText();
    if (positionTitle != null && !positionTitle.isEmpty() && !positionTitle.equals("")) {
        System.out.println("Found PositionTitle: " + positionTitle);
        return positionTitle;
    }
    
    // Alternative path - sometimes it's in PositionInformation
    JsonNode positionInfo = job.path("PositionInformation");
    if (!positionInfo.isMissingNode()) {
        positionTitle = positionInfo.path("PositionTitle").asText();
        if (positionTitle != null && !positionTitle.isEmpty()) {
            System.out.println("Found PositionTitle in PositionInformation: " + positionTitle);
            return positionTitle;
        }
    }
    
    // Check UserArea as well
    JsonNode userArea = job.path("UserArea");
    if (!userArea.isMissingNode()) {
        JsonNode details = userArea.path("Details");
        if (!details.isMissingNode()) {
            positionTitle = details.path("JobTitle").asText();
            if (positionTitle != null && !positionTitle.isEmpty()) {
                System.out.println("Found JobTitle in UserArea.Details: " + positionTitle);
                return positionTitle;
            }
        }
    }
    
    System.out.println("No position title found in job data");
    return null;
}
    
    private String cleanPositionTitle(String title) {
    if (title == null) return null;
    
    String cleaned = title
        .replaceAll("\\s*\\([^)]*\\)\\s*", " ")    // Remove parenthetical content
        .replaceAll("\\s*-\\s*GS-\\d+-\\d+", "")   // Remove GS grade info
        .replaceAll("\\s*GS-\\d+-\\d+", "")        // Remove GS grade prefix
        .replaceAll("\\s+-\\s+\\d+\\s*$", "")      // Remove trailing numbers
        .replaceAll("\\s*,\\s*\\d+\\s*$", "")      // Remove trailing comma-numbers
        .replaceAll("^\\d+\\s*-\\s*", "")          // Remove leading numbers
        .replaceAll("\\s+", " ")                   // Normalize whitespace
        .trim();
    
    // Remove empty parentheses and brackets
    cleaned = cleaned.replaceAll("\\s*\\(\\s*\\)\\s*", " ")
                    .replaceAll("\\s*\\[\\s*\\]\\s*", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
    
    return cleaned;
}
    
    private boolean isValidPositionTitle(String title) {
    if (title == null || title.trim().isEmpty()) {
        return false;
    }
    
    // Must be reasonable length
    if (title.length() < 5 || title.length() > 150) {
        return false;
    }
    
    // Must contain letters
    if (!title.matches(".*[a-zA-Z].*")) {
        return false;
    }
    
    // Exclude invalid patterns
    String lowerTitle = title.toLowerCase().trim();
    String[] invalidPatterns = {
        "^\\d+$",              // Just numbers
        "^[a-z]{1,2}$",        // Single/double letters
        "^n/?a$",              // N/A
        "^tbd$",               // TBD
        "^none$",              // None
        "^temp$",              // Temp alone
        "^temporary$",         // Temporary alone
        "^contractor$",        // Just contractor
        "^employee$",          // Just employee
        "^position$",          // Just position
        "^job$",               // Just job
        "^vacancy$"            // Just vacancy
    };
    
    for (String pattern : invalidPatterns) {
        if (lowerTitle.matches(pattern)) {
            return false;
        }
    }
    
    return true;
}
    
    private String extractTitle(JsonNode job) {
        // First try to get from JobCategory
        JsonNode categories = job.path("JobCategory");
        if (categories.isArray()) {
            for (JsonNode category : categories) {
                if (code.equals(category.path("Code").asText())) {
                    String categoryTitle = category.path("Name").asText();
                    if (categoryTitle != null && !categoryTitle.isEmpty()) {
                        return categoryTitle;
                    }
                }
            }
        }
        
        // Fallback: derive from most common position title
        if (!positionCounts.isEmpty()) {
            return positionCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Federal Position Series " + code);
        }
        
        return "Federal Position Series " + code;
    }
    
    private Set<String> extractKeywords(String text) {
        Set<String> words = new HashSet<>();
        if (text == null || text.trim().isEmpty()) {
            return words;
        }
        
        String[] tokens = text.toLowerCase()
                .replaceAll("[^a-zA-Z0-9\\s]", " ")
                .split("\\s+");
        
        Set<String> stopWords = Set.of("with", "from", "that", "this", "will", 
            "have", "for", "the", "and", "are", "but", "not", "all", "any", "must", "may");
        
        for (String word : tokens) {
            if (word.length() > 4 && !stopWords.contains(word)) {
                words.add(word);
            }
        }
        
        return words;
    }
    
    int getTotalJobs() { return totalJobs; }
    
    JobSeriesData build() {
    JobSeriesData data = new JobSeriesData();
    data.setCode(code);
    data.setTitle(title);
    data.setKeywords(new ArrayList<>(keywords));
    
    System.out.println("Building series " + code + " with " + positionCounts.size() + " unique position types");
    
    // Debug: Print all positions found
    for (Map.Entry<String, Integer> entry : positionCounts.entrySet()) {
        System.out.println("  Position: '" + entry.getKey() + "' (count: " + entry.getValue() + ")");
    }
    
    // Convert to sorted list by frequency, keeping all positions with at least 1 occurrence
    List<String> sortedPositions = positionCounts.entrySet().stream()
        .filter(entry -> entry.getValue() >= 1)
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
        .map(Map.Entry::getKey)
        .limit(15) // Keep top 15 positions
        .collect(Collectors.toList());
        
    System.out.println("Final positions for series " + code + ": " + sortedPositions);
    
    data.setPositions(sortedPositions);
    data.setTotalJobs(totalJobs);
    
    return data;
}
}

    public void fetchAndProcessJobSeries() throws Exception {
        String apiKey = "szq+h8pmtLiZ++/ldJQh3ZZjfVfEk74mcsAViRJGgCA=";
        String email = "marko.vukovic0311@gmail.com";
        
        Set<JsonNode> allJobs = new HashSet<>();
        
        // Fetch from targeted searches
        String[] queries = {
            "ResultsPerPage=500&Keyword=analyst",
            "ResultsPerPage=500&Keyword=specialist",
            "ResultsPerPage=500&Keyword=manager"
        };
        
        for (String query : queries) {
            for (int page = 1; page <= 3; page++) {
                String url = String.format("https://data.usajobs.gov/api/search?%s&Page=%d", query, page);
                
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization-Key", apiKey)
                        .header("User-Agent", email)
                        .GET()
                        .build();

                HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    JsonNode root = objectMapper.readTree(response.body());
                    JsonNode items = root.path("SearchResult").path("SearchResultItems");
                    
                    for (JsonNode item : items) {
                        allJobs.add(item);
                    }
                }
                
                Thread.sleep(300);
            }
        }

        processJobData(allJobs);
    }

    private void processJobData(Set<JsonNode> allJobs) {
        Map<String, JobSeriesBuilder> builders = new HashMap<>();
        
        for (JsonNode item : allJobs) {
            JsonNode job = item.path("MatchedObjectDescriptor");
            String code = extractSeriesCode(job);
            
            if (code != null && isValidSeriesCode(code)) {
                builders.computeIfAbsent(code, JobSeriesBuilder::new).addJob(job);
            }
        }
        
        jobSeriesData.clear();
        for (JobSeriesBuilder builder : builders.values()) {
            if (builder.getTotalJobs() >= 5) {
                JobSeriesData series = builder.build();
                jobSeriesData.put(series.getCode(), series);
            }
        }
        
        System.out.println("Processed " + jobSeriesData.size() + " job series");
    }
    
    private String extractSeriesCode(JsonNode job) {
        // Try job category first
        JsonNode categories = job.path("JobCategory");
        if (categories.isArray() && categories.size() > 0) {
            for (JsonNode category : categories) {
                String code = category.path("Code").asText();
                if (isValidSeriesCode(code)) {
                    return code;
                }
            }
        }
        
        // Try extracting from text fields
        String[] textFields = {
            job.path("UserArea").path("Details").path("JobSummary").asText(),
            job.path("QualificationSummary").asText(),
            job.path("PositionTitle").asText()
        };
        
        for (String text : textFields) {
            String code = extractCodeFromText(text);
            if (code != null) return code;
        }
        
        return null;
    }
    
    private String extractCodeFromText(String text) {
        if (text == null) return null;
        
        Pattern[] patterns = {
            Pattern.compile("GS[- ]?(\\d{4})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Series[\\s:]+(\\d{4})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\d{4})[\\s-]+series", Pattern.CASE_INSENSITIVE)
        };
        
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                String code = matcher.group(1);
                if (isValidSeriesCode(code)) {
                    return code;
                }
            }
        }
        return null;
    }
    
    private boolean isValidSeriesCode(String code) {
        return code != null && code.matches("\\d{4}") && 
            !code.equals("0000") && 
            Integer.parseInt(code) >= 100 && Integer.parseInt(code) <= 2299;
    }

    // Simplified recommendation system
    public List<Map<String, Object>> getRecommendations(String duties, int maxResults) {
    if (duties == null || duties.trim().isEmpty()) {
        return new ArrayList<>();
    }
    
    // Extract key terms from user input
    Set<String> userTerms = extractKeyTerms(duties);
    
    List<SeriesMatch> matches = new ArrayList<>();
    
    for (JobSeriesData series : jobSeriesData.values()) {
        double score = calculateMatchScore(userTerms, series);
        if (score > 0.1) {
            matches.add(new SeriesMatch(series, score));
        }
    }
    
    return matches.stream()
            .sorted((a, b) -> Double.compare(b.score, a.score))
            .limit(maxResults)
            .map(match -> formatRecommendation(match, duties)) // Pass duties for intelligent position matching
            .collect(Collectors.toList());
}

    private Set<String> extractKeyTerms(String duties) {
        Set<String> terms = new HashSet<>();
        String[] words = duties.toLowerCase()
                .replaceAll("[^a-zA-Z0-9\\s]", " ")
                .split("\\s+");
        
        Set<String> stopWords = Set.of("the", "and", "for", "are", "but", "not", "you", "all", "can", "had", 
            "her", "was", "one", "our", "out", "day", "get", "has", "him", "his", 
            "how", "its", "may", "new", "now", "old", "see", "two", "who", "work", 
            "will", "with", "from", "have", "they", "that", "this", "been", "more", 
            "some", "time", "very", "when", "much", "make", "than", "many", "over",
            "such", "take", "only", "think", "know", "just", "first", "also", "after",
            "back", "other", "good", "could", "would", "federal", "government", 
            "duties", "position", "agency", "department", "employee");
        
        for (String word : words) {
            if (word.length() > 3 && !stopWords.contains(word)) {
                terms.add(word);
            }
        }
        
        return terms;
    }
    
    private double calculateMatchScore(Set<String> userTerms, JobSeriesData series) {
        if (userTerms.isEmpty()) return 0.0;
        
        double score = 0.0;
        
        // Title matching (50% weight)
        String title = series.getTitle().toLowerCase();
        long titleMatches = userTerms.stream()
                .mapToLong(term -> title.contains(term) ? 1 : 0)
                .sum();
        score += (double) titleMatches * 0.5;
        
        // Keywords matching (35% weight)
        if (series.getKeywords() != null) {
            long keywordMatches = 0;
            for (String userTerm : userTerms) {
                for (String keyword : series.getKeywords()) {
                    if (keyword.toLowerCase().contains(userTerm) || 
                        userTerm.contains(keyword.toLowerCase())) {
                        keywordMatches++;
                        break;
                    }
                }
            }
            score += (double) keywordMatches * 0.35;
        }
        
        // Positions matching (15% weight)
        if (series.getPositions() != null) {
            long positionMatches = series.getPositions().stream()
                    .mapToLong(position -> {
                        String lowerPos = position.toLowerCase();
                        return userTerms.stream().anyMatch(term -> 
                            lowerPos.contains(term)) ? 1 : 0;
                    })
                    .sum();
            score += Math.min(1.0, (double) positionMatches / series.getPositions().size()) * 0.15;
        }
        
        return Math.min(1.0, score);
    }
        
    // Legacy compatibility
    public Map<String, Map<String, Object>> getJobSeriesData() {
        Map<String, Map<String, Object>> legacy = new HashMap<>();
        
        for (Map.Entry<String, JobSeriesData> entry : jobSeriesData.entrySet()) {
            JobSeriesData data = entry.getValue();
            Map<String, Object> seriesMap = new HashMap<>();
            seriesMap.put("title", data.getTitle());
            seriesMap.put("keywords", data.getKeywords());
            seriesMap.put("positions", data.getPositions());
            legacy.put(entry.getKey(), seriesMap);
        }
        
        return legacy;
    }

@PostMapping("/api/job-series/{code}/positions")
public List<String> getPositionsForSeries(@PathVariable String code) {
    JobSeriesData series = jobSeriesData.get(code);
    if (series != null && series.getPositions() != null) {
        return series.getPositions();
    }
    return new ArrayList<>();
}

public String getBestPositionMatch(String code, String duties) {
    JobSeriesData series = jobSeriesData.get(code);
    if (series == null || series.getPositions() == null || series.getPositions().isEmpty()) {
        return null;
    }
    
    if (duties == null || duties.trim().isEmpty()) {
        return series.getPositions().get(0); // Fallback to first position
    }
    
    // Extract key terms from user duties
    Set<String> dutiesTerms = extractKeyTermsFromDuties(duties);
    
    // Score each position against the duties
    List<PositionScore> scoredPositions = new ArrayList<>();
    
    for (String position : series.getPositions()) {
        double score = calculatePositionMatchScore(position, dutiesTerms, duties);
        scoredPositions.add(new PositionScore(position, score));
    }
    
    // Sort by score and return the best match
    return scoredPositions.stream()
            .sorted((a, b) -> Double.compare(b.score, a.score))
            .findFirst()
            .map(ps -> ps.position)
            .orElse(series.getPositions().get(0));
}

// Helper class for scoring positions
private static class PositionScore {
    final String position;
    final double score;
    
    PositionScore(String position, double score) {
        this.position = position;
        this.score = score;
    }
}
private Set<String> extractKeyTermsFromDuties(String duties) {
    Set<String> terms = new HashSet<>();
    
    // Convert to lowercase and split into words
    String[] words = duties.toLowerCase()
            .replaceAll("[^a-zA-Z0-9\\s]", " ")
            .split("\\s+");
    
    // Enhanced stop words for federal job context
    Set<String> stopWords = Set.of(
        "the", "and", "for", "are", "but", "not", "you", "all", "can", "had", 
        "her", "was", "one", "our", "out", "day", "get", "has", "him", "his", 
        "how", "its", "may", "new", "now", "old", "see", "two", "who", "work", 
        "will", "with", "from", "have", "they", "that", "this", "been", "more", 
        "some", "time", "very", "when", "much", "make", "than", "many", "over",
        "such", "take", "only", "think", "know", "just", "first", "also", "after",
        "back", "other", "good", "could", "would", "federal", "government", 
        "duties", "position", "agency", "department", "employee", "responsibilities"
    );
    
    // Extract meaningful terms
    for (String word : words) {
        if (word.length() > 2 && !stopWords.contains(word)) {
            terms.add(word);
        }
    }
    
    // After splitting into words, also extract bigrams/trigrams
    for (int i = 0; i < words.length - 1; i++) {
        String bigram = words[i] + " " + words[i + 1];
        if (bigram.length() > 6) terms.add(bigram);
        if (i < words.length - 2) {
            String trigram = words[i] + " " + words[i + 1] + " " + words[i + 2];
            if (trigram.length() > 10) terms.add(trigram);
        }
    }
    // Also extract important phrases (2-3 word combinations)
    extractPhrases(duties.toLowerCase(), terms);
    
    return terms;
}

// Extract important phrases from duties text
private void extractPhrases(String duties, Set<String> terms) {
    // Common federal job phrases that are important for matching
    String[] importantPhrases = {
        "data analysis", "project management", "budget analysis", "policy development",
        "program evaluation", "risk assessment", "quality assurance", "customer service",
        "technical support", "system administration", "database management", "financial analysis",
        "human resources", "contract management", "strategic planning", "compliance monitoring",
        "training development", "research analysis", "cybersecurity", "network administration",
        "software development", "web development", "graphic design", "communications",
        "public affairs", "legal counsel", "audit", "procurement", "logistics"
    };
    
    for (String phrase : importantPhrases) {
        if (duties.contains(phrase)) {
            terms.add(phrase.replace(" ", "_")); // Add as single term
        }
    }
}

// Calculate how well a position matches the user's duties
private double calculatePositionMatchScore(String position, Set<String> dutiesTerms, String dutiesText) {
    if (position == null || position.trim().isEmpty()) {
        return 0.0;
    }
    
    String positionLower = position.toLowerCase();
    String dutiesLower = dutiesText.toLowerCase();
    
    double score = 0.0;
    
    // 1. Direct term matching (40% weight)
    long directMatches = dutiesTerms.stream()
            .mapToLong(term -> positionLower.contains(term.replace("_", " ")) ? 1 : 0)
            .sum();
    score += (double) directMatches * 0.4;
    
    // 2. Semantic similarity based on job function keywords (35% weight)
    score += calculateSemanticSimilarity(positionLower, dutiesLower) * 0.35;
    
    // 3. Seniority level matching (15% weight)
    score += calculateSeniorityMatch(positionLower, dutiesLower) * 0.15;
    
    // 4. Domain-specific matching (10% weight)
    score += calculateDomainMatch(positionLower, dutiesLower) * 0.10;
    
    return Math.min(1.0, score);
}

// Calculate semantic similarity between position and duties
private double calculateSemanticSimilarity(String position, String duties) {
    // Define semantic clusters for federal positions
    Map<String, Set<String>> semanticClusters = Map.of(
        "analyst", Set.of("analysis", "analyze", "research", "study", "evaluate", "assess", "review", "examine"),
        "specialist", Set.of("expert", "technical", "specialized", "advanced", "proficient", "skilled"),
        "manager", Set.of("manage", "supervise", "lead", "direct", "coordinate", "oversee", "administer"),
        "officer", Set.of("enforce", "compliance", "regulation", "policy", "law", "rule", "standard"),
        "coordinator", Set.of("coordinate", "organize", "facilitate", "arrange", "schedule", "plan"),
        "advisor", Set.of("advise", "consult", "recommend", "guide", "counsel", "support"),
        "technician", Set.of("technical", "maintenance", "repair", "install", "configure", "troubleshoot"),
        "administrator", Set.of("administer", "process", "handle", "manage", "maintain", "operate")
    );
    
    double maxSimilarity = 0.0;

    // In calculateSemanticSimilarity, add fuzzy matching
for (Map.Entry<String, Set<String>> cluster : semanticClusters.entrySet()) {
    if (position.contains(cluster.getKey())) {
        long matches = cluster.getValue().stream()
            .mapToLong(keyword -> duties.contains(keyword) || duties.contains(fuzzy(keyword, duties)) ? 1 : 0)
            .sum();
        double similarity = (double) matches / cluster.getValue().size();
        maxSimilarity = Math.max(maxSimilarity, similarity);
    }
}
    
    for (Map.Entry<String, Set<String>> cluster : semanticClusters.entrySet()) {
        if (position.contains(cluster.getKey())) {
            long matches = cluster.getValue().stream()
                    .mapToLong(keyword -> duties.contains(keyword) ? 1 : 0)
                    .sum();
            double similarity = (double) matches / cluster.getValue().size();
            maxSimilarity = Math.max(maxSimilarity, similarity);
        }
    }
    
    return maxSimilarity;
}

private String fuzzy(String keyword, String duties) {
    // Try plural form
    if (duties.contains(keyword + "s")) return keyword + "s";
    // Try singular form
    if (keyword.endsWith("s") && duties.contains(keyword.substring(0, keyword.length() - 1))) {
        return keyword.substring(0, keyword.length() - 1);
    }
    // Try common typo (missing letter)
    if (keyword.length() > 4) {
        String typo = keyword.substring(0, keyword.length() - 1);
        if (duties.contains(typo)) return typo;
    }
    return "";
}

// Calculate seniority level matching
private double calculateSeniorityMatch(String position, String duties) {
    // Extract seniority indicators
    boolean positionIsSenior = position.matches(".*(senior|lead|principal|chief|director|supervisor).*");
    boolean positionIsEntry = position.matches(".*(junior|entry|assistant|trainee|intern).*");
    
    boolean dutiesIndicateSenior = duties.matches(".*(manage|supervise|lead|direct|oversee|mentor|senior|advanced|expert).*");
    boolean dutiesIndicateEntry = duties.matches(".*(assist|support|learn|basic|entry|junior|trainee).*");
    
    // Perfect match
    if ((positionIsSenior && dutiesIndicateSenior) || 
        (positionIsEntry && dutiesIndicateEntry) ||
        (!positionIsSenior && !positionIsEntry && !dutiesIndicateSenior && !dutiesIndicateEntry)) {
        return 1.0;
    }
    
    // Partial mismatch
    if ((positionIsSenior && dutiesIndicateEntry) || (positionIsEntry && dutiesIndicateSenior)) {
        return 0.2;
    }
    
    return 0.6; // Neutral match
}

// Calculate domain-specific matching
private double calculateDomainMatch(String position, String duties) {
    // Define domain keywords
    Map<String, Set<String>> domains = Map.of(
        "IT", Set.of("computer", "software", "network", "database", "system", "technical", "cyber", "data"),
        "Financial", Set.of("budget", "financial", "accounting", "fiscal", "economic", "cost", "audit"),
        "HR", Set.of("human", "personnel", "employee", "recruitment", "training", "benefits", "payroll"),
        "Legal", Set.of("legal", "law", "regulation", "compliance", "contract", "policy", "attorney"),
        "Administrative", Set.of("administrative", "clerical", "office", "support", "coordination", "scheduling"),
        "Engineering", Set.of("engineering", "technical", "design", "development", "construction", "maintenance"),
        "Medical", Set.of("medical", "health", "clinical", "patient", "healthcare", "nursing", "therapy"),
        "Security", Set.of("security", "investigation", "enforcement", "protection", "safety", "guard")
    );
    
    double maxMatch = 0.0;
    
    for (Map.Entry<String, Set<String>> domain : domains.entrySet()) {
        // Check if position belongs to this domain
        boolean positionInDomain = domain.getValue().stream()
                .anyMatch(keyword -> position.contains(keyword));
        
        if (positionInDomain) {
            // Count how many domain keywords appear in duties
            long domainMatches = domain.getValue().stream()
                    .mapToLong(keyword -> duties.contains(keyword) ? 1 : 0)
                    .sum();
            double domainScore = (double) domainMatches / domain.getValue().size();
            maxMatch = Math.max(maxMatch, domainScore);
        }
    }
    
    return maxMatch;
}

// Update the formatRecommendation method to include intelligent position recommendation
private Map<String, Object> formatRecommendation(SeriesMatch match, String duties) {
    Map<String, Object> result = new HashMap<>();
    JobSeriesData series = match.series;

    result.put("code", series.getCode());
    result.put("title", series.getTitle());
    
    // BOOST the confidence score significantly
    double originalScore = match.score;
    double boostedScore;
    
    if (originalScore >= 0.3) {
        boostedScore = Math.min(0.95, originalScore + 0.3);
    } else if (originalScore >= 0.2) {
        boostedScore = Math.min(0.80, originalScore + 0.2);
    } else {
        boostedScore = originalScore; // No boost for weak matches
    }
    
    result.put("confidence", Math.round(boostedScore * 100.0) / 100.0);

    List<String> positions = series.getPositions();
    if (positions != null && !positions.isEmpty()) {
        result.put("hasValidPositions", true);
        result.put("positionCount", positions.size());
        
        // Get intelligent position recommendation based on duties
        String bestMatch = getBestPositionMatch(series.getCode(), duties);
        result.put("topPosition", bestMatch != null ? bestMatch : positions.get(0));
        
        // Also boost the position match score
        if (bestMatch != null && duties != null) {
            Set<String> dutiesTerms = extractKeyTermsFromDuties(duties);
            double originalPositionScore = calculatePositionMatchScore(bestMatch, dutiesTerms, duties);
            double boostedPositionScore = Math.min(0.95, originalPositionScore + 0.3);
            result.put("positionMatchScore", Math.round(boostedPositionScore * 100.0) / 100.0);
        }
    } else {
        result.put("hasValidPositions", false);
        result.put("positionCount", 0);
        result.put("topPosition", "Position data not available");
    }

    result.put("totalJobs", series.getTotalJobs());
    
    return result;
}

// Utility to get OpenAI embedding for a text
private List<Double> getOpenAIEmbedding(String text) throws Exception {
    String apiKey = openaiApiKey;
    String endpoint = "https://api.openai.com/v1/embeddings";
    String model = "text-embedding-ada-002";

    // Build JSON payload safely
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> payloadMap = new HashMap<>();
    payloadMap.put("input", text);
    payloadMap.put("model", model);
    String payload = mapper.writeValueAsString(payloadMap);

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(endpoint))
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();

    HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) throw new RuntimeException("OpenAI embedding failed: " + response.body());

    JsonNode root = objectMapper.readTree(response.body());
    JsonNode embeddingNode = root.path("data").get(0).path("embedding");
    List<Double> embedding = new ArrayList<>();
    for (JsonNode value : embeddingNode) {
        embedding.add(value.asDouble());
    }
    return embedding;
}

// Utility to compute cosine similarity between two embeddings
private double cosineSimilarity(List<Double> a, List<Double> b) {
    double dot = 0.0, normA = 0.0, normB = 0.0;
    for (int i = 0; i < a.size(); i++) {
        dot += a.get(i) * b.get(i);
        normA += a.get(i) * a.get(i);
        normB += b.get(i) * b.get(i);
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
}

// New recommendation method using embeddings
public List<Map<String, Object>> getRecommendationsWithEmbeddings(String duties, int maxResults) throws Exception {
    if (duties == null || duties.trim().isEmpty()) return new ArrayList<>();
    List<Double> dutiesEmbedding = getOpenAIEmbedding(duties);

    List<SeriesMatch> matches = new ArrayList<>();
    for (JobSeriesData series : jobSeriesData.values()) {
        // Use the most common position description for embedding
        String positionDesc = series.getPositions() != null && !series.getPositions().isEmpty()
            ? series.getPositions().get(0)
            : series.getTitle();
        String context = positionDesc;
        if (series.getKeywords() != null && !series.getKeywords().isEmpty()) {
            context += " " + String.join(" ", series.getKeywords());
        }
        // Optionally add a sample job summary if available
        List<Double> positionEmbedding = getOpenAIEmbedding(context);

        double similarity = cosineSimilarity(dutiesEmbedding, positionEmbedding);
        if (similarity > 0.2) { // Threshold for match
            matches.add(new SeriesMatch(series, similarity));
        }
    }

    return matches.stream()
        .sorted((a, b) -> Double.compare(b.score, a.score))
        .limit(maxResults)
        .map(match -> formatRecommendation(match, duties))
        .collect(Collectors.toList());
}
}

