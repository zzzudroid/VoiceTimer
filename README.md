# VoiceTimer

Android-таймер обратного отсчёта с голосовым управлением на русском языке.

## Возможности

- **Голосовой ввод** — скажи «Слей воду 20 минут» или «5 минут», таймер запустится сам
- **Офлайн-распознавание** — работает без интернета (Vosk, модель ~45 МБ вшита в APK, разрешение `INTERNET` не требуется)
- **Фоновая работа** — сигнал сработает даже при выключенном экране
- **Зацикленный сигнал** — звонит до ручной остановки кнопкой «Стоп»
- **Snooze** — отложи сигнал на 30 сек / 1 мин / 5 мин, после чего он повторится
- **Метка действия** — «Слей воду» отображается над таймером и в уведомлении

## Стек

- Kotlin + Jetpack Compose
- [Vosk Android](https://alphacephei.com/vosk/) — офлайн STT
- ForegroundService + WakeLock
- Android API 26+

## Сборка

```bash
# Требуется: JDK 17–21, Android SDK (compileSdk 35)
./gradlew assembleDebug          # на Windows: gradlew.bat assembleDebug

# Установить на подключённый телефон
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Модель Vosk (~88 МБ распакованная) **не хранится в git** — она автоматически
скачивается и распаковывается в `app/src/main/assets/vosk-model-ru` задачей
`fetchVoskModel`, которая вызывается перед сборкой (`preBuild`). Подтянуть модель
вручную: `./gradlew fetchVoskModel`.

### Release

Подпись release читается из `keystore.properties` в корне проекта (файл в `.gitignore`,
в репозиторий не попадает). Пример содержимого:

```properties
storeFile=app/release.keystore
storePassword=...
keyAlias=voicetimer
keyPassword=...
```

```bash
./gradlew assembleRelease       # app/build/outputs/apk/release/app-release.apk
```

## Использование голосового ввода

| Что сказать | Результат |
|---|---|
| «5 минут» | 5:00 |
| «30 секунд» | 0:30 |
| «1 час 20 минут» | 1:20:00 |
| «полчаса» | 30:00 |
| «Слей воду двадцать минут» | Метка «Слей воду», 20:00 |
| «Поставь чайник 3 минуты» | Метка «Поставь чайник», 3:00 |
