package dev.myfrei.repopilot.tasks;

import java.util.*;

/** Read-only fictional fixture catalog, reusable without Spring or an LLM. */
public final class TaskCatalog {
    private final List<Task> tasks=List.of(
        new Task("TASK-101","Единый порт локального запуска",Task.Status.OPEN,"Согласовать HTTP-порт 8091 в конфигурации и инструкции запуска TaskShelf.",List.of(
            "Локальный HTTP endpoint использует порт 8091.","Инструкция запуска показывает ту же настройку.","Проверка конфигурации обнаруживает несовпадение портов.")),
        new Task("TASK-102","Пагинация списка задач",Task.Status.IN_PROGRESS,"Добавить ограниченную пагинацию и фильтр статуса для списка задач.",List.of(
            "Порядок задач стабилен по ID.","Лимит страницы ограничен и проверяется до поиска.","Фильтр статуса применяется до пагинации.")),
        new Task("TASK-103","Контроль бюджета контекста",Task.Status.OPEN,"Перед отправкой запроса оценивать полный prompt и сохранять резерв ответа модели.",List.of(
            "Оценка включает системную инструкцию и выбранные источники.","Сокращаются только полные пары истории.","Переполнение возвращает явный код до model call.")),
        new Task("TASK-104","Диагностика SQLITE_BUSY",Task.Status.OPEN,"Показывать понятную ошибку блокировки SQLite и не выдавать незавершённую запись за успешную.",List.of(
            "Сбой блокировки не создаёт completed turn.","Пользователь получает безопасное сообщение без SQL и путей.","Сценарий блокировки воспроизводится интеграционным тестом.")),
        new Task("TASK-105","Проверка health endpoint",Task.Status.DONE,"Зафиксировать простой health endpoint для локального учебного TaskShelf.",List.of(
            "Успешный ответ имеет HTTP 200.","Проверка не требует LLM и PostgreSQL.","Формат ответа проверяется контрактным тестом.")));
    private final Map<String,Task> byId;
    private final ProjectInfo project;
    public TaskCatalog() {
        var map=new LinkedHashMap<String,Task>(); tasks.forEach(task->map.put(task.id(),task)); byId=Collections.unmodifiableMap(map);
        project=new ProjectInfo("taskshelf","TaskShelf","Вымышленный read-only каталог учебных задач; инструменты не изменяют репозиторий.",
                List.of("Java 25","Spring Boot 4.1.2","SQLite","Ollama"),tasks.stream().map(Task::id).toList());
    }
    public ProjectInfo projectInfo() { return project; }
    public TaskView getTask(String id) { return findTask(id).orElseThrow(()->new IllegalArgumentException("Unknown TaskShelf taskId")).toView(); }
    public Optional<Task> findTask(String id) { return id==null?Optional.empty():Optional.ofNullable(byId.get(id)); }
    public List<TaskView> listTaskViews() { return tasks.stream().map(Task::toView).toList(); }
}
