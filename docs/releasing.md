# Выпуск Android-релиза

Релиз вручную запускается в GitHub: **Actions → Publish Android release → Run workflow**. Выберите ветку с нужным исходным кодом, укажите новый тег (например, `v1.0.0`) и отметьте prerelease, если это предварительная версия. Workflow соберёт подписанный APK, создаст GitHub Release с этим тегом и приложит APK вместе с SHA-256.

## Однократная настройка подписи

Для публикации нужен постоянный Android keystore. Не создавайте новый ключ для каждого релиза: Android принимает обновление только с той же подписью, что и у установленного приложения.

Создайте следующие **Actions repository secrets** в настройках репозитория:

| Secret | Значение |
| --- | --- |
| `ANDROID_RELEASE_KEYSTORE_BASE64` | Файл keystore, закодированный в Base64 одной строкой |
| `ANDROID_RELEASE_KEYSTORE_PASSWORD` | Пароль keystore |
| `ANDROID_RELEASE_KEY_ALIAS` | Alias ключа внутри keystore |
| `ANDROID_RELEASE_KEY_PASSWORD` | Пароль ключа |

Для Linux/macOS скопируйте Base64-значение keystore командой `base64 -w 0 gtamine-release.jks` (на macOS: `base64 gtamine-release.jks | tr -d '\n'`). В PowerShell используйте `[Convert]::ToBase64String([IO.File]::ReadAllBytes('gtamine-release.jks'))`. Добавьте именно полученную строку как `ANDROID_RELEASE_KEYSTORE_BASE64`.

Ключ должен быть тем же, которым уже подписывались опубликованные версии GTAMINE Mobile. Если это первый релиз, сохраните keystore в защищённом месте и используйте его для всех последующих выпусков. Не добавляйте keystore или пароли в Git.

Необязательный секрет `CURSEFORGE_API_KEY` передаётся сборке для функций CurseForge; без него сборка использует предусмотренный проектом режим без API-ключа.

## Для пользователей

Инструкция по установке автоматически включается в описание каждого GitHub Release. Она объясняет скачивание APK, разрешение установки для браузера/файлового менеджера и обновление приложения.
