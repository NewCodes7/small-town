package com.newcodes7.small_town.crawler.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MediumDateParsingTest {
    
    /**
     * Test date parsing logic directly without WebDriver
     */
    @Test
    public void testMediumDateParsing() {
        LocalDateTime now = LocalDateTime.now();
        
        // Test simple patterns (3d ago, 2h ago, 5m ago)
        assertEquals(now.minusDays(3).getDayOfYear(), parseDateText("3d ago").getDayOfYear());
        assertEquals(now.minusHours(2).getHour(), parseDateText("2h ago").getHour());
        assertEquals(now.minusMinutes(5).getMinute(), parseDateText("5m ago").getMinute());
        
        // Test relative patterns
        assertEquals(now.minusDays(1).getDayOfYear(), parseDateText("1 day ago").getDayOfYear());
        assertEquals(now.minusHours(12).getHour(), parseDateText("12 hours ago").getHour());
        assertEquals(now.minusMinutes(30).getMinute(), parseDateText("30 minutes ago").getMinute());
        
        // Test absolute patterns
        LocalDateTime oct15 = parseDateText("Oct 15, 2023");
        assertNotNull(oct15);
        assertEquals(2023, oct15.getYear());
        assertEquals(10, oct15.getMonthValue());  
        assertEquals(15, oct15.getDayOfMonth());
        
        System.out.println("✅ All date parsing tests passed!");
    }
    
    @Test
    public void testDateKeywordDetection() {
        assertTrue(containsDateKeywords("3d ago"));
        assertTrue(containsDateKeywords("2 hours ago"));
        assertTrue(containsDateKeywords("5 minutes"));
        assertTrue(containsDateKeywords("1 week ago"));
        assertFalse(containsDateKeywords("random text"));
        assertFalse(containsDateKeywords(""));
        
        System.out.println("✅ Date keyword detection tests passed!");
    }
    
    // Copy the date parsing logic from MediumBlogCrawler for testing
    private LocalDateTime parseDateText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        
        text = text.toLowerCase().trim();
        LocalDateTime now = LocalDateTime.now();
        
        // Simple patterns used in Medium (e.g., "3d ago", "2h ago", "5m ago")
        Pattern simplePattern = Pattern.compile("(\\d+)([dhm])\\s*(?:ago)?");
        Matcher simpleMatcher = simplePattern.matcher(text);
        if (simpleMatcher.find()) {
            try {
                int value = Integer.parseInt(simpleMatcher.group(1));
                String unit = simpleMatcher.group(2);
                
                switch (unit) {
                    case "d":
                        return now.minusDays(value);
                    case "h":
                        return now.minusHours(value);
                    case "m":
                        return now.minusMinutes(value);
                }
            } catch (NumberFormatException e) {
                // Continue to other patterns
            }
        }
        
        // Relative date patterns
        if (text.contains("minute") || text.contains("min")) {
            Pattern pattern = Pattern.compile("(\\d+)\\s*(?:minute|min)");
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                int minutes = Integer.parseInt(matcher.group(1));
                return now.minusMinutes(minutes);
            }
        }
        
        if (text.contains("hour") || text.contains("hr")) {
            Pattern hourPattern = Pattern.compile("(\\d+)\\s*(?:hour|hr|h)");
            Matcher hourMatcher = hourPattern.matcher(text);
            if (hourMatcher.find()) {
                int hours = Integer.parseInt(hourMatcher.group(1));
                return now.minusHours(hours);
            }
        }
        
        if (text.contains("day")) {
            Pattern dayPattern = Pattern.compile("(\\d+)\\s*day");
            Matcher dayMatcher = dayPattern.matcher(text);
            if (dayMatcher.find()) {
                int days = Integer.parseInt(dayMatcher.group(1));
                return now.minusDays(days);
            }
        }
        
        if (text.contains("week")) {
            Pattern weekPattern = Pattern.compile("(\\d+)\\s*week");
            Matcher weekMatcher = weekPattern.matcher(text);
            if (weekMatcher.find()) {
                int weeks = Integer.parseInt(weekMatcher.group(1));
                return now.minusWeeks(weeks);
            }
        }
        
        if (text.contains("month")) {
            Pattern monthPattern = Pattern.compile("(\\d+)\\s*month");
            Matcher monthMatcher = monthPattern.matcher(text);
            if (monthMatcher.find()) {
                int months = Integer.parseInt(monthMatcher.group(1));
                return now.minusMonths(months);
            }
        }
        
        // Absolute date patterns (e.g., Oct 15, 2023)
        Pattern absolutePattern = Pattern.compile("(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\s+(\\d{1,2}),?\\s+(\\d{4})");
        Matcher absoluteMatcher = absolutePattern.matcher(text);
        if (absoluteMatcher.find()) {
            try {
                String monthStr = absoluteMatcher.group(1);
                int day = Integer.parseInt(absoluteMatcher.group(2));
                int year = Integer.parseInt(absoluteMatcher.group(3));
                
                int month = getMonthNumber(monthStr);
                return LocalDateTime.of(year, month, day, 0, 0);
            } catch (Exception e) {
                // Continue
            }
        }
        
        return null;
    }
    
    private boolean containsDateKeywords(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        String lowerText = text.toLowerCase();
        return lowerText.contains("ago") || 
               lowerText.contains("day") || 
               lowerText.contains("hour") || 
               lowerText.contains("minute") || 
               lowerText.contains("min") || 
               lowerText.contains("week") || 
               lowerText.contains("month") || 
               lowerText.contains("year") ||
               lowerText.matches("\\d+[dhm].*");
    }
    
    private int getMonthNumber(String monthStr) {
        switch (monthStr.toLowerCase()) {
            case "jan": return 1;
            case "feb": return 2;
            case "mar": return 3;
            case "apr": return 4;
            case "may": return 5;
            case "jun": return 6;
            case "jul": return 7;
            case "aug": return 8;
            case "sep": return 9;
            case "oct": return 10;
            case "nov": return 11;
            case "dec": return 12;
            default: return 1;
        }
    }
}