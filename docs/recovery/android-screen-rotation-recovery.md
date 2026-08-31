# Регламент диагностики и восстановления автоповорота экрана (Android / HyperOS)

## 1. Описание проблемы
При сбоях в оконном менеджере Android (`WindowManager`) экран перестает реагировать на поворот устройства или зависает в фиксированной ориентации (`ROTATION_0` или `ROTATION_90`).

### Первопричины:
1. **Блокировка на уровне дисплея (`mFixedToUserRotation=true`):** WindowManager принудительно фиксирует ориентацию, выключая системный слушатель датчиков (`WindowOrientationListener: mEnabled=false`).
2. **Сброс системных флагов HyperOS:** параметры `accelerometer_rotation` и внутренние переменные (`accelerometer_rotation_inner`) переводятся в `0`.
3. **Зависание сессии датчиков:** виртуальный сенсор ориентации `device_orient (Xiaomi)` или подсистема `OrientationSensorJudge` блокируются внешними вызовами.

---

## 2. Команда экстренного восстановления (One-Liner Recovery)

Выполните команду в Termux через Shizuku (`rish`) или через ADB с компьютера:

```sh
rish -c '
settings --user 0 put system accelerometer_rotation 1
settings --user 0 put system user_rotation 0
settings --user 0 put system accelerometer_rotation_inner 1
settings --user 0 put system user_rotation_inner 0
cmd window fixed-to-user-rotation -d 0 disabled
cmd window set-ignore-orientation-request -d 0 false
cmd window user-rotation -d 0 free 0
'
```

### Что делает скрипт:
1. Включает автоповорот в базовых и внутренних системных настройках (`accelerometer_rotation=1`).
2. Сбрасывает фиксированный пользовательский угол в вертикальное положение (`user_rotation=0`).
3. Отключает принудительную фиксацию ориентации дисплея (`cmd window fixed-to-user-rotation -d 0 disabled`).
4. Активирует режим свободной ориентации WindowManager (`cmd window user-rotation -d 0 free 0`), что запускает `WindowOrientationListener` (`mEnabled=true`).

---

## 3. Проверка статуса подсистемы

После выполнения команды проверьте статус параметров:

```sh
rish -c '
echo "accelerometer_rotation = $(settings --user 0 get system accelerometer_rotation)"
echo "fixed_to_user          = $(cmd window fixed-to-user-rotation -d 0)"
echo "user_rotation_mode     = $(cmd window user-rotation -d 0)"
dumpsys window displays | grep -E "mRotation=|mUserRotationMode|mFixedToUserRotation|mSupportAutoRotation"
'
```

### Ожидаемый эталонный вывод:
* `accelerometer_rotation = 1`
* `fixed_to_user = disabled`
* `user_rotation_mode = free`
* `mFixedToUserRotation = false`
* `mSupportAutoRotation = true`

---

## 4. Защита от несанкционированного изменения сторонними приложениями

1. **Контроль прав `WRITE_SETTINGS`:** Сторонние приложения не имеют права менять системные настройки ориентации. Проверка выданных прав:
   ```sh
   rish -c 'cmd appops query-op WRITE_SETTINGS allow'
   ```
   *(В списке должны присутствовать только системные компоненты Xiaomi/Android)*.

2. **Ограничение принудительного разворота экранов:**
   Режим `cmd window fixed-to-user-rotation -d 0 disabled` гарантирует, что приложения не смогут заблокировать дисплей при свободной ориентации.

---

## 5. Дополнительно: Перезапуск графического слоя (SystemUI)

Если системная шторка или панель навигации зависли в старом положении:

```sh
rish -c 'pids=$(pidof com.android.systemui); [ -n "$pids" ] && kill -9 $pids'
```
*(Графический интерфейс перезапустится за 1–2 секунды без потери данных)*.
