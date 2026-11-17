# Jarvis Pro — релиз

**Версия:** 1.6.0-pro (`versionCode` 31) · `applicationId` `com.jarvis.pro`

## Перед магазином

1. JDK 17: `export JAVA_HOME=…/java-17-openjdk-amd64`
2. Подпись: скопируйте `keystore.properties.example` → `keystore.properties`, положите `.jks` рядом с `android/`.
3. Сборка:
   - `./gradlew :app:assembleRelease` — APK в `app/build/outputs/apk/release/`
   - без `keystore.properties` release соберётся, но **без** вашей подписи (не для магазина)
4. Smoke на телефоне: активация, «Джарвис», погода/музыка в виджетах, память, сценарии, тема → виджеты перекрашиваются.

## Что закрыто для релиза

- Консоль разработчика только в **debug** (нет в main manifest / скрыта в Pro UI)
- Память: BrainGraph + CompanionMemory в одном списке
- Настройки Pro ≈ classic «Ещё»
- Виджеты рабочие и в теме Pro
- CHANGELOG обновлён

## Не делать в release-сборке

- Не включать `-PvoiceClips=true` для магазина
- Не экспортировать DebugCommandReceiver (он только в `src/debug`)
