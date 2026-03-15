# DEVELOP.md — How to build and run Tungsten

## Prerequisites

| Requirement | Version |
|---|---|
| Java (JDK) | 21 |
| Gradle | bundled via `gradlew` (не нужно ставить отдельно) |
| Git | любая актуальная версия |
| Internet | нужен на первый build (~1 GB: MC + Fabric + nether-pathfinder) |

---

## Структура папок (обязательно)

```
<любая папка>/
├── Tungsten/               ← mod source, сюда запускаем gradle
└── baritone_altoclef/      ← отдельный репо, должен лежать РЯДОМ с Tungsten/
    ├── baritone/           ← git submodule (cabaletta/baritone, ветка 1.21)
    ├── patches/            ← altoclef-патчи для baritone
    ├── maven/              ← сгенерированный maven-репо (НЕ в git, нужно создать)
    └── apply_patches.bat
```

`Tungsten/build.gradle` читает Baritone из `../baritone_altoclef/maven` —
папки должны быть **строго рядом** друг с другом.

---

## Шаг 1 — Настройка baritone_altoclef (один раз)

`baritone_altoclef` — это отдельный репо с patched Baritone.
Папка `maven/` **не хранится в git** и генерируется один раз вручную.

```bash
# Клонируй рядом с Tungsten/ (уже есть — пропусти если папка уже скачана)
git clone https://github.com/MiranCZ/baritone_altoclef

# Перейди в папку
cd baritone_altoclef

# Скачай submodule (папка baritone/ появится)
git submodule update --init

# Переключи submodule на ветку MC 1.21
cd baritone
git checkout origin/1.21

# Вернись в baritone_altoclef и примени altoclef-патчи
cd ..
apply_patches.bat       # Windows
# На Linux/Mac: cd baritone && git apply ../patches/*.patch

# Собери Baritone и установи в локальный maven (~/.m2)
cd baritone
./gradlew :fabric:publishToMavenLocal   # Linux/Mac
gradlew.bat :fabric:publishToMavenLocal  # Windows
```

После сборки Baritone лежит в:

```text
~/.m2/repository/cabaletta/baritone-unoptimized-fabric/1.21/
```

Теперь скопируй артефакты в папку `maven/`:

**Windows (PowerShell):**

```powershell
$src = "$env:USERPROFILE\.m2\repository\cabaletta\baritone-unoptimized-fabric\1.21"
$dst = "..\maven\cabaletta\baritone-unoptimized-fabric\1.21"
New-Item -ItemType Directory -Force $dst
Copy-Item "$src\*.jar","$src\*.pom" $dst
```

**Linux/Mac:**

```bash
SRC=~/.m2/repository/cabaletta/baritone-unoptimized-fabric/1.21
DST=../maven/cabaletta/baritone-unoptimized-fabric/1.21
mkdir -p $DST
cp $SRC/*.jar $SRC/*.pom $DST/
```

Итого в `baritone_altoclef/maven/` должно появиться:

```text
maven/cabaletta/baritone-unoptimized-fabric/1.21/
    baritone-unoptimized-fabric-1.21.jar
    baritone-unoptimized-fabric-1.21.pom
```

> **Быстрый вариант:** если у кого-то уже есть рабочая папка `maven/` —
> просто скопируй её целиком в `baritone_altoclef/`. Это 2 файла ~600KB.

---

## Шаг 2 — Запуск Tungsten

```bash
cd Tungsten
./gradlew runClient         # Linux/Mac
gradlew.bat runClient       # Windows
```

**Первый запуск** скачает (~1 GB):

- Minecraft 1.21 + нативные библиотеки (Mojang CDN)
- Fabric Loader 0.16.2 + Fabric API (FabricMC maven)
- `dev.babbaj:nether-pathfinder:1.5` (babbaj.github.io/maven)

`baritone-unoptimized-fabric:1.21` берётся **локально** из `../baritone_altoclef/maven`.

После первого скачивания всё кешируется в `~/.gradle/caches/`.

---

## Сборка JAR (деплой на сервер/клиент)

```bash
cd Tungsten
./gradlew build
```

Результат: `build/libs/tungsten-fabric-ALPHA-1.6.0-1.21compat.jar`

Кладётся в `.minecraft/mods/` как обычный Fabric мод.
Baritone и nether-pathfinder уже **bundled** внутри JAR (через `include` в build.gradle).

---

## Конфиг мода

Создаётся при первом запуске: `.minecraft/config/tungsten.json`

```json
{
  "driftCorrectionEnabled": false,
  "driftThreshold": 0.5,
  "verboseDebugLogging": false,
  "baritoneEnabled": true
}
```

Менять через команды в чате (автосохранение):
```
;settings baritone true/false       — параллельный Baritone fallback
;settings verboseDebug true/false   — verbose логи в консоль
;settings driftCorrection true/false
;settings driftThreshold 0.5
```

---

## Команды мода

| Команда | Описание |
|---|---|
| `;followPlayer <name>` | Следовать за игроком (push mode) |
| `;followPlayer <name> <radius>` | Следовать, держась на radius блоков |
| `;stop` | Остановить всё |
| `;goto <x> <y> <z>` | Идти к координатам |

---

## Архитектура pathfinding

```
dist < 6 + LOS  →  SUPER_FAST: прямой спринт + WindMouse поворот (~60 FPS)
dist ≥ 6        →  Tungsten A* (всегда, первичный)
                   + Baritone GoalFollowEntity (fallback, пока Tungsten ищет)
```

- Tungsten находит путь → executor стартует → Baritone немедленно останавливается
- После завершения executor'а Baritone не стартует ещё 3 сек (cooldown = 60 тиков)
- `baritoneEnabled=false` → только Tungsten A*, без fallback
