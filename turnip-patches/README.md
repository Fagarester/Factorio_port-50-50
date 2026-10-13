# turnip-patches

Патчи для `.github/workflows/build-turnip-experimental.yml` (эксперимент: Turnip для Adreno 8xx).
Рецепт и патчи взяты из проекта **Banners-Turnip** (The412Banner, ветка A8xx, лицензия GPL-3.0):
https://github.com/The412Banner/Banners-Turnip

Состав (авторство - в `common/SOURCE`, `linux/SOURCE` и в заголовках самих патчей):
- `a8xx_gen8.patch` - стек whitebelyash `turnip/gen8` (конфигурации Adreno 8xx и др.);
- `a8xx_shared_mem.py`, `a840v2.py` - скрипты поверх стека (shared mem 64 КиБ, chip_id A840v2);
- `a8xx-winnative/0001-0005` - серия Max / MaxsTechReview (mesh shaders через compute, wave32, исправления зависаний A8xx);
- `linux/` - два KGSL-патча для glibc-сборки (DRM-узел, calibrated timestamps);
- `common/apply_common.sh` - применяет серию winnative и проверяет, что в Mesa есть исправления из MR 44838.

Патчи рассчитаны на Mesa `main` не старше 2026-10-08. На стабильных тегах (26.2.x) сборка остановится на проверке в `apply_common.sh`.
