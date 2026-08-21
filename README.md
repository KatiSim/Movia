# Movia (Android) 🎬

[![Version](https://img.shields.io/badge/version-0.3.22-blue.svg)](https://github.com/KatiSim/Movia)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B%20(API%2026%2B)-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-2.1.0-purple.svg)](https://kotlinlang.org)
[![Compose BOM](https://img.shields.io/badge/Jetpack%20Compose-2025.01.01-brightgreen.svg)](https://developer.android.com/jetpack/compose)

**Movia** — современный мультимедийный плеер и клиент онлайн-кинотеатра для Android, построенный на базе современных стандартов Jetpack Compose Material 3 и Media3 ExoPlayer.

---

## ✨ Основные возможности (v0.3.22)

- 🎨 **Современный UI/UX на Jetpack Compose Material 3:**
  - Адаптивный дизайн для смартфонов и планшетов
  - Тёмная и светлая темы с кастомной палитрой
  - Жестовая навигация и эргономичные элементы управления

- 🎥 **Медиаплеер на базе Jetpack Media3 (ExoPlayer 1.9.3):**
  - Поддержка фонового воспроизведения и Picture-in-Picture (PiP)
  - Выбор аудиодорожек и субтитров
  - Настройка скорости воспроизведения и масштабирования видео (Fit / Crop)
  - Управление воспроизведением через системные уведомления и MediaSession

- 📚 **Каталог и поиск:**
  - Интеллектуальный поиск по названиям, актёрам и режиссёрам
  - Фильтры по жанрам, годам выпуска, рейтингу и типу контента
  - Карусели рекомендаций и персональные подборки

- 💾 **Медиатека и офлайн-режим:**
  - Сохранение истории просмотров и избранного (Room Database 2.8.4)
  - Поддержка офлайн-загрузок через Android WorkManager
  - Сохранение прогресса воспроизведения для каждого эпизода и фильма

---

## 🛠️ Стек технологий

- **Язык:** Kotlin 2.1
- **UI Framework:** Jetpack Compose (BOM 2025.01.01) + Material Design 3
- **Медиа:** AndroidX Media3 (ExoPlayer, UI, Session 1.9.3)
- **База данных:** AndroidX Room 2.8.4 (KAPT, Room KTX)
- **Хранилище настроек:** AndroidX DataStore Preferences 1.2.1
- **Фоновые задачи:** AndroidX WorkManager 2.11.0
- **Минимальная версия:** Android 8.0 (API 26)
- **Целевая версия:** Android 15 (API 35)

---

## 📦 Сборка и запуск

Сборка debug APK с помощью Gradle:
```bash
./gradlew assembleDebug
```

Сборка release APK:
```bash
./gradlew assembleRelease
```
