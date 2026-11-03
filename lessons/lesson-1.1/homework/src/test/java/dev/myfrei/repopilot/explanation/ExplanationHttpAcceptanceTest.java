package dev.myfrei.repopilot.explanation;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.http.MediaType;
import dev.myfrei.repopilot.chat.ApiExceptionHandler;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class ExplanationHttpAcceptanceTest {
 @Test void newRouteAcceptsGuidedModeAndReturnsTheModelAnswer() throws Exception {
  var service=mock(ExplanationService.class);when(service.explain(any())).thenReturn("Guided fixture");
  var mvc=MockMvcBuilders.standaloneSetup(new ExplanationController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
  mvc.perform(post("/api/explanations").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Q\",\"mode\":\"GUIDED\"}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value("Guided fixture"));
  verify(service).explain(new ExplanationRequest("Q",ExplanationMode.GUIDED));
 }
 @Test void unknownModeIs400BeforeCallingTheModel() throws Exception {
  var service=mock(ExplanationService.class);var mvc=MockMvcBuilders.standaloneSetup(new ExplanationController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
  mvc.perform(post("/api/explanations").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Q\",\"mode\":\"MAGIC\"}")).andExpect(status().isBadRequest());verifyNoInteractions(service);
 }
 @Test void invalidQuestionIsRejectedByBeanValidation() throws Exception {
  var service=mock(ExplanationService.class);var validator=new LocalValidatorFactoryBean();validator.afterPropertiesSet();
  try {
   var mvc=MockMvcBuilders.standaloneSetup(new ExplanationController(service)).setControllerAdvice(new ApiExceptionHandler()).setValidator(validator).build();
   mvc.perform(post("/api/explanations").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\" \"}")).andExpect(status().isBadRequest());verifyNoInteractions(service);
  }finally{validator.close();}
 }
}
