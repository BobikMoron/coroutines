import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

data class Order(
    val id: String,
    val dishName: String,
    var cookingProgress: Int,
    var status: String
)

data class DishResult(
    val orderId: String,
    val dishName: String,
    val qualityScore: Double
)

suspend fun prepareIngredient(ingredientName: String): String {
    return withContext(Dispatchers.Default) {
        delay(1500)
        if (ingredientName == "лук") {
            "Нарезанный лук"
        } else if (ingredientName == "масло") {
            "Растопленное масло"
        } else {
            "Подготовленный ингредиент: $ingredientName"
        }
    }
}

suspend fun cookOrder(order: Order) = coroutineScope {
    val cooking = launch {
        try {
            while (isActive && order.cookingProgress < 100) {
                delay(500)
                order.cookingProgress += 10
                order.status = "Готовится"
                println("${order.id}: ${order.dishName} — ${order.cookingProgress}%")

                // Ошибка создаётся только у специально выбранного блюда.
                if (order.dishName == "Тестовое подгоревшее блюдо" && order.cookingProgress == 30) {
                    throw IllegalStateException("Блюдо подгорело")
                }
            }

            if (isActive) {
                order.status = "Готово"
                println("${order.id}: блюдо готово")
            }
        } catch (error: CancellationException) {
            println("${order.id}: готовка остановлена из-за отмены")
            throw error
        } catch (error: Exception) {
            order.status = "Ошибка"
            println("${order.id}: ошибка поймана — ${error.message}")
        }
    }

    val temperature = launch {
        try {
            // Если готовка остановилась, контроль температуры тоже больше не нужен.
            while (isActive && cooking.isActive && order.cookingProgress < 100) {
                delay(2000)
                if (isActive) {
                    println("${order.id}: температура проверена")
                }
            }
        } catch (error: CancellationException) {
            println("${order.id}: контроль температуры остановлен")
            throw error
        }
    }

    val monitor = launch {
        while (isActive && cooking.isActive && order.cookingProgress < 80) {
            delay(200)
        }
        if (isActive && order.cookingProgress >= 80) {
            println("${order.id}: Блюдо почти готово, пора на финишную обработку")
        }
    }

    cooking.join()
    temperature.join()
    monitor.join()
}

suspend fun checkDishQuality(orderId: String): Deferred<DishResult> {
    return coroutineScope {
        async {
            println("$orderId: шеф проверяет вкус")
            delay(3000)
            DishResult(orderId, "Блюдо заказа $orderId", 9.5)
        }
    }
}

suspend fun cancelOrder(order: Order, job: Job) {
    delay(2000)
    job.cancel()
    order.status = "Отменён"
    println("Заказ ${order.id} отменён, блюдо ${order.dishName} снято с приготовления")
}

suspend fun demonstrateChannel() = coroutineScope {
    val channel = Channel<DishResult>()

    val producer = launch {
        var number = 1
        while (number <= 5) {
            val result = DishResult("C$number", "Блюдо $number", 8.0 + number / 10.0)
            channel.send(result)
            println("Кухня отправила на раздачу: ${result.dishName}")
            number++
        }
        channel.close()
    }

    val waiter = launch {
        for (result in channel) {
            println("Метрдотель выдал ${result.dishName}, оценка: ${result.qualityScore}")
        }
        println("Метрдотель: все блюда выданы")
    }

    producer.join()
    waiter.join()
}

fun main() = runBlocking {
    println("1. Подготовка ингредиента")
    println(prepareIngredient("лук"))

    println("\n2. Обычная параллельная готовка")
    val normalOrder = Order("A1", "Ризотто", 0, "Принят")
    cookOrder(normalOrder)
    println("Проверка: статус заказа — ${normalOrder.status}")

    println("\n3. Отмена заказа")
    val cancelledOrder = Order("A2", "Стейк", 0, "Принят")
    val cookingJob = launch {
        try {
            cookOrder(cancelledOrder)
        } catch (error: CancellationException) {
            println("A2: общая область готовки отменена")
        }
    }
    val cancellationJob = launch {
        cancelOrder(cancelledOrder, cookingJob)
    }
    cookingJob.join()
    cancellationJob.join()
    println("Проверка: статус заказа — ${cancelledOrder.status}")

    println("\n4. Ошибка одного блюда")
    val badOrder = Order("A3", "Тестовое подгоревшее блюдо", 0, "Принят")
    val otherOrder = Order("A4", "Суп", 0, "Принят")
    val badJob = launch { cookOrder(badOrder) }
    val otherJob = launch { cookOrder(otherOrder) }
    badJob.join()
    otherJob.join()
    println("Проверка: ошибка A3 не остановила заказ A4")

    println("\n5. Проверка качества через async и await")
    val quality = checkDishQuality("A1").await()
    println("Оценка качества заказа ${quality.orderId}: ${quality.qualityScore}")

    println("\n6. Канал кухня — раздача")
    demonstrateChannel()

    println("\nВсе проверки закончены")
}
