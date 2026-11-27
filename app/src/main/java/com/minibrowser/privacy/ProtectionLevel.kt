package com.minibrowser.privacy

/**
 * Рівень захисту сайту.
 *  OFF      — списки не застосовуються, сторонні cookie дозволені (для сумісності зі зламаними сайтами).
 *  STANDARD — налаштування з розділу «Приватність» (списки EasyList/EasyPrivacy/URLhaus, сторонні cookie за перемикачем).
 *  STRICT   — усе зі STANDARD примусово + блок усіх сторонніх скриптів, iframe, XHR/fetch та object,
 *             заборона сторонніх cookie, лише https, очищення cookie й localStorage сайту після виходу з нього.
 */
enum class ProtectionLevel(val label: String, val description: String) {
    OFF("Вимкнено", "Без блокування. Для сайтів, що не працюють із захистом."),
    STANDARD("Стандартний", "Блокування за списками та налаштуваннями приватності."),
    STRICT(
        "Суворий",
        "Блокує всі сторонні скрипти, iframe і XHR, забороняє сторонні cookie, лише https, " +
            "очищає cookie й дані сайту після виходу. Частина сайтів може працювати некоректно.",
    ),
}
