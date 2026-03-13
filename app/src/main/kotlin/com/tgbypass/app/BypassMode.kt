package com.tgbypass.app

enum class BypassMode(val label: String, val fragmentSize: Int, val id: Int) {
    BASIC("Базовый", 4, 0),
    ENHANCED("Усиленный", 2, 1),
    MAXIMUM("Максимальный", 1, 2)
}
