# Сборка VolKVN (форк v2rayNG)

Пошаговая инструкция для репозитория с каталогом **`V2rayNG/`** (модуль приложения Android).

---

## Windows (проверялось при разработке)

### 1. JDK 17

Установите **Temurin 17**, **Microsoft Build of OpenJDK 17** или JDK из Android Studio. Проверка:

```bat
java -version
```

Должна отображаться версия **17** (не обязательно именно эта сборка, главное — major version 17).

Убедитесь, что `java` доступен в `PATH`, либо задайте **`JAVA_HOME`** на корень JDK (например `C:\Program Files\Eclipse Adoptium\jdk-17.x.x`).

### 2. Android SDK

Нужен **полный** SDK, не только `platform-tools`:

- через **Android Studio**: *Settings → Languages & Frameworks → Android SDK* — установите **Android 15 (API 36)** (или тот **compileSdk**, что указан в `V2rayNG/app/build.gradle.kts`, сейчас это **36**), **Android SDK Build-Tools**, **Android SDK Command-line Tools**;
- либо через **`sdkmanager`** из cmdline-tools: пакеты вроде `platforms;android-36`, `build-tools;...`, `cmdline-tools;latest`.

Пример принятия лицензий (из `cmdline-tools\latest\bin`):

```bat
sdkmanager --licenses
```

### 3. Файл `V2rayNG/local.properties`

В каталоге **`V2rayNG`** создайте (или отредактируйте) файл **`local.properties`**. Укажите путь к SDK **в стиле Java Properties** (слэши можно как `/`, так и `\\`):

```properties
sdk.dir=C:/Android/sdk
```

Подставьте свой реальный путь к Android SDK (часто `C:\Users\<имя>\AppData\Local\Android\Sdk` при установке через Android Studio).

### 4. Нативная библиотека `libv2ray.aar`

В репозитории **нет** файла **`libv2ray.aar`** (он не хранится в git).

1. Откройте релизы **[AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite/releases)**.
2. Скачайте артефакт **`libv2ray.aar`** (версия должна быть совместима с ожиданиями проекта; при сомнениях ориентируйтесь на тег submodule у upstream v2rayNG или последний стабильный релиз от **2dust**).
3. Положите файл в:

   **`V2rayNG/app/libs/libv2ray.aar`**

   Каталог `libs` создайте вручную, если его нет.

### 5. Сборка из каталога проекта

Откройте **cmd** или **PowerShell**, перейдите в **`V2rayNG`**:

```bat
cd C:\путь\к\репозиторию\babukvn\V2rayNG
```

Отладочная сборка (вариант **Play Store** flavor):

```bat
.\gradlew.bat assemblePlaystoreDebug
```

Первый запуск может долго качать зависимости Gradle.

### 6. Где лежит APK

Обычно:

**`V2rayNG/app/build/outputs/apk/playstore/debug/`**

— файл вида `app-playstore-debug.apk` (имя может отличаться в зависимости от flavor).

Установка на устройство с включённой отладкой по USB:

```bat
adb install -r app\build\outputs\apk\playstore\debug\app-playstore-debug.apk
```

(путь и имя файла проверьте в `outputs\apk\...` после сборки.)

### Типичные проблемы на Windows

| Симптом | Что проверить |
|--------|----------------|
| `SDK location not found` | `sdk.dir` в `V2rayNG/local.properties` |
| Ошибки про `libv2ray` / отсутствующий AAR | наличие `V2rayNG/app/libs/libv2ray.aar` |
| Несовместимая версия Java | JDK **17**, переменные `JAVA_HOME` / `PATH` |
| Медленная первая сборка | нормально; включите кэш Gradle при повторных сборках |

---

## Linux и macOS

Ниже — **логичная последовательность шагов по аналогии с Windows**, но **на Linux/macOS эта цепочка в рамках данного проекта не проверялась** — возможны отличия в путях, правах на файлы и оболочке.

1. Установите **JDK 17** (пакетный менеджер дистрибутива, SDKMAN, Homebrew на macOS и т.д.).
2. Установите **Android SDK** (часто через Android Studio или standalone cmdline-tools).
3. В **`V2rayNG/local.properties`** задайте, например:

   ```properties
   sdk.dir=/home/USER/Android/Sdk
   ```

4. Поместите **`libv2ray.aar`** в **`V2rayNG/app/libs/`** (как в разделе для Windows).
5. Из каталога **`V2rayNG`** выполните:

   ```bash
   chmod +x gradlew
   ./gradlew assemblePlaystoreDebug
   ```

APK ожидается в том же относительном пути: **`app/build/outputs/apk/playstore/debug/`**.

---

## Дополнительно

- Релизная подпись (`release`) настраивается отдельно (keystore) — не описано здесь.
- Flavor **F-Droid** в проекте может использовать `applicationIdSuffix` — смотрите `build.gradle.kts`.
