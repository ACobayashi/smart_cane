#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_chip_info.h"
#include "esp_flash.h"
#include "esp_log.h"
#include "sdkconfig.h"

static const char *TAG = "smart_cane_bringup";

void app_main(void)
{
    esp_chip_info_t chip_info;
    uint32_t flash_size = 0;

    esp_chip_info(&chip_info);
    esp_flash_get_size(NULL, &flash_size);

    ESP_LOGI(TAG, "ESP32-C5 smart cane ESP-IDF bring-up");
    ESP_LOGI(TAG, "Target: %s", CONFIG_IDF_TARGET);
    ESP_LOGI(TAG, "CPU cores: %d", chip_info.cores);
    ESP_LOGI(TAG, "Flash size: %lu MB", flash_size / (1024 * 1024));

    int counter = 0;
    while (true) {
        ESP_LOGI(TAG, "Board is alive, counter=%d", counter++);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
