# Урок 5.10. Финальная задача: MCP → FAIL → patch → свежий PASS

- [Полная пошаговая лекция](lecture/README.md).
- [Бланк реального финального прогона](lecture/evaluation/Capstone-Experiment.md).
- [Самостоятельное ДЗ: переносимый аудит](homework/README.md).
- [Результаты проверки](VERIFICATION.md).
- [Предыдущий урок 5.9](../lesson-5.9/README.md).

Отдельный настоящий MCP-сервер выдаёт фиксированную задачу Stock Room. Две AI-стадии используют общий runtime Web/REPL: prepare со Skill и планом, затем readonly review. Между ними человек отдельно разрешает применение patch и новую Docker-проверку. Сервер связывает исходный FAIL, версии, факт применения, свежий PASS, чтения и MCP provenance в 15 обязательных gates.

DONE относится к фиксированному StockLedger contract. Это ограниченный воспроизводимый финал, с явными человеческими решениями и состояниями INCOMPLETE/STALE. TaskShelf другого модуля не выдаётся за источник требований Stock Room.

В homework добавляется отдельный bounded/redacted audit export. Готовые MCP, AI loop, проверку и gate повторять не требуется.
