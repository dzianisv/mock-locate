# MockLocate

Android-приложение для мока геолокации. Тапаете точку на карте (OpenStreetMap,
без API-ключей), жмёте «Старт» — и foreground-сервис начинает отдавать эту
точку всем приложениям как вашу геопозицию через platform test-provider API.
Сервис продолжает работать, даже когда приложение свёрнуто.

Идея и архитектура подсмотрены у open-source проектов
[vincenzobpt/gps-mock-location](https://github.com/vincenzobpt/gps-mock-location)
и [timmylockley/gps-spoofer-free](https://github.com/timmylockley/gps-spoofer-free)
(Kotlin + osmdroid + foreground-сервис).

## Как пользоваться

1. Установите APK (из [Releases](../../releases) или соберите сами).
2. Включите режим разработчика: «Настройки → О телефоне» → 7 раз тапните
   «Номер сборки».
3. «Настройки → Система → Для разработчиков → Приложение для фиктивных
   геоданных» → выберите **MockLocate**.
4. Откройте приложение, выдайте разрешения, тапните точку на карте,
   нажмите **Старт**.
5. Остановка — кнопка **Стоп** в приложении или в уведомлении.

## Сборка вручную

Нужны JDK 17 и Android SDK (platform 35, build-tools 35.0.0):

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## CI

Workflow [`.github/workflows/ci.yml`](.github/workflows/ci.yml):

- собирает Docker-образ с Android SDK ([`Dockerfile`](Dockerfile));
- слои образа кэшируются через Buildx GHA-cache (`type=gha`) — `sdkmanager`
  не качает SDK заново;
- `~/.gradle` (дистрибутив Gradle, зависимости, build cache) кэшируется через
  `actions/cache` и монтируется в контейнер;
- каждый успешный билд кладёт `app-debug.apk` в артефакты workflow;
- пуш тега вида `v1.0.0` создаёт **GitHub Release** с прикреплённым APK.

Первый прогон качает всё с нуля (~5–8 минут), дальше — 1–3 минуты.

## Лицензия

MIT, см. [LICENSE](LICENSE). Только для разработки и тестирования.
