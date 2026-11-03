package dev.myfrei.repopilot.cache;

import dev.myfrei.repopilot.rag.RagExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=CachedQueryPlanController.class)
public class CachedQueryExceptionHandler extends RagExceptionHandler { }
