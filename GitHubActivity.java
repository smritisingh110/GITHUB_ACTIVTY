import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub Activity CLI - Fetch and display recent GitHub user activity
 * Usage: java GitHubActivity <username>
 */
public class GitHubActivity {
    
    private static final String BASE_URL = "https://api.github.com/users/%s/events";
    private static final String USER_AGENT = "GitHub-Activity-CLI/1.0";
    private static final int TIMEOUT = 10000; // 10 seconds
    
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java GitHubActivity <username>");
            System.out.println("Example: java GitHubActivity kamranahmedse");
            System.exit(1);
        }
        
        String username = args[0].trim();
        if (username.isEmpty()) {
            System.out.println("Error: Username cannot be empty");
            System.exit(1);
        }
        
        GitHubActivity cli = new GitHubActivity();
        try {
            System.out.println("Fetching activity for GitHub user: " + username + "...");
            String jsonResponse = cli.fetchUserActivity(username);
            List<String> activities = cli.parseAndFormatActivity(jsonResponse);
            cli.displayActivity(username, activities);
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }
    
    /**
     * Fetch recent activity for a GitHub user
     * @param username GitHub username
     * @return JSON response as string
     * @throws Exception if request fails
     */
    public String fetchUserActivity(String username) throws Exception {
        String urlString = String.format(BASE_URL, username);
        URL url;
        
        try {
            URI uri = new URI(urlString);
            url = uri.toURL();
        } catch (URISyntaxException e) {
            throw new Exception("Invalid URL: " + e.getMessage());
        }
        
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
            connection.setConnectTimeout(TIMEOUT);
            connection.setReadTimeout(TIMEOUT);
            
            int responseCode = connection.getResponseCode();
            
            if (responseCode == 404) {
                throw new Exception("User '" + username + "' not found");
            } else if (responseCode == 403) {
                throw new Exception("API rate limit exceeded. Please try again later");
            } else if (responseCode != 200) {
                throw new Exception("GitHub API error: HTTP " + responseCode);
            }
            
            reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            
            return response.toString();
            
        } catch (IOException e) {
            throw new Exception("Network error: " + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    
    /**
     * Parse JSON response and format activities into readable strings
     * @param jsonResponse Raw JSON response from GitHub API
     * @return List of formatted activity strings
     */
    public List<String> parseAndFormatActivity(String jsonResponse) {
        List<String> activities = new ArrayList<>();
        
        // Parse JSON manually using regex patterns (since we can't use external libraries)
        List<GitHubEvent> events = parseEvents(jsonResponse);
        
        for (GitHubEvent event : events) {
            String activity = formatEvent(event);
            if (activity != null && !activity.isEmpty()) {
                activities.add(activity);
            }
        }
        
        return activities;
    }
    
    /**
     * Parse GitHub events from JSON response using regex
     * @param jsonResponse JSON string
     * @return List of GitHubEvent objects
     */
    private List<GitHubEvent> parseEvents(String jsonResponse) {
        List<GitHubEvent> events = new ArrayList<>();
        
        // Split by events (each event is enclosed in {})
        Pattern eventPattern = Pattern.compile("\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}");
        Matcher eventMatcher = eventPattern.matcher(jsonResponse);
        
        while (eventMatcher.find()) {
            String eventJson = eventMatcher.group();
            GitHubEvent event = parseEvent(eventJson);
            if (event != null) {
                events.add(event);
            }
        }
        
        return events;
    }
    
    /**
     * Parse a single event from JSON string
     * @param eventJson JSON string for single event
     * @return GitHubEvent object
     */
    private GitHubEvent parseEvent(String eventJson) {
        try {
            GitHubEvent event = new GitHubEvent();
            
            // Extract type
            String type = extractJsonValue(eventJson, "type");
            event.setType(type);
            
            // Extract repo name
            String repoName = extractJsonValue(eventJson, "name", "repo");
            event.setRepoName(repoName);
            
            // Extract payload information based on event type
            if ("PushEvent".equals(type)) {
                int commitCount = countCommits(eventJson);
                event.setCommitCount(commitCount);
            } else if ("IssuesEvent".equals(type) || "PullRequestEvent".equals(type)) {
                String action = extractJsonValue(eventJson, "action", "payload");
                event.setAction(action);
            } else if ("CreateEvent".equals(type) || "DeleteEvent".equals(type)) {
                String refType = extractJsonValue(eventJson, "ref_type", "payload");
                String ref = extractJsonValue(eventJson, "ref", "payload");
                event.setRefType(refType);
                event.setRef(ref);
            } else if ("ReleaseEvent".equals(type)) {
                String action = extractJsonValue(eventJson, "action", "payload");
                String tagName = extractJsonValue(eventJson, "tag_name", "release", "payload");
                event.setAction(action);
                event.setRef(tagName);
            } else if ("PullRequestEvent".equals(type)) {
                boolean merged = eventJson.contains("\"merged\":true");
                event.setMerged(merged);
            }
            
            return event;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Extract JSON value using regex
     * @param json JSON string
     * @param key Key to extract
     * @param parentKeys Optional parent keys for nested values
     * @return Extracted value or null
     */
    private String extractJsonValue(String json, String key, String... parentKeys) {
        String pattern;
        if (parentKeys.length > 0) {
            // For nested values, create a more complex pattern
            StringBuilder patternBuilder = new StringBuilder();
            for (String parent : parentKeys) {
                patternBuilder.append("\"").append(parent).append("\"\\s*:\\s*\\{[^}]*");
            }
            pattern = patternBuilder.toString() + "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
        } else {
            pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
        }
        
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
    
    /**
     * Count commits in a push event
     * @param eventJson Event JSON string
     * @return Number of commits
     */
    private int countCommits(String eventJson) {
        Pattern commitPattern = Pattern.compile("\"commits\"\\s*:\\s*\\[(.*?)\\]");
        Matcher commitMatcher = commitPattern.matcher(eventJson);
        if (commitMatcher.find()) {
            String commitsArray = commitMatcher.group(1);
            if (commitsArray.trim().isEmpty()) {
                return 0;
            }
            // Count commit objects by counting opening braces
            Pattern commitObjectPattern = Pattern.compile("\\{");
            Matcher objectMatcher = commitObjectPattern.matcher(commitsArray);
            int count = 0;
            while (objectMatcher.find()) {
                count++;
            }
            return count;
        }
        return 1; // Default to 1 if we can't parse
    }
    
    /**
     * Format a single GitHub event into a readable string
     * @param event GitHubEvent object
     * @return Formatted activity string
     */
    private String formatEvent(GitHubEvent event) {
        if (event.getType() == null || event.getRepoName() == null) {
            return null;
        }
        
        String type = event.getType();
        String repo = event.getRepoName();
        
        switch (type) {
            case "PushEvent":
                int commits = event.getCommitCount();
                return String.format("- Pushed %d commit%s to %s", 
                    commits, commits != 1 ? "s" : "", repo);
                
            case "IssuesEvent":
                String action = event.getAction();
                if ("opened".equals(action)) {
                    return "- Opened a new issue in " + repo;
                } else if ("closed".equals(action)) {
                    return "- Closed an issue in " + repo;
                } else if (action != null) {
                    return "- " + capitalize(action) + " an issue in " + repo;
                }
                break;
                
            case "WatchEvent":
                return "- Starred " + repo;
                
            case "ForkEvent":
                return "- Forked " + repo;
                
            case "CreateEvent":
                String refType = event.getRefType();
                if ("repository".equals(refType)) {
                    return "- Created repository " + repo;
                } else if ("branch".equals(refType)) {
                    String branch = event.getRef();
                    return "- Created branch " + (branch != null ? branch : "unknown") + " in " + repo;
                } else if ("tag".equals(refType)) {
                    String tag = event.getRef();
                    return "- Created tag " + (tag != null ? tag : "unknown") + " in " + repo;
                }
                break;
                
            case "DeleteEvent":
                String delRefType = event.getRefType() != null ? event.getRefType() : "branch";
                String delRef = event.getRef() != null ? event.getRef() : "unknown";
                return "- Deleted " + delRefType + " " + delRef + " in " + repo;
                
            case "PullRequestEvent":
                String prAction = event.getAction();
                if ("opened".equals(prAction)) {
                    return "- Opened a pull request in " + repo;
                } else if ("closed".equals(prAction)) {
                    if (event.isMerged()) {
                        return "- Merged a pull request in " + repo;
                    } else {
                        return "- Closed a pull request in " + repo;
                    }
                } else if (prAction != null) {
                    return "- " + capitalize(prAction) + " a pull request in " + repo;
                }
                break;
                
            case "ReleaseEvent":
                String releaseAction = event.getAction();
                if ("published".equals(releaseAction)) {
                    String tagName = event.getRef() != null ? event.getRef() : "unknown";
                    return "- Published release " + tagName + " in " + repo;
                }
                break;
                
            case "PublicEvent":
                return "- Made " + repo + " public";
                
            default:
                // For unhandled event types, return a generic message
                String eventName = type.replace("Event", "");
                return "- " + eventName + " in " + repo;
        }
        
        return null;
    }
    
    /**
     * Capitalize the first letter of a string
     * @param str Input string
     * @return Capitalized string
     */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
    
    /**
     * Display the formatted activities in the terminal
     * @param username GitHub username
     * @param activities List of formatted activity strings
     */
    public void displayActivity(String username, List<String> activities) {
        if (activities.isEmpty()) {
            System.out.println("No recent activity found for user '" + username + "'");
            return;
        }
        
        System.out.println("Recent activity for " + username + ":");
        System.out.println();
        
        // Limit to most recent 20 activities
        int count = Math.min(activities.size(), 20);
        for (int i = 0; i < count; i++) {
            System.out.println(activities.get(i));
        }
    }
    
    /**
     * Inner class to represent a GitHub event
     */
    private static class GitHubEvent {
        private String type;
        private String repoName;
        private String action;
        private String refType;
        private String ref;
        private int commitCount;
        private boolean merged;
        
        // Getters and setters
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        
        public String getRepoName() { return repoName; }
        public void setRepoName(String repoName) { this.repoName = repoName; }
        
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        
        public String getRefType() { return refType; }
        public void setRefType(String refType) { this.refType = refType; }
        
        public String getRef() { return ref; }
        public void setRef(String ref) { this.ref = ref; }
        
        public int getCommitCount() { return commitCount; }
        public void setCommitCount(int commitCount) { this.commitCount = commitCount; }
        
        public boolean isMerged() { return merged; }
        public void setMerged(boolean merged) { this.merged = merged; }
    }
}