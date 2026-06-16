# Архитектурный план VoiceTimer «по-взрослому»

**Дата:** 2026-06-16 · **Статус:** предложение (proposal) · **Связано с:**
[recognition-analysis.md](./recognition-analysis.md)

Документ описывает целевую архитектуру и **поэтапную** миграцию к ней без переписывания
«всё и сразу». Цель — превратить распознавание/повторы из хрупких регэкспов и синглтонов в
тестируемое, расширяемое ядро, не ломая работающее приложение.

---

## 1. Принципы

1. **Clean Architecture + правило зависимостей.** Зависимости направлены внутрь: UI → домен ←
   данные. Домен не знает про Android, Room, Compose и регэкспы.
2. **Домен — чистый Kotlin.** Модели, правила повтора и парсер живут в JVM-модуле, тестируются
   за миллисекунды без эмулятора.
3. **Порты и адаптеры.** Внешний мир (БД, AlarmManager, распознавание, календарь) скрыт за
   интерфейсами (`port`), реализации (`adapter`) подключаются через DI.
4. **Однонаправленный поток данных (UDF).** UI ← состояние ← ViewModel ← use-case ← репозиторий.
5. **Тесты — вперёд функциональности.** Любая правда о языке/повторах фиксируется тестом
   (golden corpus) до изменения кода.
6. **Инкрементальность (strangler).** Каждый шаг — отдельный PR, зелёные тесты, рабочее
   приложение. Никаких длинных веток.

**Не-цели:** не вводим слои/зависимости ради моды; не меняем продуктовое поведение без
покрытия тестами; не делаем multi-module там, где достаточно пакетов (см. §13 «right-sizing»).

---

## 2. Оценка текущего состояния

| Аспект | Сейчас | Проблема |
|--------|--------|----------|
| Структура | один модуль `:app`, всё вперемешку | слои смешаны, тяжело тестировать |
| Данные | `object ReminderStore` + JSON-файл | синглтон-состояние, нет абстракции, гонки |
| Настройки | `SharedPreferences` напрямую | разбросанный доступ |
| Повторы | `enum RecurrenceType` + одно число | не выразить наборы дней/число месяца/конец серии |
| Парсер | монолитный `parse()` на регэкспах | хрупкий, чувствителен к порядку, не расширяется |
| Время | `java.util.Calendar` | многословно, ошибки с DST/месяцами |
| Распознавание | `VoiceRecognizer` со «склейкой» движков | откат ненадёжный, выбор движка разовый |
| Планирование | прямой `AlarmManager` | нет абстракции, перепланирование только на boot |
| DI | ручные синглтоны | связанность, нельзя подменить в тесте |

Плюс текущего — простота. Минус — потолок развития достигнут: каждая новая форма языка или
тип повтора множит риск регрессий.

---

## 3. Целевая модульная структура

```
:app                  — точка входа, DI-граф (Hilt), навигация, MainActivity
:core:common          — Result, Clock/TimeProvider, утилиты (чистый Kotlin)
:core:designsystem    — Compose-тема, переиспользуемые компоненты
:domain               — ЧИСТЫЙ Kotlin: модели, RecurrenceEngine, NLP-порт, use-cases, порты
:data                 — Room, DataStore, BackupService, реализации репозиториев
:core:nlp             — конвейер разбора фразы (адаптер доменного порта Parser)
:core:speech          — распознавание речи (cloud/vosk strategy + координатор)
:core:scheduling      — AlarmScheduler, BootRescheduler, WorkManager
:feature:reminders    — ViewModel + Compose-экраны (напоминания, настройки, бэкап)
```

**Правило зависимостей:** `:domain` не зависит ни от кого. `:data`, `:core:*`, `:feature:*`
зависят от `:domain`. `:app` связывает всё через DI. Циклы запрещены (проверяется модульной
границей Gradle).

---

## 4. Слои и поток данных

```
┌─────────────────────────────────────────────────────────────┐
│ PRESENTATION  :feature:reminders                             │
│   Compose UI  ──events──▶ ViewModel ──state(StateFlow)──▶ UI │
└───────────────────────────────┬─────────────────────────────┘
                                │ вызывает use-cases
┌───────────────────────────────▼─────────────────────────────┐
│ DOMAIN  :domain  (чистый Kotlin, без Android)                │
│   UseCases: AddReminder, ParsePhrase, RestoreBackup, …       │
│   Services: RecurrenceEngine, PhraseParser (порт)            │
│   Model:    Reminder, RecurrenceRule, ParsedReminder         │
│   Ports:    ReminderRepository, AlarmScheduler, SpeechEngine,│
│             SettingsRepository, BackupCodec                  │
└───────┬───────────────────────────────────────────┬─────────┘
        │ реализуют порты                            │
┌───────▼─────────┐  ┌───────────────┐  ┌────────────▼─────────┐
│ :data           │  │ :core:speech  │  │ :core:scheduling     │
│ Room, DataStore │  │ cloud / vosk  │  │ AlarmManager + Work  │
│ BackupService   │  │ координатор   │  │ Manager              │
└─────────────────┘  └───────────────┘  └──────────────────────┘
```

---

## 5. Доменное ядро: движок повторов

Заменяем `enum + int` на RRULE-подобную модель (подмножество iCalendar RFC 5545). Используем
`java.time` — на `minSdk 26` он доступен **нативно**, без desugaring.

```kotlin
// :domain
enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

sealed interface RecurrenceEnd {
    data object Never : RecurrenceEnd
    data class Count(val times: Int) : RecurrenceEnd
    data class Until(val date: LocalDate) : RecurrenceEnd
}

data class RecurrenceRule(
    val freq: Frequency,
    val interval: Int = 1,                       // «каждые N …»
    val byWeekday: Set<DayOfWeek> = emptySet(),  // «по будням», «пн/ср/пт»
    val byMonthDay: Int? = null,                 // «5 числа»
    val bySetPos: Int? = null,                   // «второй вторник», «последняя суббота» (-1)
    val end: RecurrenceEnd = RecurrenceEnd.Never
) {
    companion object { val NONE: RecurrenceRule? = null }  // разовое = отсутствие правила
}

interface RecurrenceEngine {
    // Первое срабатывание не раньше [from] с учётом якорей (день недели/число/часть суток)
    fun firstOccurrence(rule: RecurrenceRule, from: LocalDateTime, defaultTime: LocalTime): LocalDateTime
    // Следующее срабатывание строго после [after]
    fun nextOccurrence(rule: RecurrenceRule, after: LocalDateTime): LocalDateTime?
}
```

Движок — **чистая функция от модели и времени**, поэтому тестируется таблицей кейсов.
Закрывает: наборы дней (R1), число месяца (R2), якорь дня недели (R3/P1), порядковый день
(R10), конец серии (R9), 29–31 число с корректным clamp (R7).

---

## 6. Доменное ядро: парсер как конвейер

Монолитный `parse()` заменяется **конвейером с разметкой ролей** — отсюда независимость от
порядка слов (W1–W3) и лёгкая расширяемость.

```kotlin
// :domain (порт) + :core:nlp (реализация)
interface PhraseParser {
    fun parse(raw: String, now: LocalDateTime, settings: ScheduleSettings): ParseResult
}

// Конвейер :core:nlp
//   Normalizer → Tokenizer → [SlotExtractor*] → SlotResolver → TextBuilder
interface SlotExtractor {                       // каждый помечает свои токены ролью
    fun extract(tokens: List<Token>): List<Slot> // Slot = (role, value, consumedSpan)
}
// Экстракторы: Recurrence, AbsoluteDate, RelativeDay, Weekday, ClockTime, Duration, DayPart
```

**Идея:** экстракторы независимо размечают токены типизированными слотами (дата/время/повтор/
длительность). `SlotResolver` собирает из слотов итог по семантике, а не по тому, кто первый
совпал; конфликты решаются приоритетом ролей, а не позицией в строке. `TextBuilder` берёт
**неразмеченные** (содержательные) токены, отбрасывая ведущие императивы «напомни/поставь».

Преимущества: каждый экстрактор тестируется изолированно; лексиконы (дни, месяцы, числа)
выносятся в данные; добавить «к 9 часам» или «по будням» — это новый экстрактор/правило, а не
правка 300-строчной функции.

---

## 7. Распознавание речи

```kotlin
// :domain port
interface SpeechEngine {
    val id: EngineId                       // CLOUD | VOSK
    suspend fun isAvailable(): Boolean
    fun sessions(): Flow<RecognitionEvent> // Partial | Final | Error | Engine
}
```

`:core:speech` содержит `CloudEngine`, `VoskEngine` и **`RecognitionCoordinator`** —
конечный автомат, который:

1. выбирает движок по **валидной** сети (`NET_CAPABILITY_VALIDATED`, не просто наличие) — S2;
2. при сетевой ошибке облака откатывается на Vosk **в том числе после** частичного
   результата, продолжая с накопленного текста (S1, S4);
3. в непрерывном режиме пересматривает движок между фразами при смене сети (S3).

---

## 8. Персистентность и миграция данных

1. **Репозиторий вместо синглтона.** `ReminderRepository` (порт в `:domain`) →
   `RoomReminderRepository` (в `:data`). UI и use-cases больше не трогают файлы напрямую.
2. **Room вместо JSON-файла.** Сущности + `TypeConverter` для `RecurrenceRule`; миграции
   схемы версионируются и тестируются (`MigrationTestHelper`).
3. **DataStore вместо SharedPreferences** для `ScheduleSettings` (типобезопасно, асинхронно).
4. **Бэкап v2.** `BackupCodec` (порт) сериализует доменную модель через `kotlinx.serialization`;
   `BackupManager.VERSION` 1→2 с чтением старого формата (мягкая миграция). SAF-поток
   сохраняется как есть.
5. **Одноразовый импортёр** `files/reminders.json` → Room при первом запуске новой версии.
   Перед миграцией приложение само делает резервную копию (механизм уже есть) — безопасный
   откат.

---

## 9. Планирование (alarms)

```kotlin
interface AlarmScheduler {                 // :domain port → :core:scheduling adapter
    fun schedule(reminder: Reminder)
    fun cancel(id: Long)
    fun rescheduleAll(reminders: List<Reminder>)
}
```

- Точные будильники — `setExactAndAllowWhileIdle` за абстракцией; проверка разрешения
  `canScheduleExactAlarms`.
- **WorkManager** как страховочная сеть: периодический воркер сверяет запланированное с БД и
  доводит расхождения (надёжнее, чем полагаться только на `BOOT_COMPLETED`).
- Перепланирование при загрузке/восстановлении — единая точка `rescheduleAll`.

---

## 10. Технологический стек

| Слой | Выбор | Зачем |
|------|-------|-------|
| Асинхронность | Coroutines + Flow | стандарт, уже частично используется |
| Время | `java.time` | корректность DST/месяцев, доступно на minSdk 26 |
| БД | Room | миграции, запросы, тестируемость |
| Настройки | DataStore | типобезопасно, асинхронно |
| Сериализация | kotlinx.serialization | бэкап без ручного JSON |
| DI | Hilt | подмена портов в тестах, развязка |
| Тесты | JUnit + Turbine + Robolectric | домен на JVM, Flow и Android-части |

---

## 11. Стратегия тестирования

1. **Домен (быстрые JVM-тесты, 90% ценности):** `PhraseParser` — golden corpus из
   recognition-analysis (P*/R*/W*); `RecurrenceEngine` — таблица «правило × now → срок».
2. **Данные:** Room in-memory; **тесты миграций** схемы и импортёра JSON→Room; round-trip
   бэкапа v1→v2.
3. **ViewModel:** корутиновые тесты + Turbine на эмиссии состояния.
4. **UI:** минимальные Compose-тесты на ключевые экраны.
5. **CI-гейт:** `./gradlew testDebugUnitTest` обязателен; красный корпус блокирует merge.

---

## 12. План миграции (strangler, по шагам)

Каждый шаг — отдельный PR, обратная совместимость, зелёные тесты.

1. **Корпус-тесты (Фаза 0).** Зафиксировать текущее поведение `parse()` golden-тестами — сеть
   безопасности до любых правок.
2. **Выделить `:domain`.** Перенести модели и интерфейс `PhraseParser`/`ReminderRepository`,
   обернув **текущие** реализации, без смены поведения.
3. **Repository-фасад над JSON.** Спрятать `ReminderStore` за `ReminderRepository`; UI/VM —
   только через порт.
4. **Движок повторов.** Ввести `RecurrenceRule` + `RecurrenceEngine`, миграция данных v2,
   якоря первого срабатывания. Закрывает R1–R3, R7, R9, R10.
5. **NLP-конвейер.** Заменить тело `parse()` конвейером за тем же портом; корпус гарантирует
   паритет и фиксирует исправления W1/W2, P1–P4.
6. **Room + DataStore + импортёр.** Перевести хранение; бэкап v2 на kotlinx.serialization.
7. **Speech-координатор и scheduling-порт.** Надёжный откат (S1–S4), WorkManager-страховка.
8. **Hilt и финальная модуляризация.** Вводить разбиение на модули по мере стабилизации, не
   раньше.

---

## 13. Right-sizing: что реально нужно этому приложению

Полный enterprise-стек для личного приложения — риск over-engineering. Честная приоритизация:

- **Обязательно (высокая отдача):** `:domain` как чистый Kotlin-пакет, golden-тесты,
  `RecurrenceEngine`, NLP-конвейер, `java.time`. Это лечит первопричины из анализа.
- **Желательно:** Repository-порт, Room, бэкап v2, надёжный speech-координатор.
- **По мере роста (не сразу):** полноценное разбиение на Gradle-модули и Hilt. На старте
  достаточно **пакетов** `domain/`, `data/`, `feature/` в одном модуле с тем же правилом
  зависимостей — границы соблюдаются дисциплиной и тестами, а multi-module вводится, когда
  время сборки/команда этого потребуют.

**Рекомендуемый старт:** шаги 1, 2, 4, 5 (домен + тесты + движок повторов + конвейер) —
максимум ценности при умеренном риске; Room/Hilt/модули — следующей итерацией.

---

## 14. Ключевые решения (ADR-кратко)

| # | Решение | Почему | Альтернатива (отклонена) |
|---|---------|--------|--------------------------|
| 1 | Clean Architecture, домен-порты | тестируемость, развязка | оставить синглтоны — потолок развития |
| 2 | `java.time` вместо `Calendar` | корректность, читаемость | Calendar — многословен, баги с месяцами |
| 3 | RRULE-подобная модель повтора | выражает наборы/число/конец | enum+int — недостаточно выразителен |
| 4 | NLP-конвейер вместо моно-regex | порядок слов, расширяемость | патчить регэкспы — рост регрессий |
| 5 | Room вместо JSON-файла | миграции, запросы, тесты | файл — ок для MVP, но без миграций |
| 6 | Hilt (позже) | подмена в тестах | ручной DI — связанность |

---

## 15. Definition of Done

- [ ] Golden-корпус парсера зелёный; все 🔴-кейсы анализа закрыты.
- [ ] `RecurrenceEngine` покрыт таблицей кейсов (дни недели, число месяца, 29–31, конец серии).
- [ ] Домен не зависит от Android (проверяется отсутствием Android-импортов в `:domain`).
- [ ] Миграция данных v1→v2 покрыта тестом; бэкап делается перед миграцией.
- [ ] Поведение существующих фич не изменилось без явной фиксации тестом.
- [ ] `CHANGELOG.md` обновлён, сборка и ручная проверка на устройстве пройдены.
