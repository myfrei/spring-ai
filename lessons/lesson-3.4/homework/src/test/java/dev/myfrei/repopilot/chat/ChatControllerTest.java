package dev.myfrei.repopilot.chat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerTest {

    private ChatService service;
    private MockMvc mvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        this.service = mock(ChatService.class);
        this.validator = new LocalValidatorFactoryBean();
        this.validator.afterPropertiesSet();
        this.mvc = MockMvcBuilders.standaloneSetup(new ChatController(this.service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(this.validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        this.validator.close();
    }

    @Test
    void returnsAnswerAsJson() throws Exception {
        when(this.service.ask("Что такое Java?")).thenReturn("Язык программирования.");

        this.mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"Что такое Java?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.answer").value("Язык программирования."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"message\":null}", "{\"message\":\"\"}", "{\"message\":\"   \"}"})
    void rejectsInvalidMessageBeforeCallingService(String body) throws Exception {
        this.mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));
        verifyNoInteractions(this.service);
    }

    @Test
    void returnsProblemDetailForMalformedJson() throws Exception {
        this.mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{broken-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"));
        verifyNoInteractions(this.service);
    }

    @Test
    void rejectsOversizedMessageBeforeCallingService() throws Exception {
        this.mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + "a".repeat(4_001) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(this.service);
    }

    @Test
    void returns503ProblemDetailInsteadOfSuccessfulAnswerOnModelFailure() throws Exception {
        when(this.service.ask("Вопрос")).thenThrow(new LlmUnavailableException(
                new RuntimeException("internal-provider-detail")));

        this.mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Вопрос\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.title").value("LLM unavailable"))
                .andExpect(jsonPath("$.detail").value(
                        "Локальная LLM не вернула ответ. Проверьте Ollama, модель и время ожидания."))
                .andExpect(jsonPath("$.answer").doesNotExist());
    }
}
