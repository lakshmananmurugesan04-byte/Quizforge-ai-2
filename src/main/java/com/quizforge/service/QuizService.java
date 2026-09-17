package com.quizforge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.dto.Question;
import com.quizforge.dto.QuizRequest;
import com.quizforge.dto.QuizResponse;
import com.quizforge.util.DuplicateDetector;
import com.quizforge.util.LanguageDetector;
import com.quizforge.util.LanguageDetector.DetectedLanguage;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class QuizService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final int MAX_RETRY_ITERATIONS = 5;

    public QuizResponse generateQuiz(QuizRequest request) {
        // 1. Validate notes
        if (request.getNotes() == null || request.getNotes().trim().length() < 15) {
            return new QuizResponse(false, "Please provide sufficient study notes (at least a few sentences) to generate a quiz.");
        }

        // 2. Validate question count (selected by user: 3 to 30)
        int numQuestions = request.getNumQuestions() != null ? request.getNumQuestions() : 5;
        if (numQuestions < 3) numQuestions = 3;
        if (numQuestions > 30) numQuestions = 30;

        // 3. Validate difficulty
        String difficulty = request.getDifficulty();
        if (difficulty == null || difficulty.trim().isEmpty()) {
            difficulty = "Medium";
        }

        String notes = request.getNotes().trim();

        // 4. Detect Language
        DetectedLanguage detectedLanguage = LanguageDetector.detectLanguage(notes);

        // Required backend logging
        System.out.println("[QuizService] Request received - Extracted text length: " + notes.length() + " chars, Requested question count: " + numQuestions + ", Language: " + detectedLanguage.getLabel());

        // 5. Check API key
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            apiKey = System.getenv("AI_API_KEY");
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            apiKey = System.getProperty("GEMINI_API_KEY");
        }

        if (apiKey != null && !apiKey.trim().isEmpty()) {
            try {
                QuizResponse response = generateQuizWithGemini(notes, numQuestions, difficulty, detectedLanguage, apiKey.trim());
                if (response.isSuccess() && response.getQuestions() != null && response.getQuestions().size() == numQuestions) {
                    return response;
                }
            } catch (Exception e) {
                System.err.println("Gemini API call failed: " + e.getMessage() + ". Falling back to local note parser.");
            }
        }

        // 6. Fallback local generator when API key is missing or offline or Gemini yields short set
        return generateFallbackQuiz(notes, numQuestions, difficulty, detectedLanguage);
    }

    private QuizResponse generateQuizWithGemini(String notes, int targetCount, String difficulty, DetectedLanguage language, String apiKey) throws Exception {
        List<Question> acceptedQuestions = new ArrayList<>();

        // Generate extra candidates (targetCount + buffer) to account for duplicates
        int initialCandidateCount = targetCount + Math.max(3, (int) Math.ceil(targetCount * 0.3));
        if (initialCandidateCount > 35) initialCandidateCount = 35;

        // Initial Generation Prompt
        String systemPrompt = String.format(
            "You are QuizForge AI, an expert educational assessment generator. Your task is to generate a quiz based ONLY on the provided study notes.\n\n" +
            "LANGUAGE INSTRUCTION:\n" +
            "%s\n\n" +
            "CRITICAL REQUIRED QUESTION COUNT:\n" +
            "You MUST generate EXACTLY %d candidate questions. Do NOT generate fewer questions.\n\n" +
            "QUESTION DIVERSITY & QUALITY RULES:\n" +
            "1. Base all questions strictly and ONLY on facts present in the study notes below. Do NOT invent facts or use outside knowledge.\n" +
            "2. Ensure HIGH QUESTION DIVERSITY. Generate questions covering different parts and aspects of the notes (such as Definitions, Core Concepts, Functions, Examples, Applications, or Comparisons).\n" +
            "3. STRICTLY PREVENT DUPLICATES: Do NOT generate questions that test the exact same concept or reword another question.\n" +
            "4. Target difficulty level: %s.\n" +
            "5. Each question must have exactly 4 options in an array 'options'.\n" +
            "6. Specify 'correctAnswer' as the 0-based integer index (0, 1, 2, or 3) pointing to the correct option.\n" +
            "7. Provide a short explanation citing the specific note content.\n" +
            "8. Return ONLY valid raw JSON with NO markdown code block formatting (no ```json). Structure:\n" +
            "{\n" +
            "  \"questions\": [\n" +
            "    {\n" +
            "      \"question\": \"String\",\n" +
            "      \"options\": [\"Option 0\", \"Option 1\", \"Option 2\", \"Option 3\"],\n" +
            "      \"correctAnswer\": 0,\n" +
            "      \"explanation\": \"String\"\n" +
            "    }\n" +
            "  ]\n" +
            "}\n\n" +
            "STUDY NOTES:\n---\n%s\n---",
            language.getPromptInstruction(), initialCandidateCount, difficulty, notes
        );

        List<Question> candidateBatch = fetchQuestionsFromGemini(systemPrompt, apiKey);
        filterAndAddUniqueQuestions(candidateBatch, acceptedQuestions, targetCount);

        // Automated Replacement & Regeneration Loop
        int retry = 0;
        while (acceptedQuestions.size() < targetCount && retry < MAX_RETRY_ITERATIONS) {
            retry++;
            int needed = targetCount - acceptedQuestions.size();
            int replacementCandidateCount = needed + Math.max(2, (int) Math.ceil(needed * 0.5));
            if (replacementCandidateCount > 25) replacementCandidateCount = 25;

            StringBuilder existingQuestionsSummary = new StringBuilder();
            for (int i = 0; i < acceptedQuestions.size(); i++) {
                existingQuestionsSummary.append(i + 1).append(". ").append(acceptedQuestions.get(i).getQuestion()).append("\n");
            }

            String replacementPrompt = String.format(
                "You are QuizForge AI. We are generating a quiz from the study notes below.\n\n" +
                "LANGUAGE INSTRUCTION:\n%s\n\n" +
                "CRITICAL DUPLICATE AVOIDANCE:\n" +
                "We ALREADY have the following questions covering these concepts:\n" +
                "%s\n" +
                "Generate new questions from different concepts in the provided notes. Do not repeat, rephrase, or semantically duplicate any existing question. The final questions must test information that has not already been tested.\n\n" +
                "Your task: Generate EXACTLY %d NEW, UNIQUE candidate question(s) testing DIFFERENT concepts, facts, or details in the notes.\n" +
                "Target difficulty: %s.\n" +
                "Return ONLY valid raw JSON without markdown code fences:\n" +
                "{\n" +
                "  \"questions\": [\n" +
                "    {\n" +
                "      \"question\": \"String\",\n" +
                "      \"options\": [\"Option 0\", \"Option 1\", \"Option 2\", \"Option 3\"],\n" +
                "      \"correctAnswer\": 0,\n" +
                "      \"explanation\": \"String\"\n" +
                "    }\n" +
                "  ]\n" +
                "}\n\n" +
                "STUDY NOTES:\n---\n%s\n---",
                language.getPromptInstruction(), existingQuestionsSummary.toString(), replacementCandidateCount, difficulty, notes
            );

            try {
                List<Question> replacementBatch = fetchQuestionsFromGemini(replacementPrompt, apiKey);
                filterAndAddUniqueQuestions(replacementBatch, acceptedQuestions, targetCount);
            } catch (Exception e) {
                System.err.println("Replacement generation iteration " + retry + " failed: " + e.getMessage());
            }
        }

        // Top up remaining questions if needed to GUARANTEE exact target count
        if (acceptedQuestions.size() < targetCount) {
            topUpQuestions(acceptedQuestions, targetCount, notes, language);
        }

        // Final safety check: trim to exact requested count
        if (acceptedQuestions.size() > targetCount) {
            acceptedQuestions = new ArrayList<>(acceptedQuestions.subList(0, targetCount));
        }

        if (!acceptedQuestions.isEmpty()) {
            return new QuizResponse(acceptedQuestions, language.getLabel());
        }

        throw new RuntimeException("Could not generate non-duplicate questions from notes.");
    }

    private List<Question> fetchQuestionsFromGemini(String promptStr, String apiKey) throws Exception {
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + apiKey;

        String requestJson = String.format(
            "{\"contents\":[{\"parts\":[{\"text\":%s}]}]}",
            objectMapper.writeValueAsString(promptStr)
        );

        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(25000);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = requestJson.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int status = conn.getResponseCode();
        BufferedReader br;
        if (status >= 200 && status < 300) {
            br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        } else {
            br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            throw new RuntimeException("API error (HTTP " + status + "): " + sb.toString());
        }

        StringBuilder responseSb = new StringBuilder();
        String responseLine;
        while ((responseLine = br.readLine()) != null) {
            responseSb.append(responseLine.trim());
        }

        JsonNode root = objectMapper.readTree(responseSb.toString());
        String contentText = root.path("candidates")
                .get(0)
                .path("content")
                .path("parts")
                .get(0)
                .path("text")
                .asText();

        String jsonText = contentText.trim();
        if (jsonText.startsWith("```json")) {
            jsonText = jsonText.substring(7);
        } else if (jsonText.startsWith("```")) {
            jsonText = jsonText.substring(3);
        }
        if (jsonText.endsWith("```")) {
            jsonText = jsonText.substring(0, jsonText.length() - 3);
        }
        jsonText = jsonText.trim();

        JsonNode parsed = objectMapper.readTree(jsonText);
        if (parsed.has("error")) {
            throw new RuntimeException(parsed.get("error").asText());
        }

        JsonNode questionsArray = parsed.path("questions");
        List<Question> list = new ArrayList<>();
        if (questionsArray.isArray()) {
            for (JsonNode qNode : questionsArray) {
                String qText = qNode.path("question").asText();
                List<String> rawOptions = new ArrayList<>();
                for (JsonNode opt : qNode.path("options")) {
                    rawOptions.add(opt.asText());
                }
                int correct = qNode.path("correctAnswer").asInt(0);
                String expl = qNode.path("explanation").asText();

                if (qText != null && !qText.trim().isEmpty() && rawOptions.size() == 4) {
                    boolean allValid = true;
                    Set<String> uniqueOptions = new HashSet<>();
                    List<String> trimmedOptions = new ArrayList<>();
                    for (String opt : rawOptions) {
                        if (opt == null || opt.trim().isEmpty()) {
                            allValid = false;
                            break;
                        }
                        String tr = opt.trim();
                        trimmedOptions.add(tr);
                        uniqueOptions.add(tr.toLowerCase());
                    }

                    if (allValid && uniqueOptions.size() == 4 && correct >= 0 && correct < 4) {
                        String correctText = trimmedOptions.get(correct);

                        // Randomize correct answer position across A, B, C, D
                        List<String> shuffledOptions = new ArrayList<>(trimmedOptions);
                        Collections.shuffle(shuffledOptions);
                        int newCorrectIndex = shuffledOptions.indexOf(correctText);

                        list.add(new Question(qText.trim(), shuffledOptions, newCorrectIndex, expl.trim()));
                    }
                }
            }
        }
        return list;
    }

    private void filterAndAddUniqueQuestions(List<Question> candidates, List<Question> acceptedQuestions, int targetCount) {
        for (Question q : candidates) {
            if (acceptedQuestions.size() >= targetCount) {
                break;
            }
            if (!DuplicateDetector.isDuplicate(q, acceptedQuestions)) {
                acceptedQuestions.add(q);
            }
        }
    }

    private QuizResponse generateFallbackQuiz(String notes, int requestedCount, String difficulty, DetectedLanguage language) {
        List<Question> questions = new ArrayList<>();
        topUpQuestions(questions, requestedCount, notes, language);

        if (questions.isEmpty()) {
            return new QuizResponse(false, "Could not extract sufficient distinct questions from the provided notes.");
        }

        return new QuizResponse(questions, language.getLabel());
    }

    private void topUpQuestions(List<Question> questions, int targetCount, String notes, DetectedLanguage language) {
        boolean isTamil = language == DetectedLanguage.TAMIL || language == DetectedLanguage.MIXED_TAMIL_ENGLISH;

        // Split notes into sentences and clauses
        String[] rawSegments = notes.split("(?<=[.!?])\\s+|(?<=\\n)|(?<=[,;])\\s+");
        List<String> segments = new ArrayList<>();
        for (String seg : rawSegments) {
            String trimmed = seg.trim();
            if (trimmed.length() > 8) {
                segments.add(trimmed);
            }
        }

        if (segments.isEmpty()) {
            segments.add(notes.trim());
        }

        int pass = 0;
        while (questions.size() < targetCount && pass < 30) {
            pass++;
            for (int i = 0; i < segments.size() && questions.size() < targetCount; i++) {
                String seg = segments.get(i);
                String keyPhrase = extractKeyPhrase(seg);
                int qNum = questions.size() + 1;

                String questionText;
                String correctAnswerText;
                String explanation;

                int mode = (pass - 1) % 4;
                if (mode == 0) {
                    // Fact statement mode
                    String template = isTamil ?
                        "'%s' பற்றி குறிப்புகளில் கூறப்பட்டுள்ள தகவல் எது?" :
                        "According to your notes, what is stated regarding '%s'?";
                    questionText = String.format(template, keyPhrase);
                    correctAnswerText = seg.length() > 120 ? seg.substring(0, 117) + "..." : seg;
                    explanation = isTamil ? "குறிப்பிலிருந்து: \"" + seg + "\"" : "From your notes: \"" + seg + "\"";
                } else if (mode == 1) {
                    // Specific concept detail mode
                    String template = isTamil ?
                        "'%s' தொடர்பான சரியானக் கூற்று எது?" :
                        "Which statement accurately describes '%s' in the study material?";
                    questionText = String.format(template, keyPhrase);
                    correctAnswerText = (isTamil ? "இது " : "It specifies: ") + seg;
                    explanation = isTamil ? "அடிப்படைத் தகவல்: \"" + seg + "\"" : "Study detail: \"" + seg + "\"";
                } else if (mode == 2) {
                    // True aspect mode
                    String template = isTamil ?
                        "பின்வருவனவற்றில் '%s' பற்றிய உண்மையைக் குறிப்பிடுவது எது?" :
                        "Which of the following is true regarding '%s' based on your notes?";
                    questionText = String.format(template, keyPhrase);
                    correctAnswerText = (isTamil ? "குறிப்பிலுள்ள உண்மை: " : "Stated fact: ") + seg;
                    explanation = isTamil ? "குறிப்பிலிருந்து எடுக்கப்பட்ட உண்மை." : "Verified fact from study material.";
                } else {
                    // Summary aspect mode
                    String template = isTamil ?
                        "குறிப்புகளில் குறிப்பிடப்பட்டுள்ள '%s' என்பதன் முக்கியக் கருத்து என்ன?" :
                        "What key detail is highlighted about '%s'?";
                    questionText = String.format(template, keyPhrase);
                    correctAnswerText = seg;
                    explanation = isTamil ? "முக்கியக் செய்தி: \"" + seg + "\"" : "Key note: \"" + seg + "\"";
                }

                // If exact questionText already used, add question number suffix to guarantee distinctness
                final String checkQt = questionText;
                if (questions.stream().anyMatch(q -> DuplicateDetector.stripQuestionBoilerplate(DuplicateDetector.normalize(q.getQuestion()))
                        .equals(DuplicateDetector.stripQuestionBoilerplate(DuplicateDetector.normalize(checkQt))))) {
                    questionText += isTamil ? (" (வினா " + qNum + ")") : (" (Question #" + qNum + ")");
                }

                List<String> options = new ArrayList<>();
                options.add(correctAnswerText);

                if (isTamil) {
                    options.add("இது பாடக் குறிப்புகளுக்கு தொடர்பில்லாத ஒரு தவறானத் தகவலாகும்.");
                    options.add("இது கொடுக்கப்பட்ட குறிப்புடன் முற்றிலும் மாறுபட்டக் கூற்றாகும்.");
                    options.add("மேற்கண்ட கூற்றுகள் எதுவும் குறிப்பில் குறிப்பிடப்படவில்லை.");
                } else {
                    options.add("This is an incorrect statement not supported by your study material.");
                    options.add("This option directly contradicts the provided notes.");
                    options.add("None of the above details are mentioned in the notes.");
                }

                Collections.shuffle(options);
                int correctIndex = options.indexOf(correctAnswerText);

                Question candidate = new Question(questionText, options, correctIndex, explanation);

                if (!DuplicateDetector.isDuplicate(candidate, questions)) {
                    questions.add(candidate);
                }
            }
        }

        // Final safety net: if notes were extremely short and strict duplicate detector rejected variants,
        // force top-up with distinct labelled entries to guarantee target count
        while (questions.size() < targetCount) {
            int qNum = questions.size() + 1;
            String seg = segments.get((qNum - 1) % segments.size());
            String keyPhrase = extractKeyPhrase(seg);
            String questionText = isTamil ?
                ("குறிப்பு கருத்து #" + qNum + ": '" + keyPhrase + "' பற்றிய கேள்வி") :
                ("Study Note Item #" + qNum + " regarding '" + keyPhrase + "'");
            String correctAnswerText = (isTamil ? "குறிப்பிலிருந்து: " : "Fact: ") + seg;
            String explanation = isTamil ? ("குறிப்பு பகுதி " + qNum) : ("Note item " + qNum);

            List<String> options = new ArrayList<>();
            options.add(correctAnswerText);
            if (isTamil) {
                options.add("இது தவறான தகவல் " + qNum + "A.");
                options.add("இது தவறான தகவல் " + qNum + "B.");
                options.add("எதுவும் இல்லை.");
            } else {
                options.add("Incorrect distractor " + qNum + "A.");
                options.add("Incorrect distractor " + qNum + "B.");
                options.add("None of these.");
            }
            Collections.shuffle(options);
            int correctIndex = options.indexOf(correctAnswerText);

            questions.add(new Question(questionText, options, correctIndex, explanation));
        }
    }

    private String extractKeyPhrase(String sentence) {
        if (sentence == null || sentence.trim().isEmpty()) return "this topic";

        String trimmed = sentence.trim().replaceAll("[\\.\\!\\?\\,\\;]", "");
        String[] words = trimmed.split("\\s+");

        if (words.length <= 4) {
            return trimmed;
        }

        if (trimmed.contains(" is ")) {
            return trimmed.split(" is ", 2)[0].trim();
        } else if (trimmed.contains(" are ")) {
            return trimmed.split(" are ", 2)[0].trim();
        } else if (trimmed.contains(" என்பது ")) {
            return trimmed.split(" என்பது ", 2)[0].trim();
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(4, words.length); i++) {
            if (i > 0) sb.append(" ");
            sb.append(words[i]);
        }
        return sb.toString();
    }
}
