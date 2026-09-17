package com.quizforge.service;

import com.quizforge.dto.QuizRequest;
import com.quizforge.dto.QuizResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class QuizServiceTest {

    private QuizService quizService;

    @BeforeEach
    public void setUp() {
        quizService = new QuizService();
    }

    @Test
    public void testGenerateQuizCount5() {
        String notes = "Photosynthesis is the process by which green plants convert light energy into chemical energy. " +
                "Chlorophyll is the green pigment in plants that absorbs light. " +
                "Stomata are small pores on leaves that allow gas exchange. " +
                "Glucose is produced during photosynthesis to nourish the plant. " +
                "Oxygen is released as a byproduct into the atmosphere.";

        QuizRequest request = new QuizRequest(notes, 5, "Medium");
        QuizResponse response = quizService.generateQuiz(request);

        assertTrue(response.isSuccess(), "Quiz generation should succeed");
        assertNotNull(response.getQuestions(), "Questions list should not be null");
        assertEquals(5, response.getQuestions().size(), "Should return EXACTLY 5 questions when 5 requested");
    }

    @Test
    public void testGenerateQuizCount10() {
        String notes = "The Central Processing Unit (CPU) executes instructions. " +
                "RAM is volatile primary memory used for fast data access. " +
                "Hard Disk Drives provide persistent secondary storage. " +
                "The motherboard connects all hardware components together. " +
                "The Operating System manages hardware resources and user applications. " +
                "GPUs specialize in parallel processing for graphics and AI models.";

        QuizRequest request = new QuizRequest(notes, 10, "Medium");
        QuizResponse response = quizService.generateQuiz(request);

        assertTrue(response.isSuccess(), "Quiz generation should succeed");
        assertEquals(10, response.getQuestions().size(), "Should return EXACTLY 10 questions when 10 requested");
    }

    @Test
    public void testGenerateQuizCount15() {
        String notes = "Java is an object-oriented programming language. " +
                "The JVM executes Java bytecode across operating systems. " +
                "Garbage Collection automatically manages heap memory. " +
                "Spring Boot simplifies building web applications and microservices.";

        QuizRequest request = new QuizRequest(notes, 15, "Medium");
        QuizResponse response = quizService.generateQuiz(request);

        assertTrue(response.isSuccess(), "Quiz generation should succeed");
        assertEquals(15, response.getQuestions().size(), "Should return EXACTLY 15 questions when 15 requested");
    }

    @Test
    public void testGenerateQuizCount20() {
        String notes = "Python is an interpreted high-level programming language. " +
                "Data structures include lists, tuples, dictionaries, and sets. " +
                "Functions are defined using the def keyword.";

        QuizRequest request = new QuizRequest(notes, 20, "Medium");
        QuizResponse response = quizService.generateQuiz(request);

        assertTrue(response.isSuccess(), "Quiz generation should succeed");
        assertEquals(20, response.getQuestions().size(), "Should return EXACTLY 20 questions when 20 requested");
    }

    @Test
    public void testGenerateQuizTamilNotes() {
        String notes = "கணிப்பொறி என்பது தரவுகளை செயலாக்கும் ஒரு மின்னணு சாதனமாகும். " +
                "CPU என்பது மத்திய செயலகம் ஆகும். " +
                "RAM என்பது தற்காலிக நினைவகம் ஆகும். " +
                "வன்பொருள் மற்றும் மென்பொருள் கணிப்பொறியின் இரு முக்கிய கூறுகள்.";

        QuizRequest request = new QuizRequest(notes, 5, "Medium");
        QuizResponse response = quizService.generateQuiz(request);

        assertTrue(response.isSuccess(), "Quiz generation should succeed for Tamil notes");
        assertEquals(5, response.getQuestions().size(), "Should return EXACTLY 5 questions for Tamil notes");
    }
}
