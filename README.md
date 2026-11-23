# GitSend

Приложение для Android: отправляет файлы с телефона в любой ваш репозиторий GitHub.

## Как собрать
1. Создайте новый репозиторий на GitHub и загрузите туда все файлы этого проекта
   (включая папку `.github`).
2. Откройте вкладку Actions: сборка запустится сама. Если нет, нажмите
   "Build APK" -> "Run workflow".
3. Когда сборка станет зелёной, откройте её и скачайте артефакт `GitSend-debug-apk`.
4. Распакуйте архив и установите `app-debug.apk`.

## Токен GitHub
GitHub -> Settings -> Developer settings -> Personal access tokens ->
Fine-grained tokens -> Generate new token.
- Repository access: выберите нужные репозитории
- Permissions -> Repository permissions -> Contents: Read and write

## Создание репозиториев
Для кнопки "Создать репозиторий" проще всего классический токен:
Tokens (classic) -> Generate new token -> галочка `repo`.
Fine-grained токен тоже подойдёт, но только с доступом "All repositories"
и правом Administration: Read and write.
