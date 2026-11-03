package dev.myfrei.repopilot.diagnostics;

import java.util.List;
import dev.myfrei.repopilot.evaluation.*;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import dev.myfrei.repopilot.diagnostics.DiagnosticReport.*;
public final class DiagnosticRules {
    public static final String VERSION = "diagnostic-rules-v1";
    private DiagnosticRules() { }
    public static Finding classify(Result row, EvaluationCase entry, Judgment judgment) {
        if (row.outcome() == Outcome.FAILED || row.outcome() == Outcome.CANCELLED) return finding(Category.EXECUTION_FAILURE, "Запрос не завершён успешно",
                List.of("Outcome=" + row.outcome(), "Код=" + row.code()), "Ошибка исполнения или проверки условий; качество завершённого ответа не определено.", "Проверить terminal/error stage trace и воспроизвести тот же код ошибки до настройки prompt.");
        if (entry == null) return incompatible("Case ID отсутствует в текущей разметке.");
        if (row.mode() == Mode.CHAT) return finding(Category.BASELINE_WITHOUT_DOCUMENTS, "CHAT без документов проекта",
                List.of("Документы проекта в этом режиме не прикладывались."), "Отсутствие факта о проекте ожидаемо для baseline; это не сбой retrieval.", "Сравнить тот же вопрос без истории с FULL_CONTEXT и RAG.");
        if (!entry.answerable()) {
            if (judgment != null && (judgment.answer() < 2 || Boolean.FALSE.equals(judgment.refusalCorrect()))) return finding(Category.UNSUPPORTED_ANSWER_REVIEW, "Проверить ответ без размеченного факта",
                    List.of("UNKNOWN: evidence coverage не применим.", "Есть ручная отрицательная/частичная оценка."), "Ответ мог содержать догадку или неверный отказ; одних счётчиков недостаточно.", "Проверить утверждения ответа по корпусу, зафиксировать отсутствующий факт и обоснованность отказа.");
            return judgedOrPending(judgment);
        }
        if (row.mode() == Mode.RAG) {
            if (row.retrievedEvidenceHit() == null) return finding(Category.DATA_OR_CONTEXT_REVIEW, "Нет подтверждения retrieval stage", List.of("retrievedEvidenceHit=null"), "Данных для разделения поиска и сборки недостаточно.", "Проверить совместимый dataset и повторить запрос с диагностикой стадий.");
            if (Boolean.FALSE.equals(row.retrievedEvidenceHit()) && Boolean.TRUE.equals(row.providedEvidenceHit())) return finding(Category.INCONSISTENT_EVIDENCE, "Сигналы retrieval и prompt противоречат друг другу",
                    List.of("retrievedEvidenceHit=false; providedEvidenceHit=true"), "Возможна ошибка разметки, snapshot или переноса источников.", "Сверить datasetVersion, IDs и raw sources до попытки обвинить модель.");
            if (Boolean.FALSE.equals(row.retrievedEvidenceHit())) return finding(Category.RETRIEVAL_MISS, "Размеченное свидетельство не найдено",
                    List.of("retrievedEvidenceHit=false", "Кандидатов=" + row.retrievedChunks()), "Возможны ошибки chunking, query embedding, фильтра, topK/threshold или самой разметки.", "Посмотреть кандидатов отдельно от генерации; менять один параметр на DEV и проверять ожидаемый фрагмент.");
            if (Boolean.TRUE.equals(row.retrievedEvidenceHit()) && Boolean.FALSE.equals(row.providedEvidenceHit())) return finding(Category.CONTEXT_DROPPED, "Свидетельство потеряно после поиска",
                    List.of("retrievedEvidenceHit=true; providedEvidenceHit=false", "Кандидатов=" + row.retrievedChunks() + "; источников в плане=" + row.sources().size()), "Вероятна потеря при отборе/бюджете, но причина требует проверки IDs и плана.", "Сверить selected markers и omitted/estimated/inputBudget; повторить с контролируемым бюджетом или меньшими чанками.");
        }
        if (!Boolean.TRUE.equals(row.providedEvidenceHit())) return finding(Category.DATA_OR_CONTEXT_REVIEW, "Недостаточно подтверждений о контексте",
                List.of("providedEvidenceHit=" + row.providedEvidenceHit()), "Сигнал отсутствует или не совпал excerpt; это ещё не доказательство ошибки модели.", "Сверить исходный документ, разметку, версию корпуса и фактическую сборку prompt.");
        if (judgment != null && (judgment.answer() < 2 || Boolean.FALSE.equals(judgment.refusalCorrect()))) return finding(Category.GENERATION_REVIEW, "Свидетельство в плане, ответ требует разбора",
                List.of("providedEvidenceHit=true", "Ручная оценка ответа=" + judgment.answer() + "/2"), "Кандидаты причины: инструкция, неоднозначность вопроса, генерация или ошибка ручной/цитатной разметки. Внутреннее решение модели не наблюдается.", "Прочитать фактический prompt и ответ; повторить один вопрос с одним изменением инструкции и вручную проверить результат.");
        if (judgment != null && judgment.citations() < 2) return finding(Category.CITATION_REVIEW, "Отдельно проверить ссылки и утверждения",
                List.of("Свидетельство в плане присутствует.", "Ручная оценка источников=" + judgment.citations() + "/2"), "Метки или привязка утверждений к источникам могут быть неверны при правильном факте.", "Сопоставить каждую метку с source content; не считать наличие source доказательством корректной цитаты.");
        return judgedOrPending(judgment);
    }
    public static Finding incompatible(String fact) { return finding(Category.INCOMPATIBLE_BASELINE, "Сначала проверить исходные данные", List.of(fact), "Текущая разметка не даёт совместимой основы для вывода.", "Сопоставить datasetVersion/corpusVersion; анализировать старый run с его исходными данными или выполнить новый прогон."); }
    private static Finding judgedOrPending(Judgment judgment) {
        if (judgment != null && judgment.citations() < 2) return finding(Category.CITATION_REVIEW, "Проверить источники отдельно", List.of("Ручная оценка источников ниже 2/2."), "Ответ мог ссылаться на неподтверждающий текст.", "Сверить утверждения и ссылки с фактическими sources.");
        return judgment == null ? finding(Category.MANUAL_REVIEW_NEEDED, "Нужна ручная оценка", List.of("Judgment отсутствует."), "Автоматические признаки не оценивают смысл ответа и цитат.", "Прочитать ответ и источники, сохранить отдельные оценки ответа, цитат и отказа.")
                : finding(Category.NO_DETECTED_ISSUE, "По текущим проверкам проблема не обнаружена", List.of("Есть положительная ручная оценка."), "Это локальный результат данного кейса, не гарантия для всех вопросов.", "Сохранить условия и повторить проверку на отложенном наборе без подбора параметров.");
    }
    private static Finding finding(Category category, String label, List<String> facts, String hypothesis, String next) { return new Finding(category, label, facts, hypothesis, next); }
}
