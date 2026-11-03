# Урок 1.3. Streaming, отмена и незавершённые ответы

- [Полная Markdown-лекция и рабочий пример](lecture/README.md).
- [Домашнее задание и каркас](homework/README.md).
- [Отчёт проверки](VERIFICATION.md).
- [Пример остановленного потока](Preview.jpg) — тестовые данные HTTP-стенда.
- [Предыдущий урок 1.2](../lesson-1.2/README.md).

В каждой папке собственные pom.xml, Maven Wrapper, package.json и lock-файл. Основа: Java 25, Spring Boot 4.1.2, Spring AI 2.0.2, Maven 3.9.11; UI-проверки — Node.js 24 и jsdom 27.4.0.

Новый `POST /api/chat/stream` возвращает SSE-события started/delta/completed/failed. Старый `POST /api/chat` оставлен для сравнения. UI читает POST через fetch, показывает частичный текст, умеет остановить запрос и повторить попытку.

Из lecture:

```bash
./mvnw package
npm ci
npm test
./mvnw spring-boot:run
```

При работающем Ollama откройте `http://127.0.0.1:8080/`. Подготовка модели — по 1.1. Автоматические проверки не требуют настоящих весов: в lecture проходят **53 Java- и 45 JavaScript-проверок**.

В homework полностью готова лекционная база: streaming, отмена, повтор и UI. Самостоятельное задание — ограничить вывод по Unicode code points в новом `/api/chat/limited-stream`, сохранив честное failed-событие и отмену upstream. Единственный TODO — `OutputQuota.apply`. Базовые 53 Java и 45 JS проходят; 7 новых Java-проверок сначала красные. Неизменённый полный набор проверен на временном решении: 60 Java + 45 JS.
